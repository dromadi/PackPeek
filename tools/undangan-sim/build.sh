#!/bin/sh
# Builds undangan-sim.apk (test-only) with javac, d8 and aapt2, and signs it with the debug key.
#
# The APK contains code (classes.dex) for PackPeek's code analysis to find, but the manifest says
# android:hasCode="false", so Android never loads or runs it even if the APK is installed. A copy
# of the DEX is stored as assets/payload.bin to imitate a hidden payload.
# Usage: ANDROID_HOME=/path/to/sdk ./build.sh
set -e
cd "$(dirname "$0")"
BT="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-36/android.jar"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/pack/assets"
javac -source 8 -target 8 -nowarn -cp "$JAR" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --min-api 24 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$BT/aapt2" compile --dir res -o "$OUT/compiled"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/unsigned.apk" "$OUT"/compiled/*.flat
cp "$OUT/dex/classes.dex" "$OUT/pack/classes.dex"
cp "$OUT/dex/classes.dex" "$OUT/pack/assets/payload.bin"
(cd "$OUT/pack" && zip -q -X ../unsigned.apk classes.dex assets/payload.bin)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "${KEYSTORE:-$HOME/.android/debug.keystore}" --ks-pass pass:android \
    --out undangan-sim.apk "$OUT/aligned.apk"
rm -rf "$OUT" undangan-sim.apk.idsig
echo "Built $(pwd)/undangan-sim.apk"
