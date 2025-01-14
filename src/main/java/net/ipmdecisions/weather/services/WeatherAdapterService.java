/*
 * Copyright (c) 2020 NIBIO <http://www.nibio.no/>. 
 * 
 * This file is part of IPM Decisions Weather Service.
 * IPM Decisions Weather Service is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * IPM Decisions Weather Service is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with IPM Decisions Weather Service.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package net.ipmdecisions.weather.services;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import javax.ejb.EJB;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import net.ipmdecisions.weather.controller.AmalgamationBean;
import net.ipmdecisions.weather.controller.WeatherDataSourceBean;
import net.ipmdecisions.weather.datasourceadapters.*;
import net.ipmdecisions.weather.datasourceadapters.finnishmeteorologicalinstitute.FinnishMeteorologicalInstituteAdapter;
import net.ipmdecisions.weather.entity.WeatherData;
import net.ipmdecisions.weather.entity.WeatherDataSource;
import net.ipmdecisions.weather.entity.WeatherDataSourceException;
import net.ipmdecisions.weather.util.WeatherDataUtil;
import org.jboss.resteasy.annotations.GZIP;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;
import javax.xml.datatype.DatatypeConfigurationException;
import net.ipmdecisions.weather.datasourceadapters.dmi.DMIPointWebDataParser;
import org.wololo.geojson.Feature;
import org.wololo.jts2geojson.GeoJSONReader;

/**
 * Some weather data sources may agree to deliver their weather data in the 
 * platform’s format directly. For the data sources that do not, adapters have 
 * to be programmed. The adapter's role is to download the data from the 
 * specified source and transform it into the platform's format. If the platform 
 * is using an adapter to download the weather data from a data source, the 
 * adapter's endpoint is specified in the weather data source catalogue.
 * 
 * @copyright 2020 <a href="http://www.nibio.no/">NIBIO</a>
 * @author Tor-Einar Skog <tor-einar.skog@nibio.no>
 */
@Path("rest/weatheradapter")
public class WeatherAdapterService {

    private static Logger LOGGER = LoggerFactory.getLogger(WeatherAdapterService.class);

    private static final String PARAM_USER_NAME = "userName";
    private static final String PARAM_PASSWORD = "password";

    private WeatherDataUtil weatherDataUtil;

    private static final String SECRET_KEY = System.getProperty("org.madiphs.weatherservice.TOKEN_SECRET_KEY");
    private static final Algorithm JWT_ALGORITHM = Algorithm.HMAC256(SECRET_KEY);
    public static final String TAHMO_TOKEN_ISSUER = "MaDiPHS";
    public static final String TAHMO_TOKEN_CLAIM = "userId";

    @EJB
    AmalgamationBean amalgamationBean;

    @EJB
    private WeatherDataSourceBean weatherDataSourceBean;
    
    /**
     * Get 9 day weather forecasts from <a href="https://www.met.no/en" target="new">The Norwegian Meteorological Institute</a>'s 
     * <a href="https://api.met.no/weatherapi/locationforecast/1.9/documentation" target="new">Locationforecast API</a> 
     * @param longitude WGS84 Decimal degrees
     * @param latitude WGS84 Decimal degrees
     * @param altitude Meters above sea level. This is used for correction of 
     * temperatures (outside of Norway, where the local topological model is used)
     * @pathExample /rest/weatheradapter/yr/?longitude=14.3711&latitude=67.2828&altitude=70
     * @return the weather forecast formatted in the IPM Decision platform's weather data format
     */
    @GET
    @POST
    @Path("yr/")
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    public Response getYRForecasts(
                    @QueryParam("longitude") Double longitude,
                    @QueryParam("latitude") Double latitude,
                    @QueryParam("altitude") Double altitude,
                    @QueryParam("parameters") String parameters
    )
    {
        if(longitude == null || latitude == null)
        {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing longitude and/or altitude. Please correct this.").build();
        }
        if(altitude == null)
        {
            altitude = 0.0;
        }
        
        Set<Integer> ipmDecisionsParameters = parameters != null ? Arrays.asList(parameters.split(",")).stream()
                .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toSet())
                : null;
        
