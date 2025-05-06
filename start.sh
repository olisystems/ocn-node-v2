#!/bin/bash

# Start the application using the verified working command from project memory
JAVA_HOME="/Users/matheusrosendo/Library/Java/JavaVirtualMachines/azul-13.0.14/Contents/Home" \
./gradlew bootRun -Pprofile=local-minikube \
-Dorg.gradle.jvmargs='--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED'