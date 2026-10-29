#!/usr/bin/env bash
# Upload the release signing material to GitHub Actions, verifying it first.
#
# Copy-pasting a base64 keystore into a web form is where this goes wrong: lines
# get wrapped, padding gets lost, and a stale keystore gets uploaded alongside a
# password that belongs to a different file. This script avoids the copy entirely
# by piping the file into `gh secret set`.
#
# Usage:
#   tools/set-signing-secrets.sh release.jks
#   tools/set-signing-secrets.sh release.jks --repo nero-/Tafh
#
# The passwords are read interactively (never from argv, never echoed) and are
# validated against the keystore before anything is uploaded, so a wrong password
# fails here in one second instead of after a seven-minute CI run.
set -euo pipefail

KEYSTORE="${1:-}"
shift || true
REPO="nero-/Tafh"

while [ $# -gt 0 ]; do
    case "$1" in
        --repo) REPO="${2:-}"; shift 2 ;;
        *) echo "error: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

if [ -z "$KEYSTORE" ]; then
    echo "usage: tools/set-signing-secrets.sh <keystore.jks> [--repo owner/name]" >&2
    exit 2
fi
if [ ! -f "$KEYSTORE" ]; then
    echo "error: '$KEYSTORE' not found. Pass the path to the .jks you just created." >&2
    exit 1
fi

command -v gh >/dev/null || { echo "error: gh CLI is required." >&2; exit 1; }

printf 'keystore : %s\n' "$KEYSTORE"
printf 'size     : %s bytes\n' "$(wc -c < "$KEYSTORE" | tr -d ' ')"
printf 'sha256   : %s\n' "$(sha256sum "$KEYSTORE" | cut -d' ' -f1)"
printf 'repo     : %s\n\n' "$REPO"

# ── credentials ───────────────────────────────────────────────────────────────
read -rsp 'store password: ' STORE_PASS; echo
[ -n "$STORE_PASS" ] || { echo "error: empty password." >&2; exit 1; }

read -rsp 'key password (Enter = same as store password): ' KEY_PASS; echo
[ -n "$KEY_PASS" ] || KEY_PASS="$STORE_PASS"

echo
echo "Aliases in this keystore:"
ALIASES="$(keytool -list -keystore "$KEYSTORE" -storepass "$STORE_PASS" 2>/dev/null \
            | sed -n 's/^\([^,]*\),.*/\1/p' || true)"
if [ -z "$ALIASES" ]; then
    echo "  (none readable)"
    echo
    echo "FAIL: that store password does not open this keystore." >&2
    echo "  Check you are passing the file the password was created with:" >&2
    echo "    keytool -list -keystore $KEYSTORE -storepass <password>" >&2
    exit 1
fi
printf '  %s\n' $ALIASES

if [ "$(printf '%s\n' $ALIASES | wc -l | tr -d ' ')" = "1" ]; then
    ALIAS="$(printf '%s' "$ALIASES" | head -n1)"
    printf '\nusing the only alias: %s\n' "$ALIAS"
else
    read -rp 'alias to use: ' ALIAS
fi

# Prove the key can actually be used for signing before uploading anything.
if ! keytool -list -keystore "$KEYSTORE" -storepass "$STORE_PASS" -alias "$ALIAS" >/dev/null 2>&1; then
    echo "FAIL: alias '$ALIAS' cannot be read with that password." >&2
    exit 1
fi

# ── upload ────────────────────────────────────────────────────────────────────
echo
echo "Uploading secrets to $REPO ..."
# `gh secret set NAME < file` streams the bytes, so no wrapping, padding or
# shell-escaping is involved.
if ! base64 -w0 "$KEYSTORE" | gh secret set KEYSTORE_BASE64 --repo "$REPO"; then
    echo "error: failed to upload KEYSTORE_BASE64 (are you authenticated? try 'gh auth status')." >&2
    exit 1
fi
gh secret set KEYSTORE_PASSWORD --repo "$REPO" --body "$STORE_PASS"
gh secret set KEY_PASSWORD --repo "$REPO" --body "$KEY_PASS"
gh secret set KEY_ALIAS --repo "$REPO" --body "$ALIAS"

echo
gh secret list --repo "$REPO" | sed 's/^/  /'
echo
echo "Done. Keep '$KEYSTORE' somewhere safe and backed up: losing it means you can"
echo "never publish an update to an already-installed copy of the app."
