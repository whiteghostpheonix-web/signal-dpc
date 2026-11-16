#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIGNING_DIR="${HOME}/.config/signal-electronics/dpc-signing"
KEYSTORE="${SIGNING_DIR}/signal-dpc-release.jks"
PASSWORD_FILE="${SIGNING_DIR}/store-password.txt"
: "${ANDROID_HOME:=/tmp/android-sdk}"
: "${GRADLE_USER_HOME:=/tmp/gradle-home}"
if [[ ! -f "$KEYSTORE" || ! -f "$PASSWORD_FILE" ]]; then
  echo "Release signing key is missing: $SIGNING_DIR" >&2
  exit 1
fi
umask 077
mkdir -p "$GRADLE_USER_HOME"
STORE_PASSWORD="$(cat "$PASSWORD_FILE")"
cat > "$GRADLE_USER_HOME/gradle.properties" <<PROPS
SIGNAL_DPC_STORE_FILE=$KEYSTORE
SIGNAL_DPC_STORE_PASSWORD=$STORE_PASSWORD
SIGNAL_DPC_KEY_PASSWORD=$STORE_PASSWORD
SIGNAL_DPC_KEY_ALIAS=signal-dpc-release
PROPS
unset STORE_PASSWORD
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" GRADLE_USER_HOME
cd "$ROOT"
./gradlew --no-daemon clean assembleRelease
