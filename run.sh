#!/usr/bin/env sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
runner_image=${RUNNER_IMAGE:-monitoring-bootstrap-runner:local}

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: Docker must be installed and available in PATH." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running or the current user cannot access it." >&2
  exit 1
fi

socket_source=/var/run/docker.sock
case "${DOCKER_HOST:-}" in
  unix://*)
    socket_source=${DOCKER_HOST#unix://}
    ;;
  "")
    ;;
  *)
    echo "ERROR: This launcher supports a local Unix Docker socket only." >&2
    echo "Current DOCKER_HOST: $DOCKER_HOST" >&2
    exit 1
    ;;
esac

if [ ! -e "$socket_source" ]; then
  echo "ERROR: Docker socket not found at $socket_source." >&2
  exit 1
fi

host_uid=$(id -u)
host_gid=$(id -g)
if socket_gid=$(stat -c '%g' "$socket_source" 2>/dev/null); then
  :
else
  socket_gid=$(stat -f '%g' "$socket_source")
fi

echo "Preparing the Node.js runner image..."
docker build \
  --file "$script_directory/runner/Dockerfile" \
  --tag "$runner_image" \
  "$script_directory/runner"

exec docker run --rm \
  --env DOCKER_HOST=unix:///var/run/docker.sock \
  --env HOME=/tmp \
  --user "$host_uid:$host_gid" \
  --group-add "$socket_gid" \
  --mount "type=bind,source=$script_directory,target=/workspace" \
  --mount "type=bind,source=$socket_source,target=/var/run/docker.sock" \
  --workdir /workspace \
  "$runner_image" "$@"
