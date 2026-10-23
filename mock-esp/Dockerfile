# ---------------------------------------------------------------------------
# The MOCK eSign provider.
#
# The backend is built first because the mock reuses the shared PKI helpers
# (CMS and XML-DSig) from the backend artifact.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml ./backend/pom.xml
RUN mvn -B -f backend/pom.xml dependency:go-offline -DskipTests || true
COPY backend ./backend
RUN mvn -B -f backend/pom.xml install -DskipTests
COPY mock-esp/pom.xml ./mock-esp/pom.xml
RUN mvn -B -f mock-esp/pom.xml dependency:go-offline -DskipTests || true
COPY mock-esp ./mock-esp
RUN mvn -B -f mock-esp/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --create-home --uid 10002 dastakhat
COPY --from=build /build/mock-esp/target/dastakhat-mock-esp.jar /app/app.jar
USER dastakhat
EXPOSE 9100
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
