#!/usr/bin/env bash
set -Eeuo pipefail

: "${OIDC_REALM:?OIDC_REALM is required}"

if [[ ! "$OIDC_REALM" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "OIDC_REALM may contain only letters, numbers, hyphens, and underscores." >&2
  exit 1
fi

import_directory=/opt/keycloak/data/import
import_file="${import_directory}/${OIDC_REALM}-realm.json"

mkdir -p "$import_directory"
cp /opt/keycloak/config/realm-template.json "$import_file"

exec /opt/keycloak/bin/kc.sh "$@"
