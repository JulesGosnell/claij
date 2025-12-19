#!/bin/bash
# Generate a self-signed SSL certificate for CLAIJ development
#
# Usage: bin/gen-ssl-cert.sh [hostname] [keystore-path]
#
# Creates a Java keystore with a self-signed certificate valid for:
# - localhost
# - 127.0.0.1
# - The machine's hostname
# - Any custom hostname provided
#
# The certificate is valid for 365 days.

set -e

HOSTNAME="${1:-$(hostname)}"
KEYSTORE="${2:-claij-dev.jks}"
PASSWORD="changeit"
DAYS=365

echo "Generating self-signed SSL certificate..."
echo "  Hostname: $HOSTNAME"
echo "  Keystore: $KEYSTORE"
echo "  Password: $PASSWORD"
echo ""

# Generate keystore with self-signed cert
# SAN (Subject Alternative Names) allows the cert to work for multiple hostnames
keytool -genkeypair \
    -alias claij \
    -keyalg RSA \
    -keysize 2048 \
    -validity $DAYS \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=$HOSTNAME, OU=Development, O=CLAIJ, L=Local, ST=Dev, C=US" \
    -ext "SAN=dns:localhost,dns:$HOSTNAME,ip:127.0.0.1"

echo ""
echo "Certificate generated successfully!"
echo ""
echo "To start CLAIJ with HTTPS:"
echo "  clojure -M -m claij.server --ssl-port 8443 --keystore $KEYSTORE"
echo ""
echo "Then open: https://localhost:8443"
echo ""
echo "Note: Your browser will warn about the self-signed certificate."
echo "      This is expected for development. Click 'Advanced' and proceed."
echo ""
echo "For iOS/mobile testing, you may need to:"
echo "  1. Access https://<your-ip>:8443 from the device"
echo "  2. Accept the certificate warning"
echo "  3. On iOS: Settings > General > About > Certificate Trust Settings"
echo "     and enable trust for the certificate"
