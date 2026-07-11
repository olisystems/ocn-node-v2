FROM docker.io/eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon

COPY src/ src/
RUN ./gradlew build -x test -x asciidoctor -x integrationTest --no-daemon

FROM docker.io/eclipse-temurin:21-jre

WORKDIR /app

# archive name follows rootProject.name + project.version (see build.gradle.kts)
COPY --from=builder /app/build/libs/node-ocn-v2.jar app.jar

USER nobody

EXPOSE 8080

ENV OCN_PLUGINS_LOADER_PATH="/app/plugins"
ENTRYPOINT ["java", "-Dloader.path=/app/plugins", "-jar", "app.jar"]
