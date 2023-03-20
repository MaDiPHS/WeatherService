package net.ipmdecisions.weather.datasourceadapters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ipmdecisions.weather.entity.LocationWeatherData;
import net.ipmdecisions.weather.entity.WeatherData;
import net.ipmdecisions.weather.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TahmoAdapterTest {

    private static final String TAHMO_STATION = "TA00321";
    private static final double LATITUDE = -15.903429;
    private static final double LONGITUDE = 35.21617;
    private static final String USERNAME = "user";
    private static final String PASSWORD = "pwd";
    private static final ZonedDateTime START_DATE = ZonedDateTime.parse("2021-01-01T00:00:00+03:00");
    private static final ZonedDateTime END_DATE = ZonedDateTime.parse("2021-01-01T23:59:59+03:00");
    private static final Set<Integer> IPM_CODES = Collections.singleton(1002);

    @Captor
    ArgumentCaptor<String> tahmoCodeCaptor;
    @Captor
    ArgumentCaptor<String> startDateTimeCaptor;
    @Captor
    ArgumentCaptor<String> endDateTimeCaptor;

    private static TahmoAdapter.TahmoConnection tahmoConnection;
    private static TahmoAdapter adapter;

    @BeforeEach
    void setUp() {
        tahmoConnection = Mockito.mock(TahmoAdapter.TahmoConnection.class);
        adapter = new TahmoAdapter(tahmoConnection);
    }

    @Test
    public void shouldReturnNullIfTahmoResponseIsEmpty() throws ParseWeatherDataException {
        when(tahmoConnection.findTahmoSeriesForParam(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        WeatherData weatherData = adapter.getWeatherData(TAHMO_STATION, LATITUDE, LONGITUDE, USERNAME, PASSWORD, START_DATE, END_DATE, IPM_CODES);
        assertNull(weatherData);
    }

    @Test
    public void shouldNotFailIfTahmoRespondsWithValidData() throws ParseWeatherDataException, JsonProcessingException {
        setupTahmoResponse("/tahmo_observations_1day_5m.json");
        WeatherData weatherData = adapter.getWeatherData(TAHMO_STATION, LATITUDE, LONGITUDE, USERNAME, PASSWORD, START_DATE, END_DATE, IPM_CODES);

        verify(tahmoConnection, times(1)).findTahmoSeriesForParam(
                tahmoCodeCaptor.capture(),
                eq(TAHMO_STATION),
                startDateTimeCaptor.capture(),
                endDateTimeCaptor.capture(),
                eq(USERNAME),
                eq(PASSWORD));

        assertEquals("te", tahmoCodeCaptor.getValue());
        assertEquals("2021-01-01T00:00:00Z", startDateTimeCaptor.getValue());
        assertEquals("2021-01-01T23:59:59Z", endDateTimeCaptor.getValue());
        assertNotNull(weatherData);
        assertEquals(3600, weatherData.getInterval());
        LocationWeatherData dataForParameter = weatherData.getDataForParameter(1002).get(0);
        assertEquals(LATITUDE, dataForParameter.getLatitude());
        assertEquals(LONGITUDE, dataForParameter.getLongitude());
        assertEquals(24, dataForParameter.getData().length);
    }

    private void setupTahmoResponse(String fileName) throws JsonProcessingException, ParseWeatherDataException {
        String tahmoResponse = new FileUtils().getStringFromFileInApp(fileName);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(tahmoResponse);
        when(tahmoConnection.findTahmoSeriesForParam(
                anyString(),
                eq(TAHMO_STATION),
                anyString(),
                anyString(),
                eq(USERNAME),
                eq(PASSWORD))
        ).thenReturn(response);
    }


}