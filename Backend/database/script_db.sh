#!/bin/bash
set -e

echo "[script_db.sh] - Inicio"

/usr/local/bin/docker-entrypoint.sh postgres &

until pg_isready -h localhost -p 5432; do
    echo "[script_db.sh] - Esperando a que PostgreSQL se inicie..."
    sleep 1
done

DB_EXISTS=$(psql -U $POSTGRES_USER -tAc "SELECT 1 FROM pg_database WHERE datname='$DATABASE_NAME'")

if [ "$DB_EXISTS" != "1" ]; then
    echo "[script_db.sh] - La base de datos '$DATABASE_NAME' no existe. Creándola..."
    psql -U $POSTGRES_USER -c "CREATE DATABASE $DATABASE_NAME"
    psql -U $POSTGRES_USER -d $DATABASE_NAME -f /DDL.sql
else
    echo "[script_db.sh] - La base de datos '$DATABASE_NAME' ya existe."
fi



wait