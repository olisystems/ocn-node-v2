FROM openjdk:8-alpine

# Copy build artifacts and resources
COPY build /ocn-node
COPY src/main/resources/* /ocn-node/

WORKDIR /ocn-node

# Set environment variables
ENV NETWORK=minikube
ENV OCN_NODE_URL=http://local.node.com

# Grant execute permissions for the script
RUN chmod +x /ocn-node/entrypoint-register-node.sh

# Expose the required port
EXPOSE 9999

# Execute the script at container startup (not at build time)
ENTRYPOINT ["sh", "/ocn-node/entrypoint-register-node.sh"]

# CMD ["java", "-jar", "./libs/ocn-node-1.2.0-rc2.jar"]