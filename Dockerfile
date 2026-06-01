# ---- Build stage ----
FROM gradle:8.8-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
# cache dependency layer
RUN gradle dependencies --no-daemon 2>/dev/null || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
