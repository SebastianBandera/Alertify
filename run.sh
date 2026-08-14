#!/usr/bin/env sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: Node.js 18 o posterior debe estar instalado y disponible en PATH." >&2
  exit 1
fi

exec node "$script_directory/run.js" "$@"
