FROM docker.io/eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon

COPY src/ src/
RUN ./gradlew build -x test -x asciidoctor -x integrationTest --no-daemon

FROM docker.io/amazoncorretto:21.0.13-al2023-headless

WORKDIR /app

# aws CLI for OTC OBS (S3-compatible) plugin fetch at startup
RUN dnf install -y unzip \
  && ARCH=$(uname -m) \
  && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${ARCH}.zip" -o /tmp/awscliv2.zip \
  && unzip -q /tmp/awscliv2.zip -d /tmp \
  && /tmp/aws/install \
  && rm -rf /tmp/aws /tmp/awscliv2.zip \
  && dnf remove -y unzip \
  && dnf clean all

# archive name follows rootProject.name + project.version (see build.gradle.kts)
COPY --from=builder /app/build/libs/node-ocn-v2.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN mkdir -p /app/plugins \
  && chmod +x /app/docker-entrypoint.sh \
  && chown -R nobody:nobody /app/plugins /app/docker-entrypoint.sh

USER nobody

EXPOSE 8080

ENV OCN_PLUGINS_LOADER_PATH="/app/plugins"
ENTRYPOINT ["/app/docker-entrypoint.sh"]
