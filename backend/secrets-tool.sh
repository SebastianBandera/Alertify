#!/usr/bin/env sh
set -eu

# Internal-only secrets export/import tool. There is no HTTP endpoint for
# this: it must be run with `docker exec -it <container> /application/secrets-tool.sh ...`.
# It reuses the backend's own fat jar but loads a separate main class through
# Spring Boot's PropertiesLauncher, so it boots its own minimal, non-web
# Spring context instead of the running application.
exec java -Dloader.main=app.alertify.secretsexport.SecretExportImportCli \
    -cp /application/alertify-backend.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher "$@"
