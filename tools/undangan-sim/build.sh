#!/bin/sh
# Builds undangan-sim.apk (no code, test-only) with aapt2 and signs it with the debug key.
# Usage: ANDROID_HOME=/path/to/sdk ./build.sh
set -e
cd "$(dirname "$0")"
BT="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-36/android.jar"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/compiled"
"$BT/aapt2" compile --dir res -o "$OUT/compiled"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/unsigned.apk" "$OUT"/compiled/*.flat
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "${KEYSTORE:-$HOME/.android/debug.keystore}" --ks-pass pass:android \
    --out undangan-sim.apk "$OUT/aligned.apk"
rm -rf "$OUT"
echo "Built $(pwd)/undangan-sim.apk"
