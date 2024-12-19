FROM openjdk:8-alpine

COPY build /ocn-node
COPY src/main/resources/* /ocn-node/
COPY gradle.properties /ocn-node/
WORKDIR /ocn-node

EXPOSE 9999

CMD ["java", "-jar", "./libs/ocn-node-1.2.0-rc2.jar"]
