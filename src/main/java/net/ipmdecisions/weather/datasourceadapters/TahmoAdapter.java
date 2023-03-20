package net.ipmdecisions.weather.datasourceadapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import net.ipmdecisions.weather.entity.WeatherData;
import net.ipmdecisions.weather.util.vips.InvalidAggregationTypeException;
import net.ipmdecisions.weather.util.vips.VIPSWeatherObservation;
import net.ipmdecisions.weather.util.vips.WeatherObservationListException;
import net.ipmdecisions.weather.util.vips.WeatherUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TahmoAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TahmoAdapter.class);

    private static final DateFormat OLD_DATE_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final TimeZone OLD_DEFAULT_TIME_ZONE = TimeZone.getTimeZone("GMT");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("UTC");

    private final TahmoConnection tahmoConnection;
    private final WeatherUtils weatherUtils;

    public enum TahmoVariable {
        SURFACE_AIR_TEMPERATURE("te", "TM", 1002, 1, WeatherUtils.AGGREGATION_TYPE_AVERAGE),
        PRECIPITATION("pr", "RR", 2001, 1, WeatherUtils.AGGREGATION_TYPE_SUM),
        WIND_DIRECTION("wd", "DM2", 4001, 1, WeatherUtils.AGGREGATION_TYPE_AVERAGE),
        WIND_SPEED("ws", "FM2", 4003, 1, WeatherUtils.AGGREGATION_TYPE_AVERAGE),
        WIND_GUSTS("wg", "FG2", 4002, 1, WeatherUtils.AGGREGATION_TYPE_MAXIMUM),
        SHORTWAVE_RADIATION("ra", "Q0", 5001, 1, WeatherUtils.AGGREGATION_TYPE_AVERAGE),
        SOIL_TEMPERATURE("st", "TJM10", 1112, 1, WeatherUtils.AGGREGATION_TYPE_AVERAGE),
        RELATIVE_HUMIDITY("rh", "UM", 3002, 100, WeatherUtils.AGGREGATION_TYPE_AVERAGE); // RH values from Tahmo parsers are between 0-1

        private final String tahmoCode;
        private final String lmtCode;
        private final int weatherParameterId;
        private final int factor;
        private final int aggregationType;

        TahmoVariable(String tahmoCode, String lmtCode, int weatherParameterId, int factor, int aggregationType) {
            this.tahmoCode = tahmoCode;
            this.lmtCode = lmtCode;
            this.weatherParameterId = weatherParameterId;
            this.factor = factor;
            this.aggregationType = aggregationType;
        }

        public static Set<TahmoVariable> forIpmCodes(Set<Integer> ipmCodes) {
            return Arrays.stream(TahmoVariable.values()).filter(e -> ipmCodes.contains(e.weatherParameterId)).collect(Collectors.toSet());
        }
    }

    // To enable mocking in tests
    public TahmoAdapter(TahmoConnection tahmoConnection) {
        this.tahmoConnection = tahmoConnection;
        this.weatherUtils = new WeatherUtils();
        OLD_DATE_TIME_FORMAT.setTimeZone(OLD_DEFAULT_TIME_ZONE);
    }

    public WeatherData getWeatherData(String stationCode, double latitude, double longitude, String userName, String password, ZonedDateTime startDate, ZonedDateTime endDate, Set<Integer> ipmCodes) throws ParseWeatherDataException {
        LOGGER.info("Get weather data for Tahmo station {}", stationCode);

        // Then get the weather data and convert to IPM Decisions format
        List<VIPSWeatherObservation> observations = this.getWeatherObservations(
                stationCode,
                userName,
                password,
                startDate,
                endDate,
                TahmoVariable.forIpmCodes(ipmCodes)
        );
        return weatherUtils.getWeatherDataFromVIPSWeatherObservations(observations, longitude, latitude, 0);
    }

    private List<VIPSWeatherObservation> getWeatherObservations(String stationCode, String userName, String password, ZonedDateTime startDateTime, ZonedDateTime endDateTime, Set<TahmoVariable> variables) throws ParseWeatherDataException {
        List<VIPSWeatherObservation> observations = new ArrayList<>();

        // Must find observations for each given param individually
        for (TahmoVariable variable : variables) {
            Map<TahmoVariable, List<VIPSWeatherObservation>> variableObservationsMap = new HashMap<>();
            JsonNode series = tahmoConnection.findTahmoSeriesForParam(variable.tahmoCode, stationCode, startDateTime.format(DATE_TIME_FORMATTER), endDateTime.format(DATE_TIME_FORMATTER), userName, password);

            // Return empty list if Tahmo response is empty/incomplete
            if (series == null || series.findValue("values") == null) {
                LOGGER.info("No valid response from Tahmo, return empty list");
                return observations;
            }

            List<String> columns = Arrays.asList(tokenize(series.findValue("columns")));
            int timeIndex = columns.indexOf("time");
            int variableIndex = columns.indexOf("variable");
            int valueIndex = columns.indexOf("value");

            JsonNode values = series.findValue("values");
            int logIntervalId = findLogIntervalId(values);

            // For all lines in Tahmo datahub response:
            for (JsonNode line : values) {
                String[] lineData = tokenize(line);

                // Skip line without content
                if (lineData.length <= 1) {
                    continue;
                }

                String lineVariable = lineData[variableIndex];
                String lineValue = lineData[valueIndex];
                String lineTime = lineData[timeIndex];

                if (variable.tahmoCode.equals(lineVariable) && !lineValue.equals("null")) {
                    VIPSWeatherObservation obs = createWeatherObservation(variable, lineTime, lineValue, logIntervalId);

                    if (logIntervalId == VIPSWeatherObservation.LOG_INTERVAL_ID_5M) {
                        if (!variableObservationsMap.containsKey(variable)) {
                            variableObservationsMap.put(variable, new ArrayList<>());
                        }
                        variableObservationsMap.get(variable).add(obs);
                    } else { // Always 1h if not 5m?
                        observations.add(obs);
                    }
                }
            }
            // Convert from 5 min intervals to hourly intervals
            if(logIntervalId == VIPSWeatherObservation.LOG_INTERVAL_ID_5M) {
                List<VIPSWeatherObservation> observationsForElement = variableObservationsMap.get(variable);
                LOGGER.info("Convert {} observations to hourly for element {}", observationsForElement.size(), variable.name());
                try {
                    observationsForElement = removeDuplicateWeatherObservations(observationsForElement, 0.05);
                } catch (WeatherObservationListException e) {
                    LOGGER.error("Unable to remove duplicate weather observations for element {}", variable.name(), e);
                    throw new ParseWeatherDataException(e.getMessage());
                }
                try {
                    observationsForElement = weatherUtils.getAggregateHourlyValues(observationsForElement, OLD_DEFAULT_TIME_ZONE, VIPSWeatherObservation.LOG_INTERVAL_ID_5M, variable.aggregationType);
                } catch (WeatherObservationListException e) {
                    LOGGER.error("Unable to aggregate hourly values for element {}", variable.name(), e);
                    throw new ParseWeatherDataException(e.getMessage());
                } catch (InvalidAggregationTypeException e) {
                    LOGGER.error("Invalid aggregation type {} for element {}", variable.aggregationType, variable.name(), e);
                    throw new ParseWeatherDataException(e.getMessage());
                }
                observations.addAll(observationsForElement);
            }
        }
        Collections.sort(observations);
        return observations;
    }

    // Copied from no.nibio.vips.util.WeatherUtil#removeDuplicateWeatherObservations
    // Should probably be moved to Util class and tested there. See test here:
    // no.nibio.vips.util.WeatherUtilTest#testRemoveDuplicateWeatherObservations
    private List<VIPSWeatherObservation> removeDuplicateWeatherObservations
    (List<VIPSWeatherObservation> observations, Double maximumDuplicateRatio) throws
            WeatherObservationListException {
        if (maximumDuplicateRatio == null) {
            maximumDuplicateRatio = 0.05;
        }

        HashMap<Long, VIPSWeatherObservation> uniqueMap = new HashMap();
        observations.forEach((observation) -> {
            uniqueMap.put(observation.getValiditySignature(), observation);
        });
        List<VIPSWeatherObservation> retVal = new ArrayList(uniqueMap.values());
        Double numberOfDuplicates = new Double((double) (observations.size() - retVal.size()));
        if (numberOfDuplicates / (double) observations.size() > maximumDuplicateRatio) {
            throw new WeatherObservationListException("Too many duplicates for " + ((VIPSWeatherObservation) observations.get(0)).getElementMeasurementTypeId() + ": " + numberOfDuplicates + "(" + numberOfDuplicates / (double) observations.size() + "%)");
        } else {
            return retVal;
        }
    }

    private VIPSWeatherObservation createWeatherObservation(
            TahmoVariable variable,
            String lineDate,
            String lineValue,
            int logIntervalId
    ) throws ParseWeatherDataException {
        VIPSWeatherObservation obs = new VIPSWeatherObservation();
        obs.setElementMeasurementTypeId(variable.lmtCode);
        obs.setTimeMeasured(parseDateTime(lineDate));
        obs.setValue(Double.parseDouble(lineValue) * variable.factor);
        obs.setLogIntervalId(logIntervalId);
        return obs;
    }

    private Date parseDateTime(String dateStr) throws ParseWeatherDataException {
        try {
            return OLD_DATE_TIME_FORMAT.parse(dateStr);
        } catch (ParseException e) {
            LOGGER.error("Unable to parse date in Tahmo response {}", dateStr, e);
            throw new ParseWeatherDataException(e.getMessage());
        }
    }

    // TODO Find better solution
    // Assuming that two consecutive timestamps with minutes=00 means hourly interval, otherwise 5 minute interval
    private Integer findLogIntervalId(JsonNode values) {
        if (values == null || values.size() < 2) {
            return VIPSWeatherObservation.LOG_INTERVAL_ID_1H;
        }
        LocalDateTime firstDateTime = LocalDateTime.parse(tokenize(values.get(0))[0], DATE_TIME_FORMATTER);
        LocalDateTime secondDateTime = LocalDateTime.parse(tokenize((values.get(1)))[0], DATE_TIME_FORMATTER);

        if (firstDateTime.getMinute() != 0 || secondDateTime.getMinute() != 0) {
            LOGGER.info("Assume 5m interval based on {} and {}", firstDateTime, secondDateTime);
            return VIPSWeatherObservation.LOG_INTERVAL_ID_5M;
        }
        LOGGER.info("Assume 1h interval based on {} and {}", firstDateTime, secondDateTime);
        return VIPSWeatherObservation.LOG_INTERVAL_ID_1H;
    }

    private String[] tokenize(JsonNode jsonNode) {
        String content = jsonNode.toString();
        String cleanContent = content.replaceAll("[\\[\\]^\"|\"$]", "");
        return cleanContent.split(",");
    }

    public static class TahmoConnection {
        private final static String URL_MEASUREMENTS = "https://datahub.tahmo.org/services/measurements/v2/stations/{0}/measurements/{1}?start={2}&end={3}&variable={4}";
        // e.g. https://datahub.tahmo.org/services/measurements/v2/stations/TA00321/measurements/controlled?start=2021-01-01T00:00:00Z&end=2021-01-02T00:00:00Z&variable=pr

        public JsonNode findTahmoSeriesForParam(String tahmoParam, String stationCode, String start, String end, String userName, String password) throws ParseWeatherDataException {
            // TODO Simplify reading json from URL: https://www.baeldung.com/java-read-json-from-url
            String endpoint = MessageFormat.format(
                    URL_MEASUREMENTS,
                    stationCode,
                    "controlled", // TODO Might be 'raw'? Currently not configurable as param.
                    start,
                    end,
                    tahmoParam
            );
            LOGGER.info("Get Tahmo data from endpoint: {}", endpoint);
            URLConnection authConnection = getAuthConnection(endpoint, userName, password);
            JsonNode rootNode = findJsonRootNode(authConnection);
            return rootNode.findValue("series");
        }

        private URLConnection getAuthConnection(String endpoint, String userName, String password) throws
                ParseWeatherDataException {
            URLConnection connection;
            try {
                connection = new URL(endpoint).openConnection();
            } catch (IOException e) {
                LOGGER.error("Unable to connect to {}", endpoint, e);
                throw new ParseWeatherDataException(e.getMessage());
            }
            String userPass = userName + ":" + password;
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes());
            connection.setRequestProperty("Authorization", basicAuth);
            return connection;
        }

        private JsonNode findJsonRootNode(URLConnection tahmoConnection) throws ParseWeatherDataException {
            JsonNode rootNode;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(tahmoConnection.getInputStream()))) {
                StringBuilder all = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    all.append(line);
                }
                ObjectMapper oMapper = new ObjectMapper();
                rootNode = oMapper.readTree(all.toString());
            } catch (IOException e) {
                LOGGER.error("Unable to find base JSON node", e);
                throw new ParseWeatherDataException(e.getMessage());
            }
            return rootNode;
        }


    }
}
