FROM openjdk:8-alpine

# Install Node.js, npm, and required dependencies
RUN apk add --no-cache nodejs npm libc6-compat

# Create a non-root user
RUN addgroup -g 1001 appgroup && \
    adduser -D -u 1001 -G appgroup appuser

# Install Yarn globally with proper permissions
RUN npm install -g yarn --unsafe-perm

# Copy application files
COPY build /ocn-node
COPY src/main/resources/* /ocn-node/

# Set working directory
WORKDIR /ocn-node

# Set environment variables
ENV NETWORK=minikube
ENV OCN_NODE_URL=http://local.node.com

# Grant execute permissions to entrypoint
RUN chmod +x /ocn-node/entrypoint-register-node.sh

# Expose port
EXPOSE 9999

# Define entrypoint
ENTRYPOINT ["sh", "/ocn-node/entrypoint-register-node.sh"]

# Default CMD
CMD ["java", "-jar", "./libs/ocn-node-1.2.0-rc2.jar"]
