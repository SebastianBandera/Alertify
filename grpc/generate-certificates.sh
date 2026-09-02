#!/usr/bin/env sh
set -eu

# ============================================================
# Runtime configuration
# ============================================================

# Supported modes:
#   rotate   Creates new leaf certificates and publishes them.
#   validate Verifies the existing installation without modifying it.
mode=${1:-rotate}

# Leaf certificates are intentionally long-lived for this private PKI.
validity_days=${GRPC_CERTIFICATE_VALIDITY_DAYS:-36500}

# DNS identity expected by the backend when connecting to a worker.
server_name=${WORKER_GRPC_TLS_SERVER_NAME:-worker}


# ============================================================
# Error handling and input validation
# ============================================================

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

case "$validity_days" in
  ''|*[!0-9]*)
    fail "GRPC_CERTIFICATE_VALIDITY_DAYS must be a positive integer."
    ;;
esac

[ "$validity_days" -gt 0 ] ||
  fail "GRPC_CERTIFICATE_VALIDITY_DAYS must be positive."

case "$server_name" in
  ''|*[!A-Za-z0-9.-]*)
    fail "WORKER_GRPC_TLS_SERVER_NAME must be a DNS name."
    ;;
esac


# ============================================================
# Certificate storage
# ============================================================

# These directories normally point to separate Docker volumes:
#
#   /ca       Private certificate authorities used only by this generator.
#   /backend  Credentials and trust material exposed to the backend.
#   /worker   Credentials and trust material exposed to the workers.
ca_directory=${GRPC_CA_DIRECTORY:-/ca}
backend_directory=${GRPC_BACKEND_DIRECTORY:-/backend}
worker_directory=${GRPC_WORKER_DIRECTORY:-/worker}

# The backend client CA signs certificates accepted by workers.
client_ca_key="$ca_directory/backend-client-ca.key"
client_ca_certificate="$ca_directory/backend-client-ca.crt"

# The worker server CA signs certificates accepted by the backend.
server_ca_key="$ca_directory/worker-server-ca.key"
server_ca_certificate="$ca_directory/worker-server-ca.crt"


# ============================================================
# Certificate validation helpers
# ============================================================

# Verifies that a certificate and private key exist and represent the same
# asymmetric key pair. Their public keys are compared through SHA-256 digests.
validate_pair() {
  certificate=$1
  private_key=$2
  description=$3

  [ -s "$certificate" ] ||
    fail "$description certificate is missing: $certificate"

  [ -s "$private_key" ] ||
    fail "$description private key is missing: $private_key"

  certificate_public_key=$(
    openssl x509 -in "$certificate" -pubkey -noout |
      openssl pkey -pubin -outform DER |
      openssl dgst -sha256
  )

  private_public_key=$(
    openssl pkey -in "$private_key" -pubout -outform DER |
      openssl dgst -sha256
  )

  [ "$certificate_public_key" = "$private_public_key" ] ||
    fail "$description certificate and private key do not match."
}

# Verifies the complete published mTLS installation:
#
#   - The backend certificate matches its private key.
#   - The worker certificate matches its private key.
#   - Workers trust the backend certificate for TLS client authentication.
#   - The backend trusts the worker certificate for TLS server authentication.
#   - The worker certificate contains the expected DNS identity.
validate_installation() {
  validate_pair \
    "$backend_directory/current/backend-client.crt" \
    "$backend_directory/current/backend-client.key" \
    "Backend client"

  validate_pair \
    "$worker_directory/current/worker-server.crt" \
    "$worker_directory/current/worker-server.key" \
    "Worker server"

  openssl verify \
    -purpose sslclient \
    -CAfile "$worker_directory/current/backend-client-ca.crt" \
    "$backend_directory/current/backend-client.crt" \
    >/dev/null

  openssl verify \
    -purpose sslserver \
    -verify_hostname "$server_name" \
    -CAfile "$backend_directory/current/worker-server-ca.crt" \
    "$worker_directory/current/worker-server.crt" \
    >/dev/null
}


# ============================================================
# Validation-only mode
# ============================================================

# This mode is used when the backend or any worker was skipped. It guarantees
# that certificates are not regenerated while some containers continue using
# the credentials already loaded in memory.
if [ "$mode" = "validate" ]; then
  validate_installation
  echo "Existing gRPC mTLS certificates are valid and were not modified."
  exit 0
fi

[ "$mode" = "rotate" ] ||
  fail "Unknown mode: $mode"


# ============================================================
# Secure directory preparation
# ============================================================

# New files start with owner-only permissions:
#
#   Files:       0600
#   Directories: 0700
#
# Runtime directories are relaxed afterwards so the non-root Java processes
# can traverse their role-specific Docker volumes.
umask 077

mkdir -p \
  "$ca_directory" \
  "$backend_directory/versions" \
  "$worker_directory/versions"

chmod 0755 \
  "$backend_directory" \
  "$backend_directory/versions" \
  "$worker_directory" \
  "$worker_directory/versions"


# ============================================================
# Backend client certificate authority
# ============================================================

# The CA is preserved between rotations. Keeping it stable allows old and new
# backend client certificates to remain trusted while containers are restarted.
if [ -e "$client_ca_key" ] || [ -e "$client_ca_certificate" ]; then
  validate_pair \
    "$client_ca_certificate" \
    "$client_ca_key" \
    "Backend client CA"
