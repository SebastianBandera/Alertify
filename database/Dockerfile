ARG POSTGRES_IMAGE

FROM ${POSTGRES_IMAGE}

# Bash arrays and GNU find are required by the migration runner. Support both
# Alpine and Debian variants of the official PostgreSQL image.
RUN if command -v apk >/dev/null 2>&1; then \
      apk add --no-cache bash coreutils findutils; \
    elif command -v apt-get >/dev/null 2>&1; then \
      apt-get update \
      && apt-get install --yes --no-install-recommends bash coreutils findutils \
      && rm -rf /var/lib/apt/lists/*; \
    else \
      echo "Unsupported PostgreSQL base image: no apk or apt-get package manager." >&2; \
      exit 1; \
    fi

# Copy the migration runner, custom wrapper, and versioned SQL files.
COPY migration-script.sh /migration-script.sh
COPY docker-entrypoint.sh /custom-docker-entrypoint.sh
COPY migrations /migrations

# Make both scripts executable.
RUN chmod 0755 /migration-script.sh /custom-docker-entrypoint.sh

# Report healthy only after PostgreSQL is accepting connections and the
# migration runner has completed successfully for this container startup.
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=20 \
    CMD test -f /tmp/database-migrations-complete \
        && pg_isready --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
        || exit 1

# The wrapper delegates every argument to the official PostgreSQL entrypoint.
ENTRYPOINT ["/custom-docker-entrypoint.sh"]

# This remains the default command and can be extended or replaced by Compose.
CMD ["postgres"]
