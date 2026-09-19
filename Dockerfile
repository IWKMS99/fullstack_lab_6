FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:24-jdk AS builder
WORKDIR /app
COPY gradlew gradle.properties build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src src
COPY config config
COPY --from=frontend /frontend/dist frontend/dist
RUN ./gradlew bootJar --no-daemon --max-workers=2

FROM eclipse-temurin:24-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=builder --chown=app:app /app/build/libs/*-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
