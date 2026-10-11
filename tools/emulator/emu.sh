#!/bin/bash
# Faster emulator testing on machines without hardware acceleration (no /dev/kvm).
#
# Without KVM the emulator emulates the CPU in software and is 10-20x slower than a phone, so the
# usual pattern of "sleep N; screenshot; tap x,y" wastes minutes and misses taps. This script:
#   - boots from a saved snapshot after the first cold boot (seconds instead of minutes),
#   - turns animations off and closes "isn't responding" dialogs automatically,
#   - waits for text on screen instead of sleeping,
#   - taps elements by their text instead of by coordinates.
#
# Usage (ANDROID_HOME must point to the SDK):
#   emu.sh start [avd]            boot (snapshot "ready" when it exists), prepare the device
#   emu.sh save                   save the current state as snapshot "ready"
#   emu.sh stop                   shut the emulator down
#   emu.sh install <apk>          install or update an APK
#   emu.sh launch <package>       start an app's launcher activity
#   emu.sh wait <text> [secs]     wait until <text> is on screen (default 120 s)
#   emu.sh gone <text> [secs]     wait until <text> is no longer on screen
#   emu.sh tap <text>             tap the first element whose text or description contains <text>
#   emu.sh tapre <regex>          tap the first element whose whole text matches <regex>
#   emu.sh scroll                 scroll the screen down by about two thirds
#   emu.sh scrollup               scroll the screen up by about two thirds
#   emu.sh shot <file.png>        save a screenshot
#   emu.sh texts                  print the texts on screen (quick look without a screenshot)
#
# For checking layouts, prefer the JVM renders, which need no emulator at all:
#   ./gradlew :app:testDebugUnitTest --tests '*ScreenRenderTest*'   -> app/build/screenshots/*.png
set -euo pipefail

SDK="${ANDROID_HOME:?set ANDROID_HOME}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
LOG="${TMPDIR:-/tmp}/packpeek-emulator.log"
DUMP="/sdcard/packpeek-ui.xml"

ui() {
    # uiautomator fails while the screen animates; retry briefly.
    for _ in 1 2 3; do
        if "$ADB" shell uiautomator dump "$DUMP" >/dev/null 2>&1; then
            "$ADB" shell cat "$DUMP" 2>/dev/null && return 0
        fi
        sleep 1
    done
    return 1
}

dismiss_anr() {
    # System UI and apps stall under software emulation; choose "Wait" so testing continues.
    if ui | grep -q "isn't responding"; then
        tap_text "Wait" >/dev/null 2>&1 || true
    fi
}

tap_text() {
    local wanted="$1" xml node bounds
    xml="$(ui)" || return 1
    node="$(printf '%s' "$xml" | tr '>' '\n' | grep -F -e "text=\"$wanted" -e "content-desc=\"$wanted" | head -1)"
    [ -z "$node" ] && node="$(printf '%s' "$xml" | tr '>' '\n' | grep -F "$wanted" | head -1)"
    [ -z "$node" ] && { echo "not on screen: $wanted" >&2; return 1; }
    bounds="$(printf '%s' "$node" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ')"
    read -r x1 y1 x2 y2 <<<"$bounds"
    "$ADB" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
    echo "tapped '$wanted' at $(((x1 + x2) / 2)),$(((y1 + y2) / 2))"
}

tap_regex() {
    local pattern="$1" xml node bounds
    xml="$(ui)" || return 1
    node="$(printf '%s' "$xml" | tr '>' '\n' | grep -E "text=\"$pattern\"" | head -1)"
    [ -z "$node" ] && { echo "not on screen: /$pattern/" >&2; return 1; }
    bounds="$(printf '%s' "$node" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ')"
    read -r x1 y1 x2 y2 <<<"$bounds"
    "$ADB" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
    echo "tapped /$pattern/ at $(((x1 + x2) / 2)),$(((y1 + y2) / 2))"
}

scroll() {
    local size w h
    size="$("$ADB" shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)"
    w="${size%x*}"
    h="${size#*x}"
    "$ADB" shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 400
}

scroll_up() {
    local size w h
    size="$("$ADB" shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)"
    w="${size%x*}"
    h="${size#*x}"
    "$ADB" shell input swipe $((w / 2)) $((h / 4)) $((w / 2)) $((h * 3 / 4)) 300
}

wait_for() {
    local text="$1" seconds="${2:-120}" present="$3" end=$((SECONDS + ${2:-120}))
    while [ $SECONDS -lt $end ]; do
        dismiss_anr
        if ui | grep -qF "$text"; then
            [ "$present" = yes ] && { echo "found: $text"; return 0; }
        else
            [ "$present" = no ] && { echo "gone: $text"; return 0; }
        fi
        sleep 2
    done
    echo "timeout after ${seconds}s waiting for '$text' ($present)" >&2
    return 1
}

prepare() {
    for key in window_animation_scale transition_animation_scale animator_duration_scale; do
        "$ADB" shell settings put global "$key" 0
    done
    "$ADB" shell settings put system screen_off_timeout 1800000
    "$ADB" shell svc power stayon true
    "$ADB" shell input keyevent 82 # unlock
}

case "${1:-}" in
    start)
        avd="${2:-t26}"
        args=(-avd "$avd" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 2048 -cores 4)
        [ -e /dev/kvm ] || args+=(-accel off)
        if [ -d "${ANDROID_AVD_HOME:-$HOME/.android/avd}/$avd.avd/snapshots/ready" ]; then
            args+=(-snapshot ready -no-snapshot-save)
        fi
        nohup "$EMULATOR" "${args[@]}" >"$LOG" 2>&1 &
        "$ADB" wait-for-device
        until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
        prepare
        echo "ready (log: $LOG)"
        ;;
    save) "$ADB" emu avd snapshot save ready && echo "snapshot 'ready' saved; next start boots from it" ;;
    stop) "$ADB" emu kill || true ;;
    install) "$ADB" install -r "$2" | tail -1 ;;
    launch) "$ADB" shell monkey -p "$2" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && echo "launched $2" ;;
    wait) wait_for "$2" "${3:-120}" yes ;;
    gone) wait_for "$2" "${3:-120}" no ;;
    tap) dismiss_anr; tap_text "$2" ;;
    tapre) dismiss_anr; tap_regex "$2" ;;
    scroll) scroll ;;
    scrollup) scroll_up ;;
    shot) "$ADB" exec-out screencap -p >"$2" && echo "saved $2" ;;
    texts) ui | grep -o 'text="[^"]\+"' | sed 's/text="//; s/"$//' ;;
    *) sed -n '2,27p' "$0"; exit 1 ;;
esac
