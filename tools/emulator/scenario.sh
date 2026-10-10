#!/bin/bash
# PackPeek end-to-end scenario on a running emulator. Used by .github/workflows/emulator.yml and
# usable locally with tools/emulator/emu.sh start.
#
# Usage: scenario.sh <debug.apk> <undangan-sim.apk> <output dir> [control-clean.apk]
# The optional control APK (inert, hasCode=false) is installed through PackPeek to test installing.
# Writes numbered screenshots, logcat and SUMMARY.md (steps with pass/fail) to <output dir>.
# Exits non-zero if PackPeek crashed or a required step failed.
set -uo pipefail

APK="$1"
SAMPLE="$2"
OUT="$3"
CONTROL="${4:-}"
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
step "App list loads" yes "$E" wait "Select all results" 240
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

# Back to PackPeek's app list (the start destination) from wherever the previous step ended.
to_app_list() {
    for _ in 1 2 3 4 5 6; do
        "$E" texts 2>/dev/null | grep -q "Select all results" && return 0
        if "$ADB" shell dumpsys window | grep mCurrentFocus | grep -q "$PKG"; then
            "$ADB" shell input keyevent 4
        else
            "$E" launch "$PKG" >/dev/null
        fi
        sleep 3
    done
    "$E" wait "Select all results" 60 >/dev/null 2>&1
}

# Open a file in PackPeek: first through the system file picker like a user, then, when the
# picker layout is not recognised, through a VIEW intent on PackPeek's own FileProvider (the same
# import path the picker leads to). The summary says which way was used.
open_file() {
    local file="$1" name="${1%.apk}"
    to_app_list
    if "$E" tap "Inspect file" >>"$OUT/steps.log" 2>&1; then
        sleep 4
        "$E" tap "$name" >>"$OUT/steps.log" 2>&1 ||
            { "$E" tap "Show roots" && sleep 2 && "$E" tap "Downloads" && sleep 3 && "$E" tap "$name"; } >>"$OUT/steps.log" 2>&1 ||
            { "$E" tap "Download" && sleep 3 && "$E" tap "$name"; } >>"$OUT/steps.log" 2>&1
        if "$E" wait "Install backup" 120 >>"$OUT/steps.log" 2>&1; then
            echo "| | Open $file with the file picker | ✅ |" >>"$SUMMARY"
            return 0
        fi
        "$ADB" shell input keyevent 4
    fi
    "$ADB" shell "chmod 644 /data/local/tmp/$file"
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p cache/shared && cp /data/local/tmp/$file cache/shared/$file'"
    "$ADB" shell am start -a android.intent.action.VIEW -t application/vnd.android.package-archive \
        -d "content://$PKG.fileprovider/shared/$file" -p "$PKG" >>"$OUT/steps.log" 2>&1
    if "$E" wait "Install backup" 120 >>"$OUT/steps.log" 2>&1; then
        echo "| | Open $file (picker not recognised, opened by VIEW intent) | ✅ |" >>"$SUMMARY"
        return 0
    fi
    echo "| | Open $file | ❌ |" >>"$SUMMARY"
    failed=1
    return 1
}

"$ADB" push "$SAMPLE" /data/local/tmp/undangan-sim.apk >/dev/null
if open_file undangan-sim.apk; then
    step "Sample analysed" yes "$E" wait "OTP theft" 180
    shot sample "undangan-sim: concerns"
    "$E" scroll
    shot sample-evidence "undangan-sim: evidence"
    # Deep analysis: the button sits under the concern summary.
    for _ in 1 2 3 4; do "$E" tap "Deep analysis" >/dev/null 2>&1 && break; "$E" scroll; done
    if step "Deep analysis finishes" yes "$E" wait "Finished in" 300; then
        shot deep "Deep analysis"
        # Evidence report: the PDF is written, then handed to the share sheet. The shortcut sits
        # under the summary; the full section is at the bottom.
        "$E" tap "Share evidence report" >>"$OUT/steps.log" 2>&1 ||
            for _ in $(seq 30); do "$E" tap "Share as PDF" >/dev/null 2>&1 && break; "$E" scroll; done
        sleep 5
        shot report-tapped "After tapping the report button"
        if step "Report PDF reaches the share sheet" yes sh -c "'$ADB' shell dumpsys activity activities | grep -q -E 'ChooserActivity|ResolverActivity|intentresolver'"; then
            "$ADB" shell input keyevent 4
            sleep 2
        fi
        for k in 1 2 3; do "$E" scroll; shot "deep-$k" "Deep analysis, part $k"; done
    fi
fi

# Installing a file: the inert control APK through PackPeek and the system installer.
if [ -n "$CONTROL" ]; then
    "$ADB" shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
    "$ADB" push "$CONTROL" /data/local/tmp/control-clean.apk >/dev/null
    "$ADB" push "$CONTROL" /sdcard/Download/control-clean.apk >/dev/null
    "$ADB" shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1 || true
    if open_file control-clean.apk; then
        shot control "control-clean: ready to install"
        "$E" tap "Install backup" >>"$OUT/steps.log" 2>&1
        sleep 8
        shot install-tapped "After tapping Install backup"
        # The system asks for confirmation; its button is "Install" (or "Update").
        if step "System install prompt" yes sh -c "for i in \$(seq 30); do '$ADB' shell dumpsys window | grep mCurrentFocus | grep -qi packageinstaller && exit 0; sleep 2; done; exit 1"; then
            shot install-prompt "System install prompt"
            "$E" tapre 'Install|INSTALL|Update|UPDATE' >>"$OUT/steps.log" 2>&1
            step "PackPeek shows the install result" yes "$E" wait "Installation completed" 180
            shot install-result "Install result"
            step "Control app is installed" yes sh -c "'$ADB' shell pm list packages | grep -q sim.app.notes"
        fi
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