else
  # The CA lasts longer than the leaf certificates it signs.
  ca_validity_days=$((validity_days + 3650))

  openssl genpkey \
    -algorithm RSA \
    -pkeyopt rsa_keygen_bits:4096 \
    -out "$client_ca_key"

  openssl req \
    -x509 \
    -new \
    -sha256 \
    -key "$client_ca_key" \
    -days "$ca_validity_days" \
    -subj "/CN=Alertify Backend Client CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$client_ca_certificate"
fi


# ============================================================
# Worker server certificate authority
# ============================================================

# This separate CA is used only for worker server certificates. Separating both
# authorities prevents a worker certificate from being accepted as a backend
# client certificate.
if [ -e "$server_ca_key" ] || [ -e "$server_ca_certificate" ]; then
  validate_pair \
    "$server_ca_certificate" \
    "$server_ca_key" \
    "Worker server CA"
else
  # The CA lasts longer than the leaf certificates it signs.
  ca_validity_days=$((validity_days + 3650))

  openssl genpkey \
    -algorithm RSA \
    -pkeyopt rsa_keygen_bits:4096 \
    -out "$server_ca_key"

  openssl req \
    -x509 \
    -new \
    -sha256 \
    -key "$server_ca_key" \
    -days "$ca_validity_days" \
    -subj "/CN=Alertify Worker Server CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$server_ca_certificate"
fi


# ============================================================
# Version staging
# ============================================================

# Certificates are first written into a new version directory. The "current"
# symbolic links are changed only after every required file has been generated.
version="$(date +%s)-$$"

backend_version="$backend_directory/versions/$version"
worker_version="$worker_directory/versions/$version"

mkdir -p "$backend_version" "$worker_version"


# ============================================================
# Backend client credentials
# ============================================================

# This private key and certificate identify the backend to every worker.
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:3072 \
  -out "$backend_version/backend-client.key"

openssl req \
  -new \
  -sha256 \
  -key "$backend_version/backend-client.key" \
  -subj "/CN=alertify-backend" \
  -out "/tmp/backend-client.csr"

# The clientAuth extended key usage prevents this certificate from being used
# as a TLS server certificate.
printf '%s\n' \
  "basicConstraints=critical,CA:FALSE" \
  "keyUsage=critical,digitalSignature,keyEncipherment" \
  "extendedKeyUsage=clientAuth" \
  > /tmp/backend-client.ext

openssl x509 \
  -req \
  -sha256 \
  -in /tmp/backend-client.csr \
  -CA "$client_ca_certificate" \
  -CAkey "$client_ca_key" \
  -CAcreateserial \
  -days "$validity_days" \
  -extfile /tmp/backend-client.ext \
  -out "$backend_version/backend-client.crt"

# The backend receives only the public worker CA certificate. The corresponding
# CA private key remains isolated inside the generator's CA volume.
cp \
  "$server_ca_certificate" \
  "$backend_version/worker-server-ca.crt"


# ============================================================
# Worker server credentials
# ============================================================

# All worker replicas use a certificate for the shared worker DNS identity.
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:3072 \
  -out "$worker_version/worker-server.key"

openssl req \
  -new \
  -sha256 \
  -key "$worker_version/worker-server.key" \
  -subj "/CN=$server_name" \
  -out "/tmp/worker-server.csr"

# The serverAuth extended key usage restricts this certificate to TLS server
# authentication. The SAN contains the shared worker DNS name verified by the
# backend, including when it connects directly to a discovered worker IP.
printf '%s\n' \
  "basicConstraints=critical,CA:FALSE" \
  "keyUsage=critical,digitalSignature,keyEncipherment" \
  "extendedKeyUsage=serverAuth" \
  "subjectAltName=DNS:$server_name,DNS:localhost,IP:127.0.0.1" \
  > /tmp/worker-server.ext

openssl x509 \
  -req \
  -sha256 \
  -in /tmp/worker-server.csr \
  -CA "$server_ca_certificate" \
  -CAkey "$server_ca_key" \
  -CAcreateserial \
  -days "$validity_days" \
  -extfile /tmp/worker-server.ext \
  -out "$worker_version/worker-server.crt"

# Workers receive only the public backend client CA certificate. This allows
# them to authenticate backend clients without exposing the CA private key.
cp \
  "$client_ca_certificate" \
  "$worker_version/backend-client-ca.crt"


# ============================================================
# Publish the new certificate version
# ============================================================

# Runtime containers use role-isolated, read-only Docker volumes. Files must be
# readable by their non-root Java user, while directories only need traversal.
chmod 0444 \
  "$backend_version"/* \
  "$worker_version"/*

chmod 0555 \
  "$backend_version" \
  "$worker_version"

# Switch both roles to the newly generated version.
ln -sfn \
  "versions/$version" \
  "$backend_directory/current"

ln -sfn \
  "versions/$version" \
  "$worker_directory/current"


# ============================================================
# Post-publication validation and cleanup
# ============================================================

# Validate the published links before removing previous versions.
validate_installation

# Old leaf certificates are no longer needed after a successful publication.
# Published directories are read-only, so owner write permission is restored
# immediately before deletion. Certificate authority files remain untouched.
find "$backend_directory/versions" \
  -mindepth 1 \
  -maxdepth 1 \
  -type d \
  ! -name "$version" \
  -exec chmod -R u+w {} \; \
  -exec rm -rf {} +

find "$worker_directory/versions" \
  -mindepth 1 \
  -maxdepth 1 \
  -type d \
  ! -name "$version" \
  -exec chmod -R u+w {} \; \
  -exec rm -rf {} +

echo "gRPC mTLS certificates renewed successfully for DNS name $server_name."
