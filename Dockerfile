FROM mcr.microsoft.com/openjdk/jdk:21-azurelinux

COPY build /ocn-node
COPY src/main/resources/* /ocn-node/
WORKDIR /ocn-node

# Run the application (first .jar file found in the ./libs directory)
ENTRYPOINT ["sh", "-c", "java -jar $(ls ./libs/*.jar | head -n 1)"]