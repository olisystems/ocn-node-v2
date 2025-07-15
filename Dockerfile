FROM mcr.microsoft.com/openjdk/jdk:21-azurelinux

COPY build /ocn-node
WORKDIR /ocn-node
ENV JAVA_TOOL_OPTIONS "-Djava.rmi.server.hostname=localhost"
ENV OCN_NODE_JAVA_TOOL_OPTIONS ""
ENV SERVER_HOST "0.0.0.0"
# Run the application (first .jar file found in the ./libs directory)
ENTRYPOINT ["java", "-jar", "./libs/node-ocn-v3.jar"]
