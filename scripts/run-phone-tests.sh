#!/bin/sh
# Runs every phone test in one session. The device must start unlocked, with the screen on,
# connected by USB. The script locks it once; when the screen turns on again by itself, the
# device must be unlocked one more time.
#
# Usage: scripts/run-phone-tests.sh <serial>
set -u

SERIAL=$1
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/phone-tests
SUMMARY=$OUT/summary.txt
mkdir -p "$OUT"
: > "$SUMMARY"
cd "$ROOT"

record() { echo "$1 | $2 | $3" | tee -a "$SUMMARY"; }
locked() { $ADB shell dumpsys trust | grep -m1 -q 'deviceLocked=1'; }
instrument() { # package class#method phase
    $ADB shell am instrument -w -e class "$2" -e phase "$3" "$1/androidx.test.runner.AndroidJUnitRunner" 2>&1
}
phase() { # name package class#method phase
    if instrument "$2" "$3" "$4" > "$OUT/$1.log" && grep -q '^OK (1 test)' "$OUT/$1.log"; then
        record "$1" PASS ""
    else
        record "$1" FAIL "see $OUT/$1.log"
    fi
}

$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
if locked; then echo "The device is locked. Unlock it and start again."; exit 1; fi
$ADB shell svc power stayon usb

echo "== 1. Instrumented tests (unlocked)"
for module in core:crypto core:vault app; do
    name=$(echo "$module" | tr ':' '-')
    if ANDROID_SERIAL=$SERIAL ./gradlew -q ":$module:connectedDebugAndroidTest" > "$OUT/connected-$name.log" 2>&1; then
        record "connected $module" PASS "$(cat "$module/build/outputs/androidTest-results/connected/debug/"TEST-*.xml 2>/dev/null | grep -o 'tests="[0-9]*" failures="[0-9]*"' | head -1)"
    else
        record "connected $module" FAIL "see $OUT/connected-$name.log"
    fi
done
# The emulator-only page size check and the phase tests are skipped here by design.

echo "== 2. Install the apps for the phases"
ANDROID_SERIAL=$SERIAL ./gradlew -q :core:vault:installDebugAndroidTest :app:installDebug :app:installDebugAndroidTest > "$OUT/install.log" 2>&1 \
    || record "install" FAIL "see $OUT/install.log"
(cd spikes/diag && ./gradlew -q assembleDebug > "$OUT/diag-build.log" 2>&1 && $ADB install -r app/build/outputs/apk/debug/app-debug.apk > /dev/null) \
    || record "install spike" FAIL "see $OUT/diag-build.log"

VAULT=com.sebrain.core.vault.test
VAULT_CLASS=com.sebrain.core.vault.VaultSessionPhaseTest
APP=com.sebrain.app.test
APP_CLASS=com.sebrain.app.LockedPhaseTest

echo "== 3. Share intent, end to end"
$ADB logcat -c
STAMP=$(date +%s)
share() {
    $ADB shell am start -W -n com.sebrain.app/com.sebrain.ingest.ShareActivity -a android.intent.action.SEND \
        -t text/plain --es android.intent.extra.TEXT "'Phone test capture $STAMP'" > /dev/null 2>&1
    sleep 4
}
share
share
$ADB shell am start -W -n com.sebrain.app/.MainActivity > /dev/null 2>&1
sleep 4
$ADB logcat -d -s SEBRAIN_INGEST:I SEBRAIN_APP:I > "$OUT/share.log"
grep -q 'share result=Stored' "$OUT/share.log" && record "share: first share stored" PASS "" || record "share: first share stored" FAIL "see $OUT/share.log"
grep -q 'import imported=1 .*remaining=0 stoppedBy=null' "$OUT/share.log" && record "share: imported into the vault" PASS "" || record "share: imported into the vault" FAIL "see $OUT/share.log"
grep -q 'share result=Duplicate' "$OUT/share.log" && record "share: second share is a duplicate" PASS "" || record "share: second share is a duplicate" FAIL "see $OUT/share.log"
record "share: status" INFO "$(grep 'status pending' "$OUT/share.log" | tail -1 | sed 's/.*status //')"
$ADB shell input keyevent KEYCODE_HOME

