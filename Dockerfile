FROM docker.io/eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon

COPY src/ src/
RUN ./gradlew build -x test -x asciidoctor -x integrationTest --no-daemon

FROM docker.io/eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/build/libs/node-ocn-v3.jar app.jar

USER nobody

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
