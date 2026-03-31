#!/usr/bin/env bash

source "$(dirname "$0")/../transit-local-services/scripts/customTerminal.sh"

PORT=$(cat local/port.md | head -n 1 | tr -d '[:space:]')
API_NAME="ocn-node:$PORT"
LANG="kotlin"   # only for color (optional)

custom_terminal_setup "$API_NAME" "$LANG"

# Start the application with Gradle and Spring profiles
./gradlew bootRun --args='--spring.profiles.active=local,local-custom'
