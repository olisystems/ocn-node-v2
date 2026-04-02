#!/usr/bin/env bash

CUSTOM_TERMINAL_SCRIPT="$(dirname "$0")/../transit-local-services/scripts/customTerminal.sh"
if [ -f "$CUSTOM_TERMINAL_SCRIPT" ]; then
  source "$CUSTOM_TERMINAL_SCRIPT"
fi

if [ ! -f "$(dirname "$0")/local/port.md" ]; then
  echo "WARNING: local/port.md not found. Please create it with the service port number."
  exit 1
fi

PORT=$(cat local/port.md | head -n 1 | tr -d '[:space:]')
API_NAME="ocn-node:$PORT"
LANG="kotlin"   # only for color (optional)

if command -v custom_terminal_setup &> /dev/null; then
  custom_terminal_setup "$API_NAME" "$LANG"
fi

# Start the application with Gradle and Spring profiles
./gradlew bootRun --args='--spring.profiles.active=local,local-custom'
