#!/bin/bash
# PackPeek end-to-end scenario on a running emulator. Used by .github/workflows/emulator.yml and
# usable locally with tools/emulator/emu.sh start.
#
# Usage: scenario.sh <debug.apk> <undangan-sim.apk> <output dir>
# Writes numbered screenshots, logcat and SUMMARY.md (steps with pass/fail) to <output dir>.
# Exits non-zero if PackPeek crashed or a required step failed.
set -uo pipefail

APK="$1"
SAMPLE="$2"
OUT="$3"
PKG=com.packpeek.app.debug
HERE="$(cd "$(dirname "$0")" && pwd)"
E="$HERE/emu.sh"
ADB="$ANDROID_HOME/platform-tools/adb"
mkdir -p "$OUT"
SUMMARY="$OUT/SUMMARY.md"
n=0
failed=0

api="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
release="$("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
{
    echo "# PackPeek emulator run"
    echo
    echo "Android $release (API $api) · $(date -u '+%Y-%m-%d %H:%M UTC')"
    echo
    echo "| # | Step | Result |"
    echo "|---|---|---|"
} >"$SUMMARY"

shot() {
    n=$((n + 1))
    local file
    file="$(printf '%02d' "$n")-$1.png"
    "$E" shot "$OUT/$file" >/dev/null
    printf '\n### %s. %s\n\n![%s](%s)\n' "$n" "$2" "$2" "$file" >>"$OUT/SCREENS.md"
}

# step <name> <required: yes|no> <command...>
step() {
    local name="$1" required="$2"
    shift 2
    if "$@" >>"$OUT/steps.log" 2>&1; then
        echo "| | $name | ✅ |" >>"$SUMMARY"
        return 0
    fi
    if [ "$required" = yes ]; then
        echo "| | $name | ❌ |" >>"$SUMMARY"
        failed=1
    else
        echo "| | $name | ⚠️ skipped |" >>"$SUMMARY"
    fi
    return 1
}

: >"$OUT/SCREENS.md"
"$ADB" logcat -c || true

step "Install PackPeek" yes "$E" install "$APK"
step "Copy undangan-sim.apk to Download" yes "$ADB" push "$SAMPLE" /sdcard/Download/undangan-sim.apk
# Make the file visible to the system file picker.
"$ADB" shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1 ||
    "$ADB" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/undangan-sim.apk >/dev/null 2>&1 || true
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

step "Launch" yes "$E" launch "$PKG"
step "Onboarding shown" no "$E" wait "Skip" 180 && shot onboarding "Onboarding" && "$E" tap "Skip"
step "App list loads" yes "$E" wait "Search apps" 180
shot apps "App list"

step "Open Overview" yes "$E" tap "Overview"
step "Check card shown" yes "$E" wait "Check your apps" 60
shot overview "Overview before the check"
if step "Start the app check" yes "$E" tapre 'Check [0-9]+ apps?'; then
    step "Check finishes" yes "$E" gone "Checking" 600
    shot overview-checked "Overview after the check"
    "$E" scroll
    shot overview-review "Needs review and analysis"
    "$E" scroll
    shot overview-analysis "App analysis"
fi

# Inspect the harmless sample through the system file picker (best effort: pickers differ).
step "Back to the app list" no "$E" tap "Apps"
if step "Open the file picker" no "$E" tap "Inspect file"; then
    sleep 3
    shot picker "File picker"
    "$E" tap "undangan-sim" >/dev/null 2>&1 || { "$E" tap "Downloads" >/dev/null 2>&1; sleep 2; "$E" tap "undangan-sim" >/dev/null 2>&1; } || true
    if step "Sample analysed" no "$E" wait "OTP theft" 180; then
        shot sample "undangan-sim: concerns"
        "$E" scroll
        shot sample-evidence "undangan-sim: evidence"
        "$E" scroll
        shot sample-more "undangan-sim: more evidence"
    fi
fi

"$ADB" logcat -d >"$OUT/logcat.txt" 2>/dev/null || true
"$ADB" logcat -d -b crash >"$OUT/crash.txt" 2>/dev/null || true
if grep -q "Process: $PKG" "$OUT/crash.txt" 2>/dev/null; then
    echo "| | No crash | ❌ (see crash.txt) |" >>"$SUMMARY"
    failed=1
else
    echo "| | No crash | ✅ |" >>"$SUMMARY"
fi

# Number the steps and append the screenshots so one page shows everything.
awk 'BEGIN{i=0} /^\| \| /{i++; sub(/^\| \| /, "| " i " | ")} {print}' "$SUMMARY" >"$SUMMARY.tmp" && mv "$SUMMARY.tmp" "$SUMMARY"
{
    echo
    echo "## Screenshots"
    cat "$OUT/SCREENS.md"
} >>"$SUMMARY"
rm -f "$OUT/SCREENS.md"
echo "summary: $SUMMARY (failed=$failed)"
exit $failed
