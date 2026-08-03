#!/bin/bash
# Build and run MiniGoogle locally
./gradlew build -x test
java -jar build/libs/mini-google.jar
