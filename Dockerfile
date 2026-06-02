# ---- Build stage ----
FROM gradle:9.5.1-jdk25 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
# cache dependency layer
RUN gradle dependencies --no-daemon 2>/dev/null || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
