# ---------------------------------------------------------------------------
# The Dastakhat backend.
#
# The build stage compiles the jar, the runtime stage only contains the JRE.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml ./backend/pom.xml
RUN mvn -B -f backend/pom.xml dependency:go-offline -DskipTests || true
COPY backend ./backend
RUN mvn -B -f backend/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --create-home --uid 10001 dastakhat
COPY --from=build /build/backend/target/dastakhat-backend-1.0.0.jar /app/app.jar
RUN mkdir -p /data/storage && chown -R dastakhat:dastakhat /data /app
USER dastakhat
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
