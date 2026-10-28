#!/usr/bin/env bash
# Verify a release keystore against the credentials the CI workflows expect.
#
# Run this before adding the four repository secrets (or when a release fails in
# the "generate release key" step). It answers one question: can these exact
# credentials open this exact keystore, and what alias is inside it?
#
# Usage:
#   tools/verify-signing.sh release.jks
#   KEYSTORE_PASSWORD=... tools/verify-signing.sh release.jks
#
# Nothing is uploaded and the password is never echoed.
set -euo pipefail

KEYSTORE="${1:-release.jks}"

if [ ! -f "$KEYSTORE" ]; then
    echo "error: keystore '$KEYSTORE' not found." >&2
    echo "create one with:" >&2
    echo "  keytool -genkeypair -v -keystore release.jks -alias tafh \\" >&2
    echo "    -keyalg RSA -keysize 4096 -validity 10000" >&2
    exit 1
fi

# Same normalisation the workflow applies, so a value that works here works there.
normalise() {
    local v="${1:-}"
    v="${v#\"}"; v="${v%\"}"; v="${v#\'}"; v="${v%\'}"
    printf '%s' "$v" | sed -e 's/\\n$//' -e 's/\\r$//' \
                         -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

printf 'keystore : %s\n' "$KEYSTORE"
printf 'format   : %s\n' "$(od -An -tx1 -N4 "$KEYSTORE" | tr -d ' \n' | grep -q '^feedfeed' && echo JKS || echo 'PKCS12 (or unknown)')"
printf 'size     : %s bytes\n' "$(wc -c < "$KEYSTORE" | tr -d ' ')"

if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    # Interactive prompt keeps the password out of shell history.
    read -rsp 'store password: ' KEYSTORE_PASSWORD
    echo
fi
STOREPASS="$(normalise "$KEYSTORE_PASSWORD")"

if ! keytool -list -keystore "$KEYSTORE" -storepass "$STOREPASS" >/dev/null 2>&1; then
    echo
    echo "FAIL: that password does not open this keystore." >&2
    echo "  - the keystore was created with a different -storepass" >&2
    echo "  - or this is not the file you created (regenerated after copying?)" >&2
    exit 1
fi

echo
echo "OK: password opens the keystore. Aliases inside:"
keytool -list -keystore "$KEYSTORE" -storepass "$STOREPASS" 2>/dev/null \
    | sed -n 's/^\([^,]*\),.*/\1/p' | sed 's/^/  /'

echo
echo "Add these four repository secrets (Settings -> Secrets and variables -> Actions):"
echo "  KEYSTORE_BASE64    = $(base64 -w0 "$KEYSTORE" | tr -d '\n' | wc -c | tr -d ' ') characters (regenerate with: base64 -w0 $KEYSTORE)"
echo "  KEYSTORE_PASSWORD  = the password you just entered"
echo "  KEY_ALIAS          = one of the aliases listed above"
echo "  KEY_PASSWORD       = same as KEYSTORE_PASSWORD unless you set a distinct key password"
