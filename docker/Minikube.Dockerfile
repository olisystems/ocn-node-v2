FROM openjdk:8-alpine

# Install Node.js and Yarn
RUN apk add --no-cache nodejs npm && \
    npm install -g yarn

# Copy application files
COPY build /ocn-node
COPY src/main/resources/* /ocn-node/

# Set working directory
WORKDIR /ocn-node

# Set environment variables
ENV NETWORK=minikube
ENV OCN_NODE_URL=http://local.node.com

# Ensure script permissions
RUN chmod +x /ocn-node/entrypoint-register-node.sh

# Expose the required port
EXPOSE 9999

# Define entrypoint
ENTRYPOINT ["sh", "/ocn-node/entrypoint-register-node.sh"]

# Default command
CMD ["java", "-jar", "./libs/ocn-node-1.2.0-rc2.jar"]
