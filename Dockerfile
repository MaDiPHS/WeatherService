# the first stage of our build will use a maven 3.8 parent image
FROM maven:3.8-openjdk-17 AS maven_build
 
# copy the pom and src code to the container
COPY ./ ./
 
# package our application code
RUN mvn clean install

RUN git clone --single-branch --branch main https://gitlab.nibio.no/madiphs/weather-metadata.git Weather-Metadata
RUN git clone --single-branch --branch master https://github.com/datasets/geo-countries.git

# Used this as a template: https://github.com/jboss-dockerfiles/wildfly/blob/master/Dockerfile 
# Use latest jboss/base-jdk:11 image as the base
FROM eclipse-temurin:17-jammy

RUN groupadd -r jboss -g 1000 && useradd -u 1000 -r -g jboss -m -d /opt/jboss -s /sbin/nologin -c "JBoss user" jboss && chmod 755 /opt/jboss

# Set the WILDFLY_VERSION env variable
ENV WILDFLY_VERSION=26.1.3.Final
ENV WILDFLY_SHA1=b9f52ba41df890e09bb141d72947d2510caf758c
ENV JBOSS_HOME=/opt/jboss/wildfly

USER root

# Add the WildFly distribution to /opt, and make wildfly the owner of the extracted tar content
# Make sure the distribution is available from a well-known place
RUN cd $HOME \
    && curl -O -L https://github.com/wildfly/wildfly/releases/download/$WILDFLY_VERSION/wildfly-$WILDFLY_VERSION.tar.gz \
    && sha1sum wildfly-$WILDFLY_VERSION.tar.gz | grep $WILDFLY_SHA1 \
    && tar xf wildfly-$WILDFLY_VERSION.tar.gz \
    && mv $HOME/wildfly-$WILDFLY_VERSION $JBOSS_HOME \
    && rm wildfly-$WILDFLY_VERSION.tar.gz \
    && chown -R jboss:0 ${JBOSS_HOME} \
    && chmod -R g+rw ${JBOSS_HOME}

# Replace standalone.xml_26.1.3.Final (the main WildFly config file)
COPY ./wildfly_config/standalone.xml_${WILDFLY_VERSION} ${JBOSS_HOME}/standalone/configuration/standalone.xml  

ENV APP_VERSION=0.10.0

# copy only the artifacts we need from the first stage and discard the rest
COPY --from=maven_build /target/MaDiPHSWeatherService-$APP_VERSION.war /MaDiPHSWeatherService-$APP_VERSION.war
COPY --from=maven_build /geo-countries/data/countries.geojson /countries.geojson
RUN ln -s /MaDiPHSWeatherService-$APP_VERSION.war ${JBOSS_HOME}/standalone/deployments/MaDiPHSWeatherService.war
# This requires you to have cloned the formats repository from GitHub: https://github.com/H2020-IPM-Decisions/dss-metadata
RUN mkdir /Weather-Metadata
COPY  --from=maven_build /Weather-Metadata/ /Weather-Metadata/
RUN chown -R jboss:jboss /Weather-Metadata

# Ensure signals are forwarded to the JVM process correctly for graceful shutdown
ENV LAUNCH_JBOSS_IN_BACKGROUND=true

USER jboss

# Expose the ports we're interested in
EXPOSE 8080

# Set the default command to run on boot
# This will boot WildFly in the standalone mode and bind to all interfaces
# Run container like this: sudo docker run --publish 18081:8080 --detach -e TOKEN_USERNAME=[TOKEN_USERNAME] -e TAHMO_USERNAME=[TAHMO_USERNAME] -e TAHMO_PASSWORD=[TAHMO_PASSWORD] -e TOKEN_SECRET_KEY=[TOKEN_SECRET_KEY] --name madiphsweather madiphs/weather_api:[YOUR_VERSION]
CMD [ "/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0", "-Dorg.madiphs.weatherservice.DATASOURCE_LIST_FILE=/Weather-Metadata/weather_datasources.yaml", "-Dnet.ipmdecisions.weatherservice.COUNTRY_BOUNDARIES_FILE=/countries.geojson", "-Dnet.ipmdecisions.weatherservice.WEATHER_API_URL=${WEATHER_API_URL}", "-Dorg.madiphs.weatherservice.TOKEN_USERNAME=${TOKEN_USERNAME}", "-Dorg.madiphs.weatherservice.TAHMO_USERNAME=${TAHMO_USERNAME}", "-Dorg.madiphs.weatherservice.TAHMO_PASSWORD=${TAHMO_PASSWORD}", "-Dorg.madiphs.weatherservice.TOKEN_SECRET_KEY=${TOKEN_SECRET_KEY}"]
