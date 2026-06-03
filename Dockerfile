# syntax=docker/dockerfile:1

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# BuildKit cache mount keeps the local Maven repo across builds for fast rebuilds.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# curl is only needed for the container HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 appuser

# The Spring Boot plugin produces a single bootable jar; the unpackaged original
# is left as *.jar.original, so this glob matches only the runnable artifact.
COPY --from=build /app/target/*.jar /app/app.jar

USER appuser
EXPOSE 8080

# ADMIN_PASSWORD is intentionally NOT baked in — the app refuses to start without it,
# so it must be supplied at runtime (e.g. `docker run -e ADMIN_PASSWORD=...`).
# /actuator/health is public; admin endpoints require basic auth.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# Exec form so extra Spring args pass through: `docker run <image> --server.port=9090`.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
