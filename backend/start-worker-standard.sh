#!/usr/bin/env sh
set -eu

exec java "$@" -jar /application/alertify-worker-standard.jar
