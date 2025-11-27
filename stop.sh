#!/bin/bash

PORT=9999

echo "Stopping process on port $PORT..."

# Find the PID of the process using the port
PID=$(lsof -ti:$PORT)

if [ -z "$PID" ]; then
    echo "No process found running on port $PORT"
    exit 0
fi

echo "Found process $PID on port $PORT"
kill -9 $PID

if [ $? -eq 0 ]; then
    echo "Successfully killed process $PID"
else
    echo "Failed to kill process $PID"
    exit 1
fi
