#!/usr/bin/env bash
set -Eeuo pipefail

: "${OIDC_REALM:?OIDC_REALM is required}"
: "${OIDC_BACKEND_AUDIENCE:?OIDC_BACKEND_AUDIENCE is required}"

if [[ ! "$OIDC_REALM" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "OIDC_REALM may contain only letters, numbers, hyphens, and underscores." >&2
  exit 1
fi

if [[ ! "$OIDC_BACKEND_AUDIENCE" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "OIDC_BACKEND_AUDIENCE may contain only letters, numbers, dots, hyphens, and underscores." >&2
  exit 1
fi

import_directory=/opt/keycloak/data/import
import_file="${import_directory}/${OIDC_REALM}-realm.json"
template_file=/opt/keycloak/config/realm-template.json
backend_audience_placeholder='${OIDC_BACKEND_AUDIENCE}'

mkdir -p "$import_directory"

realm_template=$(<"$template_file")
rendered_template="${realm_template//"$backend_audience_placeholder"/"$OIDC_BACKEND_AUDIENCE"}"
printf '%s\n' "$rendered_template" > "$import_file"

exec /opt/keycloak/bin/kc.sh "$@"
