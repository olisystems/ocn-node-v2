FROM mcr.microsoft.com/openjdk/jdk:21-azurelinux

COPY build /ocn-node
WORKDIR /ocn-node
ENV JAVA_TOOL_OPTIONS "-Djava.rmi.server.hostname=localhost"
ENV OCN_NODE_JAVA_TOOL_OPTIONS ""
ENV SERVER_HOST "0.0.0.0"
ENV OCN_PLUGINS_LOADER_PATH "/ocn-node/plugins"
ENTRYPOINT ["java", "-Dloader.path=/ocn-node/plugins", "-jar", "./libs/node-ocn-v2.jar"]
