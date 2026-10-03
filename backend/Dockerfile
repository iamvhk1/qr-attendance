# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Build the JAR (skipping tests for faster deployment)
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

# Run with strict memory limits optimized for Koyeb's 512MB RAM free tier
# -Xmx300m: Max heap size
# -Xss512k: Thread stack size
# -XX:MaxMetaspaceSize=128m: Limit class metadata size
ENTRYPOINT ["java", "-Xmx300m", "-Xss512k", "-XX:MaxMetaspaceSize=128m", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