echo "== 4. Spike Worker with the minimal schema (5 segments)"
$ADB logcat -c
$ADB shell am start -S -n com.sebrain.diag/.MainActivity --es mode cognition --es batches 5 > /dev/null 2>&1
i=0
while [ $i -lt 100 ]; do
    $ADB logcat -d -s SEBRAIN_DIAG:I | grep -q -E 'ECDH % of total|\|FAIL\|' && break
    sleep 3; i=$((i + 1))
done
$ADB logcat -d -s SEBRAIN_DIAG:I > "$OUT/spike.log"
LINE=$(grep 'ECDH % of total' "$OUT/spike.log" | sed 's/.*; valid JSON/valid JSON/')
[ -n "$LINE" ] && record "spike minimal schema" INFO "$LINE" || record "spike minimal schema" FAIL "see $OUT/spike.log"
$ADB shell am force-stop com.sebrain.diag

echo "== 5. Phases before the lock"
phase "vault: create" $VAULT "$VAULT_CLASS#phaseCreate" create
$ADB shell am force-stop $VAULT
phase "vault: reopen after process kill" $VAULT "$VAULT_CLASS#phaseReopenAfterKill" reopen
phase "pipeline: prepare" $APP "$APP_CLASS#phasePrepare" prepare
if locked; then record "lock precondition" FAIL "the device locked before the lock phase"; fi

echo "== 6. Lock"
$ADB shell svc power stayon false
$ADB logcat -c
instrument $VAULT "$VAULT_CLASS#phaseClosesOnLockAndCannotOpen" locked > "$OUT/vault-lock.log" &
PHASE_PID=$!
i=0
until $ADB logcat -d -s SEBRAIN_VAULT_PHASE:I | grep -q READY_FOR_SCREEN_OFF; do
    sleep 1; i=$((i + 1)); [ $i -gt 60 ] && break
done
$ADB shell input keyevent KEYCODE_SLEEP
wait $PHASE_PID
grep -q '^OK (1 test)' "$OUT/vault-lock.log" && record "vault: closes on lock and refuses while locked" PASS "" \
    || record "vault: closes on lock and refuses while locked" FAIL "see $OUT/vault-lock.log"

echo "== 7. Locked phases"
if locked; then
    $ADB shell am force-stop com.sebrain.app
    phase "pipeline: locked" $APP "$APP_CLASS#phaseLocked" locked
    $ADB logcat -d -s SEBRAIN_APP_PHASE:I | sed 's/.*SEBRAIN_APP_PHASE: /    /' | tee -a "$OUT/locked-notes.log"
    locked || record "lock held" FAIL "the device was unlocked during the locked phases"
else
    record "pipeline: locked" INVALID "the device did not stay locked"
fi

echo "== 8. Waiting for the unlock (the screen turns on as the signal)"
$ADB shell input keyevent KEYCODE_WAKEUP
i=0
while locked; do
    sleep 2; i=$((i + 1))
    if [ $i -gt 600 ]; then record "unlock" FAIL "not unlocked within 20 minutes"; break; fi
done

echo "== 9. Phases after the unlock"
if ! locked; then
    sleep 2
    $ADB shell am force-stop com.sebrain.app
    phase "pipeline: import after unlock" $APP "$APP_CLASS#phaseAfterUnlock" afterUnlock
    phase "vault: reopen after unlock" $VAULT "$VAULT_CLASS#phaseAfterUnlock" afterUnlock
fi

echo
echo "== SUMMARY"
cat "$SUMMARY"
grep -q -E '\| (FAIL|INVALID) \|' "$SUMMARY" && exit 1
exit 0
