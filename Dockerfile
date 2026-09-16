# Two-stage image for hosting the portal (used by Render).
# Stage 1 builds the runnable jar, stage 2 keeps only a JRE and that jar, so the
# image that runs in production stays small.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the Gradle wrapper first so its download is cached between builds.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN chmod +x ./gradlew && ./gradlew --no-daemon --version

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user: nothing in the app needs root.
RUN useradd --system --create-home --uid 1001 portal
USER portal

COPY --from=build --chown=portal:portal /app/build/libs/*.jar app.jar

# Render sets PORT; application.properties already reads it, defaulting to 8080.
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
