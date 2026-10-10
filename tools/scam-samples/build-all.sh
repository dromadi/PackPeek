#!/bin/sh
# Builds one test APK per scam modus from samples/ (run generate.py first). Every APK is INERT:
# the manifest declares hasCode="false", so Android never runs the code; endpoints and tokens are
# fake. The DEX is there only for PackPeek's static analysis.
#
# Usage: ANDROID_HOME=/path/to/sdk ./build-all.sh   ->  dist/<name>.apk
set -e
cd "$(dirname "$0")"
BT="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-36/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
python3 generate.py
rm -rf dist && mkdir -p dist

for dir in samples/*/; do
    name=$(basename "$dir")
    out="build/$name"
    rm -rf "$out" && mkdir -p "$out/compiled" "$out/classes" "$out/dex" "$out/pack/assets"
    javac -source 8 -target 8 -nowarn -cp "$JAR" -d "$out/classes" $(find "$dir/src" -name '*.java')
    "$BT/d8" --min-api 24 --lib "$JAR" --output "$out/dex" $(find "$out/classes" -name '*.class')
    "$BT/aapt2" compile --dir "$dir/res" -o "$out/compiled"
    "$BT/aapt2" link -I "$JAR" --manifest "$dir/AndroidManifest.xml" -o "$out/unsigned.apk" "$out"/compiled/*.flat
    cp "$out/dex/classes.dex" "$out/pack/classes.dex"
    files="classes.dex"
    if [ -d "$dir/payload" ]; then
        rm -rf "$out/pc" "$out/pd" && mkdir -p "$out/pc" "$out/pd"
        javac -source 8 -target 8 -nowarn -cp "$JAR" -d "$out/pc" $(find "$dir/payload" -name '*.java')
        "$BT/d8" --min-api 24 --lib "$JAR" --output "$out/pd" $(find "$out/pc" -name '*.class')
        cp "$out/pd/classes.dex" "$out/pack/assets/payload.bin"
        head -c 32768 /dev/urandom > "$out/pack/assets/config.dat"
        files="$files assets/payload.bin assets/config.dat"
    fi
    (cd "$out/pack" && zip -q -X ../unsigned.apk $files)
    "$BT/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --out "dist/$name.apk" "$out/aligned.apk"
    rm -f "dist/$name.apk.idsig"
    echo "  built dist/$name.apk"
done
rm -rf build
echo "Done. $(ls dist | wc -l) sample APKs in $(pwd)/dist"
