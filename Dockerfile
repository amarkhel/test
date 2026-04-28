# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-17 AS builder
WORKDIR /opt/app

# Copy build metadata first for better layer caching.
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:17-jre-jammy
WORKDIR /opt/app

LABEL org.opencontainers.image.title="weather-server" \
      org.opencontainers.image.description="Scala http4s weather server" \
      org.opencontainers.image.vendor="weather-server"

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --create-home --home-dir /home/app --shell /usr/sbin/nologin app

COPY --from=builder --chown=app:app /opt/app/target/classes ./classes
COPY --from=builder --chown=app:app /opt/app/target/dependency ./dependency

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

USER 10001:10001

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD code="$(curl --silent --output /dev/null --write-out "%{http_code}" http://127.0.0.1:8080/weather)" && [ "$code" = "400" ]

ENTRYPOINT ["java", "-cp", "/opt/app/classes:/opt/app/dependency/*", "weather.Main"]

