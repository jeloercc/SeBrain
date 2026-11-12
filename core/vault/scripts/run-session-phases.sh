#!/bin/sh
# Runs the VaultSession checks that need process restarts or a locked device.
# Usage: run-session-phases.sh <serial> before-lock|lock|after-unlock
set -eu

SERIAL=$1
STAGE=$2
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
PACKAGE=com.sebrain.core.vault.test
CLASS=com.sebrain.core.vault.VaultSessionPhaseTest
RUNNER=$PACKAGE/androidx.test.runner.AndroidJUnitRunner

run_phase() {
    $ADB shell am instrument -w -e class "$CLASS#$1" -e phase "$2" "$RUNNER"
}

case "$STAGE" in
before-lock)
    (cd "$ROOT" && ANDROID_SERIAL=$SERIAL ./gradlew -q :core:vault:installDebugAndroidTest)
    run_phase phaseCreate create
    $ADB shell am force-stop "$PACKAGE"
    run_phase phaseReopenAfterKill reopen
    ;;
lock)
    $ADB logcat -b main -c
    run_phase phaseClosesOnLockAndCannotOpen locked &
    PHASE_PID=$!
    until $ADB logcat -d -s SEBRAIN_VAULT_PHASE:I | grep -q READY_FOR_SCREEN_OFF; do sleep 1; done
    $ADB shell input keyevent KEYCODE_SLEEP
    wait "$PHASE_PID"
    $ADB logcat -d -s SEBRAIN_VAULT_PHASE:I | grep SEBRAIN_VAULT_PHASE
    ;;
after-unlock)
    run_phase phaseAfterUnlock afterUnlock
    ;;
*)
    echo "Unknown stage: $STAGE" >&2
    exit 1
    ;;
esac
