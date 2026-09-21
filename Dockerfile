# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Cache dependency resolution as its own layer.
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/mini-redis.jar mini-redis.jar
VOLUME ["/data"]
EXPOSE 6379
ENTRYPOINT ["java", "-jar", "mini-redis.jar", "--aof-file", "/data/mini-redis.aof"]