        try 
        {
            WeatherData theData = new YrWeatherForecastAdapter().getWeatherForecasts(longitude, latitude, altitude);
            if(ipmDecisionsParameters != null && ipmDecisionsParameters.size() > 0)
            {
            	theData = new WeatherDataUtil().filterParameters(theData, ipmDecisionsParameters);
            }
            return Response.ok().entity(theData).build();
        } 
        catch (ParseWeatherDataException ex) 
        {
            return Response.serverError().entity(ex.getMessage()).build();
        }

    }
    
    /**
     * Get 9 day weather forecasts from <a href="https://data.gov.ie/" target="new">Met Éireann (Ireland)</a>'s 
     * <a href="https://data.gov.ie/dataset/met-eireann-weather-forecast-api" target="new">Locationforecast API</a> 
     * @param longitude WGS84 Decimal degrees
     * @param latitude WGS84 Decimal degrees
     * @pathExample /rest/weatheradapter/meteireann/?longitude=-7.644361&latitude=52.597709&parameters=1001, 3001
     * @return the weather forecast formatted in the IPM Decision platform's weather data format
     */
    @GET
    @POST
    @Path("meteireann/")
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMetIrelandForecasts(
                    @QueryParam("longitude") Double longitude,
                    @QueryParam("latitude") Double latitude,
                    @QueryParam("altitude") Double altitude,
                    @QueryParam("parameters") String parameters
    )
    {
        if(longitude == null || latitude == null)
        {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing longitude and/or altitude. Please correct this.").build();
        }
        if(altitude == null)
        {
            altitude = 0.0;
        }
        
        Set<Integer> ipmDecisionsParameters = parameters != null ? Arrays.asList(parameters.split(",")).stream()
                .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toSet())
                : null;
        
        try 
        {
            WeatherData theData = new MetIrelandWeatherForecastAdapter().getWeatherForecasts(longitude, latitude, altitude);
            if(ipmDecisionsParameters != null && ipmDecisionsParameters.size() > 0)
            {
            	theData = new WeatherDataUtil().filterParameters(theData, ipmDecisionsParameters);
            }
            return Response.ok().entity(theData).build();
        } 
        catch (ParseWeatherDataException ex) 
        {
            return Response.serverError().entity(ex.getMessage()).build();
        }

    }
    
    /**
     * Get 36 hour forecasts from FMI (The Finnish Meteorological Institute),
     * using their OpenData services at https://en.ilmatieteenlaitos.fi/open-data 
     * @param longitude WGS84 Decimal degrees
     * @param latitude WGS84 Decimal degrees

     * @pathExample /rest/weatheradapter/fmi/forecasts?latitude=67.2828&longitude=14.3711
     * @return the weather forecast formatted in the IPM Decision platform's weather data format
     */
    @GET
    @POST
    @Path("fmi/forecasts/")
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFMIForecasts(
                    @QueryParam("longitude") Double longitude,
                    @QueryParam("latitude") Double latitude,
                    @QueryParam("parameters") String parameters
    )
    {
        if(longitude == null || latitude == null)
        {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing longitude and/or latitude. Please correct this.").build();
        }
        
        Set<Integer> ipmDecisionsParameters = parameters != null ? Arrays.asList(parameters.split(",")).stream()
                .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toSet())
                : null;
        
        WeatherData theData = new FinnishMeteorologicalInstituteAdapter().getWeatherForecasts(longitude, latitude);
        if(ipmDecisionsParameters != null && ipmDecisionsParameters.size() > 0)
        {
        	theData = new WeatherDataUtil().filterParameters(theData, ipmDecisionsParameters);
        }
        return Response.ok().entity(theData).build();
    }
    
    /**
     * Get weather observations in the IPM Decision's weather data format from the Finnish Meteorological Institute https://en.ilmatieteenlaitos.fi/
     * Access is made through the Institute's open data API: https://en.ilmatieteenlaitos.fi/open-data
     * 
     * @param weatherStationId The weather station id (FMISID) in the open data API https://en.ilmatieteenlaitos.fi/observation-stations?filterKey=groups&filterQuery=weather
     * @param timeStart Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service
     * @pathExample /rest/weatheradapter/fmi/?weatherStationId=101104&interval=3600&ignoreErrors=true&timeStart=2020-06-12T00:00:00%2B03:00&timeEnd=2020-07-03T00:00:00%2B03:00&parameters=1002,3002
     * @return 
     */
    @GET
    @POST
    @Path("fmi/")
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFMIObservations(
            @QueryParam("weatherStationId") Integer weatherStationId,
            @QueryParam("timeStart") String timeStart,
            @QueryParam("timeEnd") String timeEnd,
            @QueryParam("interval") Integer logInterval,
            @QueryParam("parameters") String parameters,
            @QueryParam("ignoreErrors") String ignoreErrors
    )
    {
        List<Integer> ipmDecisionsParameters = Arrays.asList(parameters.split(",")).stream()
                    .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toList());
        
        
        Instant timeStartInstant;
        Instant timeEndInstant;
        
        // Date parsing
        // Is it a ISO-8601 timestamp or date?
        try
        {
            timeStartInstant = ZonedDateTime.parse(timeStart).toInstant();
            timeEndInstant = ZonedDateTime.parse(timeEnd).toInstant();
        }
        catch(DateTimeParseException ex)
        {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            timeStartInstant = LocalDate.parse(timeStart, dtf).atStartOfDay(ZoneId.of("Europe/Helsinki")).toInstant();//.atZone().toInstant();
            timeEndInstant = LocalDate.parse(timeEnd, dtf).atStartOfDay(ZoneId.of("Europe/Helsinki")).toInstant();//.atZone(ZoneId.of("Europe/Helsinki")).toInstant();     
        }
        
        Boolean ignoreErrorsB = ignoreErrors != null ? ignoreErrors.equals("true") : false;
        
        // We only accept requests for hourly data
        if(!logInterval.equals(3600))
        {
            return Response.status(Status.BAD_REQUEST).entity("This service only provides hourly data").build();
        }

        WeatherData theData = new FinnishMeteorologicalInstituteAdapter().getHourlyData(weatherStationId, timeStartInstant, timeEndInstant, ipmDecisionsParameters, ignoreErrorsB);

        return Response.ok().entity(theData).build();       
    }
    
    /**
     * Get weather observations and forecasts in the IPM Decision's weather data format from the Danish Meteorological Institute
     * 
     * @param longitude WGS84 Decimal degrees
     * @param latitude WGS84 Decimal degrees
     * @param timeStart Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service
     * @pathExample /rest/weatheradapter/dmipoint?latitude=56.488&longitude=9.583&parameters=2001&timeStart=2021-10-01&timeEnd=2021-10-20&interval=86400
     * @return 
     */
    @GET
    @POST
    @Path("dmipoint/")
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDMIPointWebObservations(
            @QueryParam("longitude") Double longitude,
            @QueryParam("latitude") Double latitude,
            @QueryParam("timeStart") String timeStart,
            @QueryParam("timeEnd") String timeEnd,
            @QueryParam("interval") Integer logInterval,
            @QueryParam("parameters") String parameters,
            @QueryParam("ignoreErrors") String ignoreErrors
    )
    {
         Set<Integer> ipmDecisionsParameters = parameters != null ? Arrays.asList(parameters.split(",")).stream()
                    .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toSet())
                 : null;
        
        
        Instant timeStartInstant;
        Instant timeEndInstant;
        
        // Date parsing
        // Is it a ISO-8601 timestamp or date?
        try
        {
            timeStartInstant = ZonedDateTime.parse(this.tryToFixTimestampString(timeStart)).toInstant();
            timeEndInstant = ZonedDateTime.parse(this.tryToFixTimestampString(timeEnd)).toInstant();
        }
        catch(DateTimeParseException ex)
        {
            DateTimeFormatter dtf = DateTimeFormatter.ISO_DATE;
            timeStartInstant = LocalDate.parse(timeStart, dtf).atStartOfDay(ZoneId.of("Europe/Copenhagen")).toInstant();
            timeEndInstant = LocalDate.parse(timeEnd, dtf).atStartOfDay(ZoneId.of("Europe/Copenhagen")).toInstant(); 
        }
        
        Boolean ignoreErrorsB = ignoreErrors != null ? ignoreErrors.equals("true") : false;
        
        
        // Default is hourly, optional is daily
        logInterval = (logInterval == null || logInterval != 86400) ? 3600 : 86400;
        
        if(longitude == null || latitude == null)
        {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing longitude and/or latitude. Please correct this.").build();
        }
        
        try
        {
            WeatherData theData = new DMIPointWebDataParser().getData(
                    longitude, latitude, 
                    Date.from(timeStartInstant), Date.from(timeEndInstant),
                    logInterval
            );
            if(theData == null)
            {
                return Response.noContent().build();
            }
            if(ipmDecisionsParameters != null && ipmDecisionsParameters.size() > 0)
            {
            	theData = new WeatherDataUtil().filterParameters(theData, ipmDecisionsParameters);
            }
            return Response.ok().entity(theData).build();
        }
        catch(DatatypeConfigurationException ex)
        {
            return Response.serverError().entity(ex.getMessage()).build();
        }

    }
    
    /**
     * Get weather observations and forecasts in the IPM Decision's weather data format from the LantMet service
     * of the Swedish University of Agricultural Sciences
     * 
     * @param longitude WGS84 Decimal degrees
     * @param latitude WGS84 Decimal degrees
     * @param timeStart Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service
     * @pathExample /rest/weatheradapter/dmipoint?latitude=56.488&longitude=9.583&parameters=2001&timeStart=2021-10-01&timeEnd=2021-10-20&interval=86400
     * @return 
     */
    @GET
    @POST
    @Path("lantmet/")
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSLULantMetObservations(
            @QueryParam("longitude") Double longitude,
            @QueryParam("latitude") Double latitude,
            @QueryParam("timeStart") String timeStart,
            @QueryParam("timeEnd") String timeEnd,
            @QueryParam("interval") Integer logInterval,
            @QueryParam("parameters") String parameters,
            @QueryParam("ignoreErrors") String ignoreErrors
    )
    {
         List<Integer> ipmDecisionsParameters = parameters != null ? Arrays.asList(parameters.split(",")).stream()
                    .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toList())
                 : null;
        
        
        Instant timeStartInstant;
        Instant timeEndInstant;
        
        // Date parsing
        // Is it a ISO-8601 timestamp or date?
        DateTimeFormatter dtf = DateTimeFormatter.ISO_DATE;
        try
        {
            timeStartInstant = ZonedDateTime.parse(timeStart).toInstant();
            timeEndInstant = ZonedDateTime.parse(timeEnd).toInstant();
        }
        catch(DateTimeParseException ex)
        {
            
            timeStartInstant = LocalDate.parse(timeStart, dtf).atStartOfDay(ZoneId.of("GMT+1")).toInstant();//.atZone().toInstant();
            timeEndInstant = LocalDate.parse(timeEnd, dtf).atStartOfDay(ZoneId.of("GMT+1")).toInstant();//.atZone(ZoneId.of("Europe/Helsinki")).toInstant();     
        }
        
        Boolean ignoreErrorsB = ignoreErrors != null ? ignoreErrors.equals("true") : false;
        
        
        // Default is hourly, optional is daily
        logInterval = (logInterval == null || logInterval != 86400) ? 3600 : 86400;
        
        if(longitude == null || latitude == null)
        {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing longitude and/or latitude. Please correct this.").build();
        }
        
        try
        {
            WeatherData theData = new SLULantMetAdapter().getData(
                    longitude, latitude, 
                    timeStartInstant,timeEndInstant,
                    logInterval,
                    ipmDecisionsParameters
            );
            if(theData == null)
            {
                return Response.noContent().build();
            }
            
            return Response.ok().entity(theData).build();
        }
        catch(DatatypeConfigurationException | IOException | WeatherDataSourceException ex)
        {
            return Response.serverError().entity(ex.getMessage()).build();
        }

    }
    /**
     * Get weather observations and forecasts in the IPM Decision's weather data format from the Open-Meteo.com service
     *
     * @param longitude WGS84 Decimal degrees
     * @param latitude WGS84 Decimal degrees
     * @param timeStart Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service
     * @pathExample /rest/weatheradapter/openmeteo?latitude=56.488&longitude=9.583&parameters=2001&timeStart=2021-10-01&timeEnd=2021-10-20&interval=86400
     * @return
     */
    @GET
    @POST
    @Path("openmeteo/")
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    public Response getOpenMeteoObservations(
            @QueryParam("longitude") Double longitude,
            @QueryParam("latitude") Double latitude,
            @QueryParam("timeStart") String timeStart,
            @QueryParam("timeEnd") String timeEnd,
            @QueryParam("interval") Integer logInterval,
            @QueryParam("parameters") String parameters,
            @QueryParam("ignoreErrors") String ignoreErrors
    )
    {
        List<Integer> ipmDecisionsParameters = parameters != null ? Arrays.asList(parameters.split(",")).stream()
                .map(paramstr->Integer.valueOf(paramstr.strip())).collect(Collectors.toList())
                : null;

        ZoneId tzForLocation = amalgamationBean.getTimeZoneForLocation(longitude, latitude);
        Instant timeStartInstant;
        Instant timeEndInstant;

        // Date parsing
        // Is it a ISO-8601 timestamp or date?
        DateTimeFormatter dtf = DateTimeFormatter.ISO_DATE;
        try
        {
            timeStartInstant = ZonedDateTime.parse(timeStart).toInstant();
            timeEndInstant = ZonedDateTime.parse(timeEnd).toInstant();
        }
        catch(DateTimeParseException ex)
        {

            timeStartInstant = LocalDate.parse(timeStart, dtf).atStartOfDay(ZoneId.of("GMT+1")).toInstant();//.atZone().toInstant();
            timeEndInstant = LocalDate.parse(timeEnd, dtf).atStartOfDay(ZoneId.of("GMT+1")).toInstant();//.atZone(ZoneId.of("Europe/Helsinki")).toInstant();
        }

        Boolean ignoreErrorsB = ignoreErrors != null ? ignoreErrors.equals("true") : false;


        // Default is hourly, optional is daily
        logInterval = (logInterval == null || logInterval != 86400) ? 3600 : 86400;

        if(longitude == null || latitude == null)
        {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing longitude and/or latitude. Please correct this.").build();
        }

        try
        {
            WeatherData theData = new OpenMeteoAdapter().getData(
                    longitude, latitude, tzForLocation,
                    timeStartInstant,timeEndInstant,
                    logInterval,
                    ipmDecisionsParameters
            );
            if(theData == null)
            {
                return Response.noContent().build();
            }

            return Response.ok().entity(theData).build();
        }
        catch(WeatherDataSourceException ex)
        {
            return Response.serverError().entity(ex.getMessage()).build();
        }

    }
    /**
     * Get weather observations in the IPM Decision's weather data format from the the network of MeteoBot stations 
     * [https://meteobot.com/en/]
     * 
     * This is a network of privately owned weather stations, which all require 
     * authentication to access.
     * 
     * @param weatherStationId The weather station id 
     * @param timeStart Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service
     * @param credentials json object with "userName" and "password" properties set
     * @requestExample application/x-www-form-urlencoded
     *   weatherStationId:536
     *   interval:3600
     *   ignoreErrors:true
     *   timeStart:2020-06-12
     *   timeEnd:2020-07-03
     *   parameters:1002,3002,2001
     *   credentials:{"userName":"XXXXX","password":"XXXX"}
     * @return 
     */
    @POST
    @Path("meteobot/")
    @GZIP
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMeteobotObservations(
            @FormParam("weatherStationId") Integer weatherStationId,
            @FormParam("timeStart") String timeStart,
            @FormParam("timeEnd") String timeEnd,
            @FormParam("interval") Integer logInterval,
            @FormParam("parameters") String parameters,
            @FormParam("ignoreErrors") String ignoreErrors,
            @FormParam("credentials") String credentials
    )
    {
        // We only accept requests for hourly data
        if(!logInterval.equals(3600))
        {
            return Response.status(Status.BAD_REQUEST).entity("This service only provides hourly data").build();
        }
        try
        {
            JsonNode json = new ObjectMapper().readTree(credentials);
            String userName = json.get("userName").asText();
            String password = json.get("password").asText();

            Set<Integer> ipmDecisionsParameters = new HashSet(Arrays.asList(parameters.split(",")).stream()
                    .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toList()));


            // Date parsing
            LocalDate startDate, endDate;
            try
            {
                startDate = LocalDate.parse(timeStart);
                endDate = LocalDate.parse(timeEnd);
            }
            catch(DateTimeParseException ex)
            {
                ZonedDateTime zStartDate = ZonedDateTime.parse(timeStart);
                ZonedDateTime zEndDate = ZonedDateTime.parse(timeEnd);
                startDate = zStartDate.toLocalDate();
                endDate = zEndDate.toLocalDate();
            }

            //LOGGER.debug("timeStart=" + timeStart + " => startDate=" + startDate + ". timeEnd=" + timeEnd + " => endDate=" + endDate);
            Boolean ignoreErrorsB = ignoreErrors != null ? ignoreErrors.equals("true") : false;

            WeatherData theData = new MeteobotAPIAdapter().getWeatherData(weatherStationId,userName,password,startDate, endDate);
            //LOGGER.debug(this.getWeatherDataUtil().serializeWeatherData(this.getWeatherDataUtil().filterParameters(theData, ipmDecisionsParameters)));
            return Response.ok().entity(this.getWeatherDataUtil().filterParameters(theData, ipmDecisionsParameters)).build();
        }
        catch(JsonProcessingException | ParseWeatherDataException ex)
        {
            ex.printStackTrace();
            return Response.serverError().entity(ex).build();
        }
    }

    /**
     * Get weather observations in the IPM Decision's weather data format from the network of Tahmo stations.
     *
     * @param stationCode  The weather station id
     * @param timeStart    Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd      End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval  The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters   Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service. Currently not in use.
     * @param credentials  json object with "userName" and "password" properties set
     * @param authHeader   if credentials are not given, a JSON Web Token (JWT) can be used instead
     * @return weather data for the requested weather station and time period
     * @requestExample application/x-www-form-urlencoded
     * weatherStationId:TA00321
     * interval:3600
     * ignoreErrors:true
     * timeStart:2021-01-01T00:00:00+03:00
     * timeEnd:2021-01-06T23:59:59+03:00
     * parameters:1002,3002,2001
     * credentials:{"userName":"X","password":"Y"}
     */
    @POST
    @Path("tahmo/")
    @GZIP
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTahmoObservations(
            @FormParam("weatherStationId") String stationCode,
            @FormParam("timeStart") String timeStart,
            @FormParam("timeEnd") String timeEnd,
            @FormParam("interval") Integer logInterval,
            @FormParam("parameters") String parameters,
            @FormParam("ignoreErrors") String ignoreErrors,
            @FormParam("credentials") String credentials,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader
    ) {
        LOGGER.debug("Request Tahmo observations for weather station {}", stationCode);

        // Verify that all necessary environment variables are set
        String validUserName = System.getProperty("org.madiphs.weatherservice.TOKEN_USERNAME");
        String tahmoUserName = System.getProperty("org.madiphs.weatherservice.TAHMO_USERNAME");
        String tahmoPassword = System.getProperty("org.madiphs.weatherservice.TAHMO_PASSWORD");
        if(SECRET_KEY == null || validUserName == null || tahmoUserName == null || tahmoPassword == null) {
            return Response.serverError().entity("Web service is missing required configuration").build();
        }
        String userName, password;
        // If credentials are given, use them for authentication
        if(credentials != null && !credentials.trim().isEmpty()) {
            try {
                JsonNode node = new ObjectMapper().readTree(credentials);
                userName = node.get(PARAM_USER_NAME).asText();
                password = node.get(PARAM_PASSWORD).asText();
            } catch (JsonProcessingException jpe) {
                LOGGER.error("Unable to parse credentials", jpe);
                return Response.status(Status.UNAUTHORIZED).entity("Unable to parse credentials").build();
            }
        }
        // Validate token, set userName and password to values from environment variables
        else if(authHeader != null && !authHeader.trim().isEmpty() && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7); // Remove "Bearer " prefix
                DecodedJWT decodedJWT = JWT.require(JWT_ALGORITHM).withIssuer(TAHMO_TOKEN_ISSUER).build().verify(token);
                String decodedClaim = decodedJWT.getClaim(TAHMO_TOKEN_CLAIM).asString();
                if (validUserName.equals(decodedClaim)) {
                    userName = tahmoUserName;
                    password = tahmoPassword;
                } else {
                    LOGGER.error("'{}' is not a valid username", decodedClaim);
                    return Response.status(Status.UNAUTHORIZED).entity("Token does not contain a valid username").build();
                }
            } catch (TokenExpiredException tee) {
                LOGGER.error("Given token has expired", tee);
                return Response.status(Status.UNAUTHORIZED).entity(tee.getMessage()).build();
            } catch (Exception e) {
                LOGGER.error("Unable to decode or validate token", e);
                return Response.status(Status.UNAUTHORIZED).entity("Unable to decode or validate token").build();
            }
        }
        else {
            LOGGER.error("Credentials and token missing from request");
            return Response.status(Status.UNAUTHORIZED).entity("Credentials or token must be provided").build();
        }

        if (!logInterval.equals(3600)) {
            return Response.status(Status.BAD_REQUEST).entity("This service only provides hourly data").build();
        }

        String wds = "org.tahmo";
        WeatherDataSource weatherDataSource;
        try {
            weatherDataSource = weatherDataSourceBean.getWeatherDataSourceById(wds);
        } catch (IOException e) {
            LOGGER.error("Unable to find weather data source {}", wds, e);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity("Unable to find weather data source").build();
        }
        GeoJSONReader reader = new GeoJSONReader();
        Feature station = weatherDataSource.getStation(stationCode);
        Coordinate coordinate = reader.read(station.getGeometry()).getCoordinate();

        Set<Integer> intParamSet = Stream.of(parameters.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());

        ZonedDateTime startDate = ZonedDateTime.parse(timeStart);
        ZonedDateTime endDate = ZonedDateTime.parse(timeEnd);

        // TODO This parameter is never used
        Boolean ignoreErrorsB = ignoreErrors != null && ignoreErrors.equals("true");

        try {
            TahmoAdapter.TahmoConnection tahmoConnection = new TahmoAdapter.TahmoConnection();
            return Response.ok().entity(new TahmoAdapter(tahmoConnection).getWeatherData(
                    stationCode,
                    coordinate.x,
                    coordinate.y,
                    userName,
                    password,
                    startDate,
                    endDate,
                    intParamSet
            )).build();
        } catch (ParseWeatherDataException e) {
            return Response.serverError().entity(e).build();
        }
    }

    /**
     * Get weather observations in the IPM Decision's weather data format from the the network of Pessl Instruments Metos stations 
     * [https://metos.at/]
     * 
     * This is a network of privately owned weather stations, which all require 
     * authentication to access.
     * 
     * @param weatherStationId The weather station id 
     * @param timeStart Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service
     * @param credentials json object with "userName" and "password" properties set
     * @requestExample application/x-www-form-urlencoded
     *   weatherStationId:536
     *   interval:3600
     *   ignoreErrors:true
     *   timeStart:2020-06-12
     *   timeEnd:2020-07-03
     *   parameters:1002,3002,2001
     *   credentials:{"userName":"XXXXX","password":"XXXX"}
     * @return 
     */
    @POST
    @Path("metos/")
    @GZIP
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMetosObservations(
            @FormParam("weatherStationId") String weatherStationId,
            @FormParam("timeStart") String timeStart,
            @FormParam("timeEnd") String timeEnd,
            @FormParam("interval") Integer logInterval,
            @FormParam("parameters") String parameters,
            @FormParam("ignoreErrors") String ignoreErrors,
            @FormParam("credentials") String credentials
    )
    {
        // We only accept requests for hourly data
        if(!logInterval.equals(3600))
        {
            return Response.status(Status.BAD_REQUEST).entity("This service only provides hourly data").build();
        }
        try
        {
            JsonNode json = new ObjectMapper().readTree(credentials);
            String publicKey = json.get("userName").asText();
            String privateKey = json.get("password").asText();

            Set<Integer> ipmDecisionsParameters = new HashSet(Arrays.asList(parameters.split(",")).stream()
                    .map(paramstr->Integer.parseInt(paramstr.strip())).collect(Collectors.toList()));
            // Date parsing
            LocalDate startDate, endDate;
            try
            {
                startDate = LocalDate.parse(timeStart);
                endDate = LocalDate.parse(timeEnd);
            }
            catch(DateTimeParseException ex)
            {
                ZonedDateTime zStartDate = ZonedDateTime.parse(timeStart);
                ZonedDateTime zEndDate = ZonedDateTime.parse(timeEnd);
                startDate = zStartDate.toLocalDate();
                endDate = zEndDate.toLocalDate();
            }

            Boolean ignoreErrorsB = ignoreErrors != null ? ignoreErrors.equals("true") : false;

            WeatherData theData = new MetosAPIAdapter().getWeatherData(weatherStationId,publicKey,privateKey,startDate, endDate);
            if(theData != null)
            {
                //LOGGER.debug(this.getWeatherDataUtil().serializeWeatherData(this.getWeatherDataUtil().filterParameters(theData, ipmDecisionsParameters)));
                return Response.ok().entity(this.getWeatherDataUtil().filterParameters(theData, ipmDecisionsParameters)).build();
            }
            else
            {
                return Response.status(Status.NO_CONTENT).build();
            }
        }
        catch(ParseWeatherDataException | GeneralSecurityException | IOException ex)
        {
            return Response.serverError().entity(ex).build();
        }
    }
    
    /**
     * Get weather observations in the IPM Decision's weather data format from the the network of Fruitweb attached stations 
     * [https://www.fruitweb.info/en/]
     * 
     * This is a network of privately owned weather stations, which all require 
     * authentication to access.
     * 
     * @param weatherStationId The weather station id 
     * @param timeZoneId e.g. "Europe/Oslo". Optional. Default is UTC
     * @param timeStart Start of weather data period (ISO-8601 Timestamp, e.g. 2020-06-12T00:00:00+03:00)
     * @param timeEnd End of weather data period (ISO-8601 Timestamp, e.g. 2020-07-03T00:00:00+03:00)
     * @param logInterval The measuring interval in seconds. Please note that the only allowed interval in this version is 3600 (hourly)
     * @param parameters Comma separated list of the requested weather parameters, given by <a href="/rest/parameter" target="new">their codes</a>
     * @param ignoreErrors Set to "true" if you want the service to return weather data regardless of there being errors in the service
     * @param credentials json object with "userName" and "password" properties set
     * @requestExample application/x-www-form-urlencoded
     *   weatherStationId:536
     *   interval:3600
     *   ignoreErrors:true
     *   timeStart:2020-06-12
     *   timeEnd:2020-07-03
     *   parameters:1002,3002,2001
     *   credentials:{"userName":"XXXXX","password":"XXXX"}
     * @return 
     */
    @POST
    @Path("davisfruitweb/")
    @GZIP
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDavisFruitwebObservations(
            @FormParam("weatherStationId") String weatherStationId,
            @FormParam("timeZone") String timeZoneId,
            @FormParam("timeStart") String timeStart,
            @FormParam("timeEnd") String timeEnd,
            @FormParam("interval") Integer logInterval,
            @FormParam("parameters") String parameters,
            @FormParam("ignoreErrors") String ignoreErrors,
            @FormParam("credentials") String credentials
    )
    {
        //LOGGER.debug("(getDavisFruitwebObservations) timeZone=" + timeZoneId);
        TimeZone timeZone = timeZoneId != null ? TimeZone.getTimeZone(ZoneId.of(timeZoneId)) : TimeZone.getTimeZone("UTC");
        // We only accept requests for hourly data
        if(!logInterval.equals(3600))
        {
            return Response.status(Status.BAD_REQUEST).entity("This service only provides hourly data").build();
        }
        try
        {
            JsonNode json = new ObjectMapper().readTree(credentials);
            String userName = json.get("userName").asText();
            String password = json.get("password").asText();

            Set<Integer> ipmDecisionsParameters = new HashSet(Arrays.asList(parameters.split(",")).stream()
                    .map(paramstr->Integer.valueOf(paramstr.strip())).collect(Collectors.toList()));
            // Date parsing
            LocalDate startDate, endDate;
            try
            {
                startDate = LocalDate.parse(timeStart);
                endDate = LocalDate.parse(timeEnd);
            }
            catch(DateTimeParseException ex)
            {
                ZonedDateTime zStartDate = ZonedDateTime.parse(timeStart);
                ZonedDateTime zEndDate = ZonedDateTime.parse(timeEnd);
                startDate = zStartDate.toLocalDate();
                endDate = zEndDate.toLocalDate();
            }

            Boolean ignoreErrorsB = ignoreErrors != null ? ignoreErrors.equals("true") : false;



            WeatherData theData = new DavisFruitwebAdapter().getWeatherData(weatherStationId, password, startDate, endDate, timeZone);
            //LOGGER.debug(this.getWeatherDataUtil().serializeWeatherData(this.getWeatherDataUtil().filterParameters(theData, ipmDecisionsParameters)));
            return Response.ok().entity(this.getWeatherDataUtil().filterParameters(theData, ipmDecisionsParameters)).build();
        }
        catch(ParseWeatherDataException | IOException ex)
        {
            return Response.serverError().entity(ex).build();
        }
    }
    
    private WeatherDataUtil getWeatherDataUtil()
    {
        if(this.weatherDataUtil == null)
        {
            this.weatherDataUtil = new WeatherDataUtil();
        }
        return this.weatherDataUtil;
    }
    
    /**
     * Through URL decoding, some chars like "+" may get lost. We try to fix this.
     * 
     * @param timestampStr
     * @return
     */
    private String tryToFixTimestampString(String timestampStr)
    {
    	// Date parsing
        // Is it a ISO-8601 timestamp or date?
        try
        {
            ZonedDateTime.parse(timestampStr).toInstant();
            // All is well, return unchanged
            return timestampStr;
        }
        catch(DateTimeParseException ex1)
        {
        	// Something went wrong
        	// Hypothesis 1: The + is missing
        	String mod1 = timestampStr.replace(" ", "+");
        	try
        	{
	        	ZonedDateTime.parse(mod1).toInstant();
	            // All is well, return modified
	            return mod1;
        	}
        	catch(DateTimeParseException ex2)
            {
        		// No more hypothesis - return original
        		return timestampStr;
            } 
        }
    }
}
