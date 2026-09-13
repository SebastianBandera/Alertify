#!/usr/bin/env sh
set -eu

exec java -XX:MaxRAMPercentage=80.0 --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow "$@" -jar /application/alertify-worker-standard.jar
