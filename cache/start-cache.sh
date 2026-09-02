#!/bin/sh
set -eu

: "${REDIS_PASSWORD:?REDIS_PASSWORD is required}"
: "${REDIS_MAXMEMORY:?REDIS_MAXMEMORY is required}"

exec redis-server \
  --bind 0.0.0.0 \
  --protected-mode yes \
  --requirepass "$REDIS_PASSWORD" \
  --save "" \
  --appendonly no \
  --maxmemory "$REDIS_MAXMEMORY" \
  --maxmemory-policy allkeys-lru \
  --databases 1
