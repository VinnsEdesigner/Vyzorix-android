#!/usr/bin/env bash
#
# Boot the test emulator with one command, and wait until it is usable.
#
# The image and flags are not free choices: this host has **no KVM** (no /dev/kvm,
# no vmx/svm), so hardware acceleration is impossible and a naive `emulator` call
# fails. What works, and what this script does:
#
#   * the `x86_64` system image (matches the host; the arm64 image refuses to boot
#     on an x86_64 host with "not supported by the QEMU2 emulator");
#   * `-accel off` explicitly;
#   * a full boot takes 1-3 minutes. That is normal here, not a hang.
#
# Usage:
#   scripts/run-emulator.sh [--avd NAME] [--install APK] [--wipe] [--fg]
#
#   --avd NAME     AVD to boot (default x86test, created by install-toolchain.sh)
#   --install APK  install this APK once booted (repeatable)
#   --wipe         discard user data first. Use it when adb install starts failing
#                  with a package-service error, which a long-lived emulator
#                  eventually does.
#   --fg           stay in the foreground (do not background the emulator)
#   --help
#
# Env: ANDROID_HOME / ANDROID_AVD_HOME must be set — `source <prefix>/env.sh` does
# that. See scripts/install-toolchain.sh.

set -uo pipefail

AVD="x86test"
WIPE=0
FOREGROUND=0
INSTALL=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd)     AVD="$2"; shift 2 ;;
    --install) INSTALL+=("$2"); shift 2 ;;
    --wipe)    WIPE=1; shift ;;
    --fg)      FOREGROUND=1; shift ;;
    --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

log() { printf '\n=== %s\n' "$*"; }

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is not set. Source the toolchain env first:" >&2
  echo "  source \${VYZORIX_TC:-/workspace/toolchain}/env.sh" >&2
  exit 1
fi

ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
BOOT_TIMEOUT_S="${BOOT_TIMEOUT_S:-420}"

[[ -x "$EMULATOR" ]] || { echo "emulator not found at $EMULATOR" >&2; exit 1; }
[[ -x "$ADB" ]] || { echo "adb not found at $ADB" >&2; exit 1; }

# ------------------------------------------------------------------- already up?
if "$ADB" devices | grep -qE '^emulator-[0-9]+\s+device$'; then
  log "an emulator is already connected; reusing it"
else
  log "booting AVD $AVD (x86_64, -accel off; 1-3 minutes)"
  FLAGS=(-avd "$AVD" -no-window -no-audio -no-boot-anim
         -gpu swiftshader_indirect -no-snapshot -accel off)
  [[ "$WIPE" == 1 ]] && FLAGS+=(-wipe-data)

  if [[ "$FOREGROUND" == 1 ]]; then
    "$EMULATOR" "${FLAGS[@]}"
    exit 0
  fi

  mkdir -p "${VYZORIX_TC:-/tmp}/logs"
  LOG="${VYZORIX_TC:-/tmp}/logs/emulator-$AVD.log"
  nohup "$EMULATOR" "${FLAGS[@]}" > "$LOG" 2>&1 &
  echo "emulator pid $! (log: $LOG)"
fi

# ------------------------------------------------------------------- wait for boot
log "waiting for the device to come up"
"$ADB" wait-for-device

deadline=$(( SECONDS + BOOT_TIMEOUT_S ))
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  if (( SECONDS >= deadline )); then
    echo "device did not finish booting in ${BOOT_TIMEOUT_S}s" >&2
    echo "see ${VYZORIX_TC:-/tmp}/logs/emulator-$AVD.log" >&2
    exit 1
  fi
  sleep 5
done

# boot_completed flips a moment before the package service can accept installs, and
# Gradle's device discovery races the adb server; give both a beat.
"$ADB" shell input keyevent 82 >/dev/null 2>&1 || true
sleep 5

log "booted"
"$ADB" devices
printf 'android %s, abi %s\n' \
  "$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')" \
  "$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"

for apk in "${INSTALL[@]:-}"; do
  [[ -n "$apk" ]] || continue
  log "installing $apk"
  "$ADB" install -r -t "$apk" || echo "install failed: $apk" >&2
done

log "ready"
cat <<EOF

Run the instrumented suite:
  ./gradlew :app:connectedDebugAndroidTest -ProotfsAbi=x86_64
  ./gradlew :android-core:connectedDebugAndroidTest

Watch logs:
  $ADB logcat

Stop:
  $ADB emu kill
EOF
