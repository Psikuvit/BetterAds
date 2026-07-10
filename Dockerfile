# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies separately from source so a source-only change doesn't
# force a full re-download.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is used by the HEALTHCHECK below; eclipse-temurin's JRE image doesn't
# include it by default.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --home-dir /app appuser
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/logs && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fs http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# Runs BetterAdsApplication (web + the ad-processing RabbitMQ consumer — both
# live in the same component-scanned context). See docker-compose.yml for
# how to instead run WorkerApplication as a separate, web-server-less
# process if you want to scale ad processing independently of the API.
ENTRYPOINT ["java", "-jar", "app.jar"]
