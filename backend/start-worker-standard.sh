#!/usr/bin/env sh
set -eu

java -Dloader.main=app.alertify.worker.standard.NfsMountHelperMain \
    -cp /application/alertify-worker-standard.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher &
helper_pid=$!

attempt=0
while [ ! -S /run/alertify-nfs-helper/helper.sock ]; do
    if ! kill -0 "$helper_pid" 2>/dev/null; then
        echo "NFS mount helper exited before creating its Unix socket" >&2
        exit 1
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 100 ]; then
        echo "Timed out waiting for the NFS mount helper Unix socket" >&2
        exit 1
    fi
    sleep 0.1
done

exec su-exec application java -XX:MaxRAMPercentage=80.0 --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow "$@" -jar /application/alertify-worker-standard.jar
