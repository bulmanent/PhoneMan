#!/usr/bin/env sh

# Simplified Gradle wrapper script
DIR="$(cd "$(dirname "$0")" && pwd)"
java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
