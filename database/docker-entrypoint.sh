#!/usr/bin/env bash
set -Eeuo pipefail

# Match the official image behavior when PostgreSQL options are supplied directly.
if [[ "${1:-}" == -* ]]; then
  set -- postgres "$@"
fi

# Delegate non-PostgreSQL commands without starting migrations.
if [[ "${1:-}" != "postgres" ]]; then
  exec /usr/local/bin/docker-entrypoint.sh "$@"
fi

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${DATABASE_OWNER_USER:?DATABASE_OWNER_USER is required}"
: "${DATABASE_OWNER_PASSWORD:?DATABASE_OWNER_PASSWORD is required}"

DATABASE_NAME="${DATABASE_NAME:-$POSTGRES_DB}"

for identifier_name in POSTGRES_USER DATABASE_NAME DATABASE_OWNER_USER; do
  identifier_value="${!identifier_name}"
  if [[ ! "$identifier_value" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "[entrypoint] $identifier_name must be a valid unquoted PostgreSQL identifier." >&2
    exit 1
  fi
done

if [[ "$DATABASE_OWNER_USER" == "$POSTGRES_USER" ]]; then
  echo "[entrypoint] DATABASE_OWNER_USER must be different from the bootstrap POSTGRES_USER." >&2
  exit 1
fi

POSTGRES_PID=''
MIGRATIONS_READY_FILE='/tmp/database-migrations-complete'

# A stale marker can survive a container restart in its writable layer.
rm -f "$MIGRATIONS_READY_FILE"

stop_postgres() {
  if [[ -n "$POSTGRES_PID" ]] && kill -0 "$POSTGRES_PID" 2>/dev/null; then
    kill -TERM "$POSTGRES_PID"
    wait "$POSTGRES_PID" || true
  fi
}

on_exit() {
  exit_code=$?
  if (( exit_code != 0 )); then
    stop_postgres
  fi
}

trap stop_postgres TERM INT
trap on_exit EXIT

# Start the official entrypoint in the background while setup and migrations run.
/usr/local/bin/docker-entrypoint.sh "$@" &
POSTGRES_PID=$!

echo "[entrypoint] Waiting for PostgreSQL..."
# During first initialization, the official entrypoint starts a temporary
# socket-only server. Wait until the entrypoint itself has exec'd the final
# PostgreSQL process so migrations cannot race with that temporary server.
until [[ -r "/proc/$POSTGRES_PID/comm" ]] \
  && [[ "$(< "/proc/$POSTGRES_PID/comm")" == "postgres" ]] \
  && pg_isready --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" >/dev/null 2>&1; do
  if ! kill -0 "$POSTGRES_PID" 2>/dev/null; then
    echo "[entrypoint] PostgreSQL stopped before becoming ready." >&2
    wait "$POSTGRES_PID"
    exit $?
  fi
  sleep 1
done

echo "[entrypoint] PostgreSQL is ready. Preparing the database owner..."

psql_admin() {
  # The official image permits local socket access for the bootstrap user.
  psql \
    --username="$POSTGRES_USER" \
    --dbname=postgres \
    --no-psqlrc \
    --set=ON_ERROR_STOP=1 \
    "$@"
}

psql_admin \
  --set=database_owner_user="$DATABASE_OWNER_USER" <<'SQL'
\getenv database_owner_password DATABASE_OWNER_PASSWORD

SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',
    :'database_owner_user',
    :'database_owner_password'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'database_owner_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',
    :'database_owner_user',
    :'database_owner_password'
)
\gexec
SQL

# Create the application database if necessary and assign it to the owner role.
psql_admin \
  --set=database_name="$DATABASE_NAME" \
  --set=database_owner_user="$DATABASE_OWNER_USER" <<'SQL'
SELECT format(
    'CREATE DATABASE %I OWNER %I',
    :'database_name',
    :'database_owner_user'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = :'database_name'
)
\gexec

SELECT format(
    'ALTER DATABASE %I OWNER TO %I',
    :'database_name',
    :'database_owner_user'
)
\gexec
SQL

echo "[entrypoint] Running database migrations as $DATABASE_OWNER_USER..."

DB_NAME="$DATABASE_NAME" \
DB_USER="$DATABASE_OWNER_USER" \
DB_PASSWORD="$DATABASE_OWNER_PASSWORD" \
DB_HOST=127.0.0.1 \
DB_PORT=5432 \
MIGRATIONS_DIR=/migrations \
  /migration-script.sh

touch "$MIGRATIONS_READY_FILE"
echo "[entrypoint] Database migrations completed."

# Keep the wrapper alive while PostgreSQL remains the service process.
set +e
wait "$POSTGRES_PID"
exit_code=$?
set -e

trap - EXIT TERM INT
exit "$exit_code"
