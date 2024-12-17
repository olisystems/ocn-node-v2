FROM openjdk:8-alpine

COPY build /ocn-node
COPY src/main/resources/* /ocn-node/
WORKDIR /ocn-node

RUN chmod +x entrypoint-register-node.sh
RUN ./entrypoint-register-node.sh

EXPOSE 8080

CMD ["java", "-jar", "./libs/ocn-node-1.2.0-rc2.jar"]
