#!/usr/bin/env sh
set -eu

exec java --sun-misc-unsafe-memory-access=allow "$@" -jar /application/alertify-worker-standard.jar
