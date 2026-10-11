#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  tools/device_resilience_test.sh PHONE_SERIAL WATCH_SERIAL

Environment:
  PACKAGE_ID   Package under test (default: com.bluebubbles.messaging.alpha)
  OUT_DIR      Results directory (default: ./device-resilience-<timestamp>)
  RAPID_COUNT  Number of read-only rapid commands (default: 5)
  DOZE_TEST    Set to 1 to temporarily force phone Doze, then automatically undo it

Requires a debug Wear build because the command injection receiver is debug-only.
All injected commands are read-only requestInbox commands. The script never sends a message,
uninstalls an app, clears app data, toggles radios, or reboots either device.
EOF
}

if [[ $# -ne 2 ]]; then
  usage >&2
  exit 64
fi

PHONE_SERIAL=$1
WATCH_SERIAL=$2
PACKAGE_ID=${PACKAGE_ID:-com.bluebubbles.messaging.alpha}
OUT_DIR=${OUT_DIR:-"device-resilience-$(date +%Y%m%d-%H%M%S)"}
RAPID_COUNT=${RAPID_COUNT:-5}
DOZE_TEST=${DOZE_TEST:-0}
ACTION=com.openbubbles.wear.DEBUG_REQUEST_INBOX

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb is not in PATH" >&2; exit 69; }
[[ "$RAPID_COUNT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: RAPID_COUNT must be a positive integer" >&2; exit 64; }

mkdir -p "$OUT_DIR"

require_device() {
  local serial=$1 label=$2 state
  state=$(adb -s "$serial" get-state 2>/dev/null || true)
  [[ "$state" == "device" ]] || {
    echo "ERROR: $label device $serial is not available through adb (state='$state')" >&2
    exit 70
  }
}

require_device "$PHONE_SERIAL" phone
require_device "$WATCH_SERIAL" watch

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  elif [[ -r /proc/sys/kernel/random/uuid ]]; then
    cat /proc/sys/kernel/random/uuid
  else
    python3 - <<'PY'
import uuid
print(uuid.uuid4())
PY
  fi
}

phone_log() {
  adb -s "$PHONE_SERIAL" logcat -d -s OBWearPhone:D '*:S' > "$OUT_DIR/phone-wear.log" 2>&1 || true
}

watch_log() {
  adb -s "$WATCH_SERIAL" logcat -d -s OBWearWatch:D '*:S' > "$OUT_DIR/watch-wear.log" 2>&1 || true
}

refresh_logs() {
  phone_log
  watch_log
}

inject_inbox() {
  local command_id=$1
  adb -s "$WATCH_SERIAL" shell am broadcast \
    -a "$ACTION" \
    -p "$PACKAGE_ID" \
    --es commandId "$command_id" >/dev/null
}

phone_saw_command() {
  local prefix=${1:0:8}
  phone_log
  grep -q "command type=requestInbox id=$prefix" "$OUT_DIR/phone-wear.log"
}

watch_saw_ack() {
  local prefix=${1:0:8}
  watch_log
  grep -q "ack status=.* id=$prefix" "$OUT_DIR/watch-wear.log"
}

DOZE_FORCED=0
cleanup() {
  if [[ $DOZE_FORCED -eq 1 ]]; then
    echo "Restoring phone from forced Doze..."
    adb -s "$PHONE_SERIAL" shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
    adb -s "$PHONE_SERIAL" shell dumpsys battery reset >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

{
  echo "package=$PACKAGE_ID"
  echo "phone_serial=$PHONE_SERIAL"
  echo "watch_serial=$WATCH_SERIAL"
  echo "rapid_count=$RAPID_COUNT"
  echo "doze_test=$DOZE_TEST"
  echo "captured_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$OUT_DIR/test-info.txt"

adb -s "$PHONE_SERIAL" logcat -c || true
adb -s "$WATCH_SERIAL" logcat -c || true

# Put the matching apps into a known, non-force-stopped state.
adb -s "$PHONE_SERIAL" shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
adb -s "$WATCH_SERIAL" shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 4

failures=0
warns=0

echo "--- Baseline read-only command ---"
baseline_id=$(new_uuid)
inject_inbox "$baseline_id"
sleep 6
if phone_saw_command "$baseline_id"; then
  echo "PASS: phone received baseline requestInbox"
else
  echo "FAIL: phone did not receive baseline requestInbox"
  failures=$((failures + 1))
fi
if watch_saw_ack "$baseline_id"; then
  echo "PASS: watch received an ACK for baseline requestInbox"
else
  echo "WARN: no ACK observed for baseline requestInbox"
  warns=$((warns + 1))
fi

echo
echo "--- Phone UI/process background wake ---"
adb -s "$PHONE_SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
sleep 1
# am kill is intentionally used instead of force-stop. A force-stopped package is not allowed to
# receive the normal background events this test is meant to exercise.
adb -s "$PHONE_SERIAL" shell am kill "$PACKAGE_ID" >/dev/null 2>&1 || true
sleep 2
background_id=$(new_uuid)
inject_inbox "$background_id"
sleep 10
if phone_saw_command "$background_id"; then
  echo "PASS: phone received requestInbox after its UI/process was killed normally"
else
  echo "FAIL: background command did not reach the phone"
  failures=$((failures + 1))
fi

echo
echo "--- Duplicate UUID deduplication ---"
duplicate_id=$(new_uuid)
inject_inbox "$duplicate_id"
sleep 6
inject_inbox "$duplicate_id"
sleep 4
phone_log
duplicate_prefix=${duplicate_id:0:8}
if grep -q "immediate ack status=duplicate id=$duplicate_prefix" "$OUT_DIR/phone-wear.log"; then
  echo "PASS: repeated UUID returned duplicate instead of executing twice"
else
  echo "FAIL: duplicate ACK was not observed for repeated UUID"
  failures=$((failures + 1))
fi

echo
echo "--- Rapid read-only command burst ---"
rapid_ids=()
for ((i=0; i<RAPID_COUNT; i++)); do
  id=$(new_uuid)
  rapid_ids+=("$id")
  inject_inbox "$id"
  sleep 0.2
done
sleep 10
phone_log
rapid_missing=0
for id in "${rapid_ids[@]}"; do
  prefix=${id:0:8}
  if ! grep -q "command type=requestInbox id=$prefix" "$OUT_DIR/phone-wear.log"; then
    echo "  missing: $prefix"
    rapid_missing=$((rapid_missing + 1))
  fi
done
if [[ $rapid_missing -eq 0 ]]; then
  echo "PASS: all $RAPID_COUNT rapid commands reached the phone"
else
  echo "FAIL: $rapid_missing/$RAPID_COUNT rapid commands were not observed"
  failures=$((failures + 1))
fi

if [[ "$DOZE_TEST" == "1" ]]; then
  echo
echo "--- Forced Doze delivery ---"
  adb -s "$PHONE_SERIAL" shell dumpsys deviceidle force-idle >/dev/null
  DOZE_FORCED=1
  doze_id=$(new_uuid)
  inject_inbox "$doze_id"
  sleep 15
  if phone_saw_command "$doze_id"; then
    echo "PASS: requestInbox reached phone while forced into Doze"
  else
    echo "FAIL: no requestInbox receipt was observed during forced Doze"
    failures=$((failures + 1))
  fi
  adb -s "$PHONE_SERIAL" shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb -s "$PHONE_SERIAL" shell dumpsys battery reset >/dev/null 2>&1 || true
  DOZE_FORCED=0
fi

refresh_logs
adb -s "$PHONE_SERIAL" shell dumpsys activity exit-info "$PACKAGE_ID" > "$OUT_DIR/phone-exit-info.txt" 2>&1 || true
adb -s "$WATCH_SERIAL" shell dumpsys activity exit-info "$PACKAGE_ID" > "$OUT_DIR/watch-exit-info.txt" 2>&1 || true

echo
echo "--- Result ---"
echo "Failures: $failures"
echo "Warnings: $warns"
echo "Logs: $OUT_DIR"
echo "  $OUT_DIR/phone-wear.log"
echo "  $OUT_DIR/watch-wear.log"
echo "  $OUT_DIR/phone-exit-info.txt"
echo "  $OUT_DIR/watch-exit-info.txt"

if [[ $failures -ne 0 ]]; then
  exit 2
fi
