#!/usr/bin/env bash
set -Eeuo pipefail

: "${KC_BOOTSTRAP_ADMIN_USERNAME:?KC_BOOTSTRAP_ADMIN_USERNAME is required}"
: "${KC_BOOTSTRAP_ADMIN_PASSWORD:?KC_BOOTSTRAP_ADMIN_PASSWORD is required}"
: "${KEYCLOAK_ADMIN_USERNAME:?KEYCLOAK_ADMIN_USERNAME is required}"
: "${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD is required}"
: "${OIDC_REALM:?OIDC_REALM is required}"
: "${OIDC_FRONTEND_CLIENT_ID:?OIDC_FRONTEND_CLIENT_ID is required}"
: "${APP_PUBLIC_URL:?APP_PUBLIC_URL is required}"
: "${KC_HTTP_RELATIVE_PATH:?KC_HTTP_RELATIVE_PATH is required}"

if [[ "$KC_BOOTSTRAP_ADMIN_USERNAME" == "$KEYCLOAK_ADMIN_USERNAME" ]]; then
  echo "The bootstrap and permanent administrator usernames must be different." >&2
  exit 1
fi

kcadm_config=/tmp/permanent-admin-kcadm.config
ready_marker=/tmp/permanent-admin-ready

cleanup() {
  rm -f "$kcadm_config"
}

trap cleanup EXIT
rm -f "$ready_marker" "$kcadm_config"

authenticate() {
  local username=$1
  local password=$2

  rm -f "$kcadm_config"
  /opt/keycloak/bin/kcadm.sh config credentials \
    --config "$kcadm_config" \
    --server "http://127.0.0.1:8080${KC_HTTP_RELATIVE_PATH}" \
    --realm master \
    --user "$username" \
    --password "$password" >/dev/null 2>&1
}

authenticated_as=
for _ in {1..60}; do
  if authenticate "$KEYCLOAK_ADMIN_USERNAME" "$KEYCLOAK_ADMIN_PASSWORD"; then
    authenticated_as=permanent
    break
  fi

  if authenticate "$KC_BOOTSTRAP_ADMIN_USERNAME" "$KC_BOOTSTRAP_ADMIN_PASSWORD"; then
    authenticated_as=bootstrap
    break
  fi

  sleep 2
done

if [[ -z "$authenticated_as" ]]; then
  echo "Could not authenticate with either the permanent or bootstrap administrator." >&2
  exit 1
fi

if [[ "$authenticated_as" == bootstrap ]]; then
  permanent_admin_id=$(
    /opt/keycloak/bin/kcadm.sh get users \
      --config "$kcadm_config" \
      --realm master \
      --query "username=$KEYCLOAK_ADMIN_USERNAME" \
      --query exact=true \
      --fields id |
      sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' |
      head -n 1
  )

  if [[ -z "$permanent_admin_id" ]]; then
    /opt/keycloak/bin/kcadm.sh create users \
      --config "$kcadm_config" \
      --realm master \
      --set "username=$KEYCLOAK_ADMIN_USERNAME" \
      --set enabled=true
  fi

  /opt/keycloak/bin/kcadm.sh set-password \
    --config "$kcadm_config" \
    --realm master \
    --username "$KEYCLOAK_ADMIN_USERNAME" \
    --new-password "$KEYCLOAK_ADMIN_PASSWORD"

  /opt/keycloak/bin/kcadm.sh add-roles \
    --config "$kcadm_config" \
    --realm master \
    --uusername "$KEYCLOAK_ADMIN_USERNAME" \
    --rolename admin

  authenticate "$KEYCLOAK_ADMIN_USERNAME" "$KEYCLOAK_ADMIN_PASSWORD"
fi

app_public_url=${APP_PUBLIC_URL%/}
frontend_client_internal_id=$(
  /opt/keycloak/bin/kcadm.sh get clients \
    --config "$kcadm_config" \
    -r "$OIDC_REALM" \
    --query "clientId=$OIDC_FRONTEND_CLIENT_ID" \
    --fields id,clientId |
    sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' |
    head -n 1
)

if [[ -z "$frontend_client_internal_id" ]]; then
  echo "Could not find frontend client '$OIDC_FRONTEND_CLIENT_ID' in realm '$OIDC_REALM'." >&2
  exit 1
fi

/opt/keycloak/bin/kcadm.sh update "clients/$frontend_client_internal_id" \
  --config "$kcadm_config" \
  -r "$OIDC_REALM" \
  --set "redirectUris=[\"${app_public_url}/*\"]" \
  --set "webOrigins=[\"${app_public_url}\"]" \
  --set "attributes.\"pkce.code.challenge.method\"=\"S256\"" \
  --set "attributes.\"post.logout.redirect.uris\"=\"${app_public_url}/*\""

bootstrap_admin_id=$(
  /opt/keycloak/bin/kcadm.sh get users \
    --config "$kcadm_config" \
    --realm master \
    --query "username=$KC_BOOTSTRAP_ADMIN_USERNAME" \
    --query exact=true \
    --fields id |
    sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' |
    head -n 1
)

bootstrap_removed=false
if [[ -n "$bootstrap_admin_id" ]]; then
  /opt/keycloak/bin/kcadm.sh delete "users/$bootstrap_admin_id" \
    --config "$kcadm_config" \
    --realm master
  bootstrap_removed=true
fi

touch "$ready_marker"

if [[ "$authenticated_as" == bootstrap ]]; then
  echo "The permanent Keycloak administrator was configured and the bootstrap account was removed."
elif [[ "$bootstrap_removed" == true ]]; then
  echo "The permanent Keycloak administrator already exists; the remaining bootstrap account was removed."
else
  echo "The permanent Keycloak administrator already exists; no bootstrap changes were required."
fi

echo "Frontend client '$OIDC_FRONTEND_CLIENT_ID' uses '$app_public_url' for redirects and web origins."
