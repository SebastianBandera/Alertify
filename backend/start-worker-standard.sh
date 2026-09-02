#!/usr/bin/env sh
set -eu

exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow "$@" -jar /application/alertify-worker-standard.jar
