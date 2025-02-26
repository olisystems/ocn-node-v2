FROM azul/zulu-openjdk:13

COPY build /ocn-node
COPY src/main/resources/* /ocn-node/
WORKDIR /ocn-node

# Run the application (first .jar file found in the ./libs directory)
ENTRYPOINT ["sh", "-c", "sleep 900 && java -jar $(ls ./libs/*.jar | head -n 1)"]