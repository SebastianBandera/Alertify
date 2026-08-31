#!/usr/bin/env sh
set -eu

debug_enabled=${JAVA_DEBUG_ENABLED:-false}
debug_port=${JAVA_DEBUG_PORT:-5005}
debug_suspend=${JAVA_DEBUG_SUSPEND:-n}
application_context_path=${APP_CONTEXT_PATH:-/}

case "$application_context_path" in
  /)
    ;;
  /*)
    application_context_path=${application_context_path%/}
    set -- "-Dserver.servlet.context-path=$application_context_path" "$@"
    ;;
  *)
    echo "ERROR: APP_CONTEXT_PATH must be / or start with /." >&2
    exit 1
    ;;
esac

case "$debug_enabled" in
  true|false)
    ;;
  *)
    echo "ERROR: JAVA_DEBUG_ENABLED must be true or false." >&2
    exit 1
    ;;
esac

case "$debug_port" in
  ''|*[!0-9]*)
    echo "ERROR: JAVA_DEBUG_PORT must be a numeric port." >&2
    exit 1
    ;;
esac

case "$debug_suspend" in
  y|n)
    ;;
  *)
    echo "ERROR: JAVA_DEBUG_SUSPEND must be y or n." >&2
    exit 1
    ;;
esac

if [ "$debug_enabled" = "true" ]; then
  echo "Java remote debugging is enabled on port $debug_port (suspend=$debug_suspend)."
  set -- "-agentlib:jdwp=transport=dt_socket,server=y,suspend=$debug_suspend,address=*:$debug_port" "$@"
else
  echo "Java remote debugging is disabled."
fi

exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow "$@" -jar /application/alertify-backend.jar
