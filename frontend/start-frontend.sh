#!/usr/bin/env sh
set -eu

: "${KEYCLOAK_PUBLIC_URL:?KEYCLOAK_PUBLIC_URL is required}"
: "${OIDC_REALM:?OIDC_REALM is required}"
: "${OIDC_FRONTEND_CLIENT_ID:?OIDC_FRONTEND_CLIENT_ID is required}"

envsubst \
  '${KEYCLOAK_PUBLIC_URL} ${OIDC_REALM} ${OIDC_FRONTEND_CLIENT_ID}' \
  < /opt/alertify/runtime-config.template.json \
  > /usr/share/nginx/html/config/runtime-config.json

echo "Frontend runtime configuration is ready."

exec "$@"
