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

read_environment_value() {
  environment_file=$1
  variable_name=$2
  value=$(sed -n "s/^[[:space:]]*${variable_name}[[:space:]]*=[[:space:]]*//p" "$environment_file" |
    tail -n 1 | tr -d '\r')
  [ -n "$value" ] || return 1

  case "$value" in
    \"*\") value=${value#\"}; value=${value%\"} ;;
    \'*\') value=${value#\'}; value=${value%\'} ;;
  esac
  printf '%s\n' "$value"
}

template_file="$script_directory/.env.template"
environment_file="$script_directory/.env"
if [ ! -f "$template_file" ]; then
  echo "ERROR: .env.template was not found." >&2
  exit 1
fi

if [ -f "$environment_file" ]; then
  public_port=$(read_environment_value "$environment_file" PUBLIC_PORT ||
    read_environment_value "$template_file" PUBLIC_PORT)
  compose_project_name=$(read_environment_value "$environment_file" COMPOSE_PROJECT_NAME ||
    read_environment_value "$template_file" COMPOSE_PROJECT_NAME)
else
  public_port=$(read_environment_value "$template_file" PUBLIC_PORT)
  compose_project_name=$(read_environment_value "$template_file" COMPOSE_PROJECT_NAME)
fi

case "$public_port" in
  ''|*[!0-9]*)
    echo "ERROR: PUBLIC_PORT must be an integer between 1 and 65535; received: $public_port." >&2
    exit 1
    ;;
esac
if [ "$public_port" -lt 1 ] || [ "$public_port" -gt 65535 ]; then
  echo "ERROR: PUBLIC_PORT must be an integer between 1 and 65535; received: $public_port." >&2
  exit 1
fi

publisher_container="${compose_project_name}-publisher"
published_containers=$(docker ps --filter "publish=$public_port" --format '{{.Names}}')
unexpected_containers=$(printf '%s\n' "$published_containers" |
  grep -v '^$' | grep -vx "$publisher_container" || :)
if [ -n "$unexpected_containers" ]; then
  echo "ERROR: PUBLIC_PORT $public_port is already published by: $unexpected_containers." >&2
  exit 1
fi

port_is_listening() {
  checked_port=$1
  if command -v ss >/dev/null 2>&1; then
    [ -n "$(ss -H -ltn "sport = :$checked_port" 2>/dev/null)" ]
    return
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$checked_port" -sTCP:LISTEN >/dev/null 2>&1
    return
  fi
  if [ -r /proc/net/tcp ]; then
    port_hex=$(printf '%04X' "$checked_port")
    tcp_files=/proc/net/tcp
    if [ -r /proc/net/tcp6 ]; then
      tcp_files="$tcp_files /proc/net/tcp6"
    fi
    awk -v suffix=":$port_hex" \
      '$4 == "0A" && substr($2, length($2) - 4) == suffix { found = 1 } END { exit(found ? 0 : 1) }' \
      $tcp_files 2>/dev/null
    return
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -E "LISTEN[^0-9]*$|LISTENING[^0-9]*$" |
      grep -E "[.:]$checked_port[[:space:]]" >/dev/null 2>&1
    return
  fi
  return 2
}

if printf '%s\n' "$published_containers" | grep -qx "$publisher_container"; then
  echo "PUBLIC_PORT $public_port is already owned by $publisher_container; continuing."
elif port_is_listening "$public_port"; then
  echo "ERROR: PUBLIC_PORT $public_port is already in use." >&2
  exit 1
else
  port_check_status=$?
  if [ "$port_check_status" -eq 2 ]; then
    echo "ERROR: No supported tool is available to verify PUBLIC_PORT $public_port." >&2
    exit 1
  fi
  echo "PUBLIC_PORT $public_port is available."
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
