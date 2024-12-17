FROM openjdk:8-alpine

COPY build /ocn-node
COPY src/main/resources/* /ocn-node/
WORKDIR /ocn-node

# Set environment variables
ENV NETWORK=minikube
ENV OCN_NODE_URL=http://local.node.com

RUN chmod +x ./entrypoint-register-node.sh
RUN ./entrypoint-register-node.sh

EXPOSE 9999

CMD ["java", "-jar", "./libs/ocn-node-1.2.0-rc2.jar"]