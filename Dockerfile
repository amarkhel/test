# syntax=docker/dockerfile:1

FROM sbtscala/scala-sbt:eclipse-temurin-17.0.15_6_1.12.10_3.8.3 AS builder
WORKDIR /opt/app

# Copy build definition first for better layer caching.
COPY build.sbt ./
COPY project ./project
RUN sbt -batch update

COPY src ./src
RUN sbt -batch clean assembly

FROM eclipse-temurin:17.0.14_7-jre-jammy
WORKDIR /opt/app

LABEL org.opencontainers.image.title="weather-server" \
	  org.opencontainers.image.description="Scala http4s weather server" \
	  org.opencontainers.image.vendor="weather-server" \
	  org.opencontainers.image.licenses="UNLICENSED"

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --create-home --home-dir /home/app --shell /usr/sbin/nologin app

COPY --from=builder --chown=app:app /opt/app/target/scala-2.13/weather-server.jar ./weather-server.jar

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

USER 10001:10001

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD code="$(curl --silent --output /dev/null --write-out "%{http_code}" http://127.0.0.1:8080/weather)" && [ "$code" = "400" ]
ENTRYPOINT ["java", "-jar", "/opt/app/weather-server.jar"]
