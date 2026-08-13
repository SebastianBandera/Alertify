#!/usr/bin/env bash
set -Eeuo pipefail

: "${OIDC_REALM:?OIDC_REALM is required}"
: "${KC_BOOTSTRAP_ADMIN_USERNAME:?KC_BOOTSTRAP_ADMIN_USERNAME is required}"
: "${KC_BOOTSTRAP_ADMIN_PASSWORD:?KC_BOOTSTRAP_ADMIN_PASSWORD is required}"
: "${KEYCLOAK_ADMIN_USERNAME:?KEYCLOAK_ADMIN_USERNAME is required}"
: "${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD is required}"

if [[ "$KC_BOOTSTRAP_ADMIN_USERNAME" == "$KEYCLOAK_ADMIN_USERNAME" ]]; then
  echo "The bootstrap and permanent administrator usernames must be different." >&2
  exit 1
fi

if [[ ! "$OIDC_REALM" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "OIDC_REALM may contain only letters, numbers, hyphens, and underscores." >&2
  exit 1
fi

import_directory=/opt/keycloak/data/import
import_file="${import_directory}/${OIDC_REALM}-realm.json"

mkdir -p "$import_directory"
cp /opt/keycloak/config/realm-template.json "$import_file"

keycloak_pid=
kcadm_config=/tmp/kcadm.config
ready_marker=/tmp/permanent-admin-ready

stop_keycloak() {
  local exit_status=$?

  rm -f "$ready_marker" "$kcadm_config"

  if [[ -n "${keycloak_pid:-}" ]] && kill -0 "$keycloak_pid" 2>/dev/null; then
    kill -TERM "$keycloak_pid" 2>/dev/null || true
    wait "$keycloak_pid" 2>/dev/null || true
  fi

  exit "$exit_status"
}

trap stop_keycloak INT TERM

rm -f "$ready_marker" "$kcadm_config"
/opt/keycloak/bin/kc.sh "$@" &
keycloak_pid=$!

authenticate() {
  local username=$1
  local password=$2

  rm -f "$kcadm_config"
  /opt/keycloak/bin/kcadm.sh config credentials \
    --config "$kcadm_config" \
    --server http://127.0.0.1:8080 \
    --realm master \
    --user "$username" \
    --password "$password" >/dev/null 2>&1
}

authenticated_as=
for _ in {1..60}; do
  if ! kill -0 "$keycloak_pid" 2>/dev/null; then
    wait "$keycloak_pid"
    exit $?
  fi

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
  kill -TERM "$keycloak_pid" 2>/dev/null || true
  wait "$keycloak_pid" 2>/dev/null || true
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

if [[ -n "$bootstrap_admin_id" ]]; then
  /opt/keycloak/bin/kcadm.sh delete "users/$bootstrap_admin_id" \
    --config "$kcadm_config" \
    --realm master
fi

rm -f "$kcadm_config"
touch "$ready_marker"

if [[ "$authenticated_as" == bootstrap ]]; then
  echo "The permanent Keycloak administrator was created and the bootstrap account was removed."
else
  echo "The permanent Keycloak administrator already exists; no bootstrap initialization was required."
fi

set +e
wait "$keycloak_pid"
exit_status=$?
set -e

rm -f "$ready_marker"
exit "$exit_status"
