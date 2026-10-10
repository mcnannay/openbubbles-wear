#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  tools/device_smoke_test.sh PHONE_SERIAL WATCH_SERIAL PHONE_APK WEAR_APK

Environment:
  PACKAGE_ID   Android application id to test (default: com.bluebubbles.messaging.alpha)
  OUT_DIR      Directory for captured logs/results (default: ./device-smoke-<timestamp>)
  SKIP_INSTALL Set to 1 to keep existing installed APKs and only launch/test them

The script never uninstalls the apps or clears app data.
EOF
}

if [[ $# -ne 4 ]]; then
  usage >&2
  exit 64
fi

PHONE_SERIAL=$1
WATCH_SERIAL=$2
PHONE_APK=$3
WEAR_APK=$4
PACKAGE_ID=${PACKAGE_ID:-com.bluebubbles.messaging.alpha}
SKIP_INSTALL=${SKIP_INSTALL:-0}
OUT_DIR=${OUT_DIR:-"device-smoke-$(date +%Y%m%d-%H%M%S)"}

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb is not in PATH" >&2; exit 69; }
[[ -f "$PHONE_APK" ]] || { echo "ERROR: phone APK not found: $PHONE_APK" >&2; exit 66; }
[[ -f "$WEAR_APK" ]] || { echo "ERROR: Wear APK not found: $WEAR_APK" >&2; exit 66; }

mkdir -p "$OUT_DIR"

require_device() {
  local serial=$1
  local label=$2
  local state
  state=$(adb -s "$serial" get-state 2>/dev/null || true)
  if [[ "$state" != "device" ]]; then
    echo "ERROR: $label device $serial is not available through adb (state='$state')" >&2
    exit 70
  fi
}

require_device "$PHONE_SERIAL" phone
require_device "$WATCH_SERIAL" watch

if command -v apksigner >/dev/null 2>&1; then
  phone_cert=$(apksigner verify --print-certs "$PHONE_APK" 2>/dev/null | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}')
  wear_cert=$(apksigner verify --print-certs "$WEAR_APK" 2>/dev/null | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}')
  if [[ -n "$phone_cert" && -n "$wear_cert" ]]; then
    if [[ "$phone_cert" != "$wear_cert" ]]; then
      echo "ERROR: phone and Wear APK signing certificates do not match." >&2
      echo "phone: $phone_cert" >&2
      echo "wear:  $wear_cert" >&2
      exit 65
    fi
    echo "PASS: APK signing certificates match ($phone_cert)"
  fi
fi

if [[ "$SKIP_INSTALL" != "1" ]]; then
  echo "Installing phone APK on $PHONE_SERIAL (data preserved)..."
  adb -s "$PHONE_SERIAL" install -r "$PHONE_APK"
  echo "Installing Wear APK on $WATCH_SERIAL (data preserved)..."
  adb -s "$WATCH_SERIAL" install -r "$WEAR_APK"
fi

{
  echo "package=$PACKAGE_ID"
  echo "phone_serial=$PHONE_SERIAL"
  echo "watch_serial=$WATCH_SERIAL"
  echo "phone_apk=$PHONE_APK"
  echo "wear_apk=$WEAR_APK"
  echo "captured_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  command -v sha256sum >/dev/null 2>&1 && sha256sum "$PHONE_APK" "$WEAR_APK"
} > "$OUT_DIR/build-info.txt"

adb -s "$PHONE_SERIAL" shell dumpsys package "$PACKAGE_ID" > "$OUT_DIR/phone-package.txt" 2>&1 || true
adb -s "$WATCH_SERIAL" shell dumpsys package "$PACKAGE_ID" > "$OUT_DIR/watch-package.txt" 2>&1 || true

adb -s "$PHONE_SERIAL" logcat -c || true
adb -s "$WATCH_SERIAL" logcat -c || true

# Use the launcher intent instead of hard-coding flavor-specific component names.
echo "Launching phone app..."
adb -s "$PHONE_SERIAL" shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 2
echo "Launching Wear app..."
adb -s "$WATCH_SERIAL" shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

# The watch requests the inbox on resume and again when a peer connects. Give Data Layer time to settle.
sleep 12

adb -s "$PHONE_SERIAL" logcat -d -s OBWearPhone:D '*:S' > "$OUT_DIR/phone-wear.log" 2>&1 || true
adb -s "$WATCH_SERIAL" logcat -d -s OBWearWatch:D '*:S' > "$OUT_DIR/watch-wear.log" 2>&1 || true

phone_seen=0
watch_seen=0
snapshot_seen=0

grep -q 'command type=requestInbox' "$OUT_DIR/phone-wear.log" && phone_seen=1 || true
grep -q 'send type=requestInbox' "$OUT_DIR/watch-wear.log" && watch_seen=1 || true
grep -Eq 'inbox snapshot chats=|state snapshot backendReady=' "$OUT_DIR/watch-wear.log" && snapshot_seen=1 || true

echo
echo "--- Wear bridge smoke test ---"
if [[ $watch_seen -eq 1 ]]; then
  echo "PASS: watch attempted a requestInbox command"
else
  echo "FAIL: watch did not log a requestInbox attempt"
fi

if [[ $phone_seen -eq 1 ]]; then
  echo "PASS: phone received the Wear requestInbox command"
else
  echo "FAIL: phone did not receive the Wear requestInbox command"
fi

if [[ $snapshot_seen -eq 1 ]]; then
  echo "PASS: watch received phone state/inbox Data Layer data"
else
  echo "WARN: no phone state/inbox snapshot reached the watch during this short test"
fi

echo "Logs saved in: $OUT_DIR"
echo "  $OUT_DIR/watch-wear.log"
echo "  $OUT_DIR/phone-wear.log"

if [[ $watch_seen -ne 1 || $phone_seen -ne 1 ]]; then
  cat <<'EOF'

The command path did not complete. Check that:
  * phone and watch are paired and connected in Wear OS/Google Play services;
  * both APKs use the same application id/flavor;
  * both APKs are signed by the same certificate;
  * the alpha phone app has been opened at least once.
EOF
  exit 2
fi
