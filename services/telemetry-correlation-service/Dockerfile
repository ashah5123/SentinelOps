# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Resolve dependencies first so source-only changes don't invalidate this layer.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B package -DskipTests -Dspotless.check.skip=true

# ---- Run stage ----
FROM eclipse-temurin:21-jre-jammy AS run

RUN groupadd --system sentinelops \
    && useradd --system --gid sentinelops --home-dir /app --shell /usr/sbin/nologin sentinelops

WORKDIR /app
COPY --from=build /workspace/target/telemetry-correlation-service.jar app.jar
RUN chown -R sentinelops:sentinelops /app

USER sentinelops
EXPOSE 8082

# Uses bash's built-in /dev/tcp pseudo-device (already present in this base image) rather
# than installing curl/wget, per the "no unnecessary packages" containerization requirement.
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=45s \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8082 \
        && printf "GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3 \
        && grep -q "\"status\":\"UP\"" <&3' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
