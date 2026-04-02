#!/bin/bash

PORT=$(cat local/port.md | head -n 1 | tr -d '[:space:]')

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
"$(dirname "$0")/../transit-local-services/scripts/killPorts.sh" $PORT "$BASE_DIR"
