#!/usr/bin/env sh
set -eu

: "${KEYCLOAK_PUBLIC_URL:?KEYCLOAK_PUBLIC_URL is required}"
: "${OIDC_REALM:?OIDC_REALM is required}"
: "${OIDC_FRONTEND_CLIENT_ID:?OIDC_FRONTEND_CLIENT_ID is required}"
: "${OIDC_BACKEND_AUDIENCE:?OIDC_BACKEND_AUDIENCE is required}"
: "${BACKEND_PUBLIC_URL:?BACKEND_PUBLIC_URL is required}"

envsubst \
  '${KEYCLOAK_PUBLIC_URL} ${OIDC_REALM} ${OIDC_FRONTEND_CLIENT_ID} ${OIDC_BACKEND_AUDIENCE} ${BACKEND_PUBLIC_URL}' \
  < /opt/alertify/runtime-config.template.json \
  > /usr/share/nginx/html/config/runtime-config.json

echo "Frontend runtime configuration is ready."

exec "$@"
