#!/bin/bash
# Rename to start.sh and update the line above to your local Java path
JAVA_HOME="~/Library/Java/JavaVirtualMachines/azul-13.0.14/Contents/Home"
JAVA_TOOL_OPTIONS="--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
./gradlew bootRun -Pprofile=local-minikube