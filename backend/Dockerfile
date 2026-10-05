# Bygg-steg
# Java 27 pa Eclipse Temurin. Bygget gick 2026-09-22--10-05 pa Liberica eftersom Temurin
# saknade 27-avbildningar; eclipse-temurin:27-jdk/-jre/-jre-alpine finns nu (kontrollerat 2026-10-05).
# Maven kommer fortfarande fran wrappern i repot: maven:3.9-eclipse-temurin-27 finns inte an,
# och wrappern hamtar Maven sjalv med wget, curl ELLER bara java.
FROM eclipse-temurin:27-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn ./.mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Kör-steg
FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /app/target/minipristaget-backend-1.0.0.jar app.jar
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "app.jar"]
