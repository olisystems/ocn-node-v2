#!/bin/bash

# Verify NETWORK and OCN_NODE_URL environment variables
if [ -z "$NETWORK" ]; then
  echo "Error: NETWORK environment variable is not set or empty."
  exit 1
fi

if [ -z "$OCN_NODE_URL" ]; then
  echo "Error: OCN_NODE_URL environment variable is not set or empty."
  exit 1
fi

# Remove ocn-registry globally
yarn global remove ocn-registry
# Install ocn-registry using yarn
yarn global add ocn-registry

## Check is  node is already registered for the private key provided
OUTPUT=$(ocn-registry is-node-registered -s 9df16a85d24a0dab6fb2bc5c57e1068ed47d56d7518e9b0eaf1712cae718ded6  -n $NETWORK)
RESPONSE=$(echo $OUTPUT | awk '{print $NF}')

if [ "$RESPONSE" == "false" ]; then
  # register node
  ocn-registry set-node $OCN_NODE_URL -s 9df16a85d24a0dab6fb2bc5c57e1068ed47d56d7518e9b0eaf1712cae718ded6 -n $NETWORK
else
  echo "This node was already setup in $NETWORK | current OcnRegistry address: "
  ocn-registry get-registry-contract-address  -n $NETWORK
fi