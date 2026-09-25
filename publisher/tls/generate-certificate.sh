#!/usr/bin/env sh
set -eu

mode=${1:-rotate}
server_name=${PUBLISHER_TLS_SERVER_NAME:-alertify}
certificate_validity_days=${PUBLISHER_TLS_CERTIFICATE_VALIDITY_DAYS:-397}
ca_validity_days=${PUBLISHER_TLS_CA_VALIDITY_DAYS:-3650}
ca_directory=${PUBLISHER_TLS_CA_DIRECTORY:-/ca}
publisher_directory=${PUBLISHER_TLS_DIRECTORY:-/publisher}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

positive_integer() {
  value=$1
  name=$2
  case "$value" in
    ''|*[!0-9]*) fail "$name must be a positive integer." ;;
  esac
  [ "$value" -gt 0 ] || fail "$name must be positive."
}

positive_integer "$certificate_validity_days" PUBLISHER_TLS_CERTIFICATE_VALIDITY_DAYS
positive_integer "$ca_validity_days" PUBLISHER_TLS_CA_VALIDITY_DAYS

case "$server_name" in
  ''|*[!A-Za-z0-9.-]*) fail "PUBLISHER_TLS_SERVER_NAME must be a DNS name." ;;
esac

ca_key="$ca_directory/alertify-local-ca.key"
ca_certificate="$ca_directory/alertify-local-ca.crt"

validate_pair() {
  certificate=$1
  private_key=$2
  description=$3

  [ -s "$certificate" ] || fail "$description certificate is missing: $certificate"
  [ -s "$private_key" ] || fail "$description private key is missing: $private_key"

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

validate_ca() {
  validate_pair "$ca_certificate" "$ca_key" "Publisher local CA"
  openssl x509 -in "$ca_certificate" -checkend 86400 -noout >/dev/null ||
    fail "Publisher local CA is expired or expires within 24 hours."
  openssl x509 -in "$ca_certificate" -purpose -noout |
    grep -q '^SSL server CA : Yes$' ||
    fail "Publisher local CA cannot sign server certificates."
}

validate_installation() {
  server_certificate="$publisher_directory/current/publisher-server.crt"
  server_key="$publisher_directory/current/publisher-server.key"
  validate_ca
  validate_pair "$server_certificate" "$server_key" "Publisher server"
  openssl verify \
    -purpose sslserver \
    -verify_hostname "$server_name" \
    -CAfile "$ca_certificate" \
    "$server_certificate" \
    >/dev/null
}

case "$mode" in
  export-ca)
    [ -s "$ca_certificate" ] || fail "Publisher local CA certificate is missing."
    exec cat "$ca_certificate"
    ;;
  validate)
    validate_installation
    echo "Existing publisher TLS certificate is valid for DNS name $server_name."
    exit 0
    ;;
  rotate)
    ;;
  *)
    fail "Unknown mode: $mode"
    ;;
esac

umask 077
mkdir -p "$ca_directory" "$publisher_directory/versions"
chmod 0755 "$publisher_directory" "$publisher_directory/versions"

if [ -e "$ca_key" ] || [ -e "$ca_certificate" ]; then
  validate_ca
else
  openssl genpkey \
    -algorithm RSA \
    -pkeyopt rsa_keygen_bits:4096 \
    -out "$ca_key"

  openssl req \
    -x509 \
    -new \
    -sha256 \
    -key "$ca_key" \
    -days "$ca_validity_days" \
    -subj "/CN=Alertify Local Development CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" \
    -out "$ca_certificate"
fi

version="$(date +%s)-$$"
publisher_version="$publisher_directory/versions/$version"
mkdir -p "$publisher_version"

openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:3072 \
  -out "$publisher_version/publisher-server.key"

openssl req \
  -new \
  -sha256 \
  -key "$publisher_version/publisher-server.key" \
  -subj "/CN=$server_name" \
  -out /tmp/publisher-server.csr

printf '%s\n' \
  "basicConstraints=critical,CA:FALSE" \
  "keyUsage=critical,digitalSignature,keyEncipherment" \
  "extendedKeyUsage=serverAuth" \
  "subjectAltName=DNS:$server_name" \
  "authorityKeyIdentifier=keyid,issuer" \
  > /tmp/publisher-server.ext

openssl x509 \
  -req \
  -sha256 \
  -in /tmp/publisher-server.csr \
  -CA "$ca_certificate" \
  -CAkey "$ca_key" \
  -set_serial "0x$(openssl rand -hex 16)" \
  -days "$certificate_validity_days" \
  -extfile /tmp/publisher-server.ext \
  -out "$publisher_version/publisher-server.crt"

chmod 0400 "$publisher_version/publisher-server.key"
chmod 0444 "$publisher_version/publisher-server.crt"
chmod 0555 "$publisher_version"
ln -sfn "versions/$version" "$publisher_directory/current"

validate_installation

find "$publisher_directory/versions" \
  -mindepth 1 \
  -maxdepth 1 \
  -type d \
  ! -name "$version" \
  -exec chmod -R u+w {} \; \
  -exec rm -rf {} +

echo "Publisher TLS certificate renewed successfully for DNS name $server_name."
