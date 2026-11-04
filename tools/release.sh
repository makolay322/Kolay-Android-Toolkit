#!/usr/bin/env bash
# Publishes a new Kolay.apk release. Usage: tools/release.sh [versionName]
# Runs in two places with the same logic:
#   - GitHub Actions (.github/workflows/release.yml): update key from secret UPDATE_KEY_PEM, APK keystore from KEYSTORE_* secrets.
#   - Locally: keys from ~/.kolay/ (update_key.pem, release.keystore, keystore.properties).
# Versions are stateless: the next versionCode/versionName derive from the latest published manifest (no commit-back).
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gh >/dev/null || { echo "gh not installed"; exit 1; }
CI_MODE=${GITHUB_ACTIONS:-false}
KEYDIR="$HOME/.kolay"
TMP=$(mktemp -d)
cleanup() { rm -rf "$TMP"; git checkout -q -- app/version.properties 2>/dev/null || true
  [ "$CI_MODE" = true ] && rm -f "$KEYDIR/release.keystore" "$KEYDIR/keystore.properties" || true; }
trap cleanup EXIT

if [ "$CI_MODE" = true ]; then
  [ -n "${UPDATE_KEY_PEM:-}" ] || { echo "secret UPDATE_KEY_PEM missing"; exit 1; }
  ( umask 077; printf '%s\n' "$UPDATE_KEY_PEM" > "$TMP/update_key.pem" )
  # The APK must carry the same signing certificate as the installed app, or Android refuses the update: no keystore, no release.
  [ -n "${KEYSTORE_B64:-}" ] && { [ -n "${KEYSTORE_PROPERTIES:-}" ] || [ -n "${KEYSTORE_PASSWORD:-}" ]; } || { echo "keystore secrets missing"; exit 1; }
  ( umask 077; mkdir -p "$KEYDIR"
    printf '%s' "$KEYSTORE_B64" | base64 -d > "$KEYDIR/release.keystore"
    if [ -n "${KEYSTORE_PROPERTIES:-}" ]; then
      printf '%s\n' "$KEYSTORE_PROPERTIES" | sed "s#^storeFile=.*#storeFile=$KEYDIR/release.keystore#" > "$KEYDIR/keystore.properties"
    else
      printf 'storeFile=%s/release.keystore\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$KEYDIR" "$KEYSTORE_PASSWORD" "${KEY_ALIAS:-kolay}" "${KEY_PASSWORD:-$KEYSTORE_PASSWORD}" > "$KEYDIR/keystore.properties"
    fi )
  KEY="$TMP/update_key.pem"; SHA_HEAD=$GITHUB_SHA
else
  for f in keystore.properties release.keystore update_key.pem; do [ -f "$KEYDIR/$f" ] || { echo "missing $KEYDIR/$f"; exit 1; }; done
  [ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "release only from main"; exit 1; }
  [ -z "$(git status --porcelain)" ] || { echo "working tree not clean; commit first"; exit 1; }
  git fetch -q origin main
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "push main first (HEAD must equal origin/main)"; exit 1; }
  KEY="$KEYDIR/update_key.pem"; SHA_HEAD=$(git rev-parse HEAD)
fi
REPO=$(git config remote.origin.url | sed -E 's#.*github.com[:/]##; s#\.git$##')

# --- version: one above the latest published manifest (fallback: the base in app/version.properties) ---
PROP=app/version.properties
VC=$(sed -n 's/^versionCode=//p' "$PROP"); CUR=$(sed -n 's/^versionName=//p' "$PROP")
if curl -fsSL "https://github.com/$REPO/releases/latest/download/manifest.json" -o "$TMP/latest.json" 2>/dev/null; then
  LVC=$(sed -n 's/.*"versionCode":\([0-9]*\).*/\1/p' "$TMP/latest.json"); LVN=$(sed -n 's/.*"versionName":"\([^"]*\)".*/\1/p' "$TMP/latest.json")
  [ -n "$LVC" ] && [ "$LVC" -ge "$VC" ] && { VC=$LVC; CUR=$LVN; }
fi
VC=$(( VC + 1 ))
# Default name: bump the three-digit minor (1.013 -> 1.014).
if [[ "$CUR" =~ ^([0-9]+)\.([0-9]{3})$ ]]; then NEXT=$(printf '%s.%03d' "${BASH_REMATCH[1]}" $(( 10#${BASH_REMATCH[2]} + 1 )))
else NEXT="${CUR%.*}.$(( ${CUR##*.} + 1 ))"; fi
VN="${1:-$NEXT}"
gh release view "v$VN" >/dev/null 2>&1 && { echo "release v$VN already exists"; exit 1; }
printf 'versionCode=%s\nversionName=%s\n' "$VC" "$VN" > "$PROP"

GRADLE=./gradlew; [ -x "$GRADLE" ] || GRADLE=gradle
OUT=build/release; rm -rf "$OUT"; mkdir -p "$OUT"
$GRADLE -q --no-daemon :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$OUT/Kolay.apk"
APK="$OUT/Kolay.apk"

SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
SIZE=$(wc -c < "$APK" | tr -d ' ')
SIG=$(openssl dgst -sha256 -sign "$KEY" "$APK" | base64 | tr -d '\n')
# self-check: the signature must verify with the public key before anything is published
openssl ec -in "$KEY" -pubout -out "$TMP/pub.pem" 2>/dev/null
echo "$SIG" | base64 -d > "$TMP/sig.der"
openssl dgst -sha256 -verify "$TMP/pub.pem" -signature "$TMP/sig.der" "$APK" >/dev/null || { echo "signature self-check failed"; exit 1; }
PUB=$(openssl ec -in "$KEY" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n')
grep -q "$PUB" app/src/main/java/app/kolay/shell/Config.java || { echo "signing key is not in Config.UPDATE_PUBLIC_KEYS"; exit 1; }

URL="https://github.com/$REPO/releases/download/v$VN/Kolay.apk"
# versionCode/url/size/sha256/signature: read by Kolay ≥ 1.014 (the app itself).
# "api":5 and the shell* fields: only for installs still on the old two-APK design (shell ≤ 1.013). Their updater sees
# api 5 > 4, downloads no module, and offers this APK through "Kabuğu indir ve yükle". Keep both until no such install is left.
cat > "$OUT/manifest.json" <<JSON
{"versionCode":$VC,"versionName":"$VN","api":5,"size":$SIZE,"sha256":"$SHA","signature":"$SIG","url":"$URL","shellVersionCode":$VC,"shellVersionName":"$VN","shellSize":$SIZE,"shellSha256":"$SHA","shellSignature":"$SIG","shellUrl":"$URL"}
JSON

gh release create "v$VN" "$APK" "$OUT/manifest.json" --target "$SHA_HEAD" --title "$VN" --notes "Kolay $VN (versionCode $VC) — commit ${SHA_HEAD:0:7}" --latest
echo "released v$VN (versionCode $VC)"
