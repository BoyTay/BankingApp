FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/internal-wallet-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; IFS= read -r line <&3; [[ "$line" == *" 200 "* ]]'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
