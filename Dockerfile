# syntax=docker/dockerfile:1

# ---------- Build stage: compile Java + React into one jar ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy only the dependency manifests first so Docker can cache downloads.
COPY pom.xml .
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN mvn -q -B dependency:go-offline
COPY frontend ./frontend
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- Runtime stage: small JRE image, no build tools ----------
FROM eclipse-temurin:21-jre-alpine
# Run as an unprivileged user, never as root.
RUN addgroup -S miniredis && adduser -S -G miniredis -h /app miniredis \
    && mkdir -p /data && chown miniredis:miniredis /data
WORKDIR /app
COPY --from=build /build/target/mini-redis.jar mini-redis.jar
USER miniredis
VOLUME ["/data"]
EXPOSE 6379 8080
# Inside a container we must listen on all interfaces, so a password is required:
# set MINI_REDIS_PASSWORD (see .env.example / docker-compose.yml).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "mini-redis.jar", \
            "--bind", "0.0.0.0", "--dashboard-bind", "0.0.0.0", "--aof-file", "/data/mini-redis.aof"]
