FROM openjdk:8-alpine

COPY build /ocn-node
COPY src/main/resources/* /ocn-node/
WORKDIR /ocn-node

CMD ["java", "-jar", "./libs/ocn-node-1.1.2.jar"]
