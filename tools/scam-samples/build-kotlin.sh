#!/bin/sh
# Builds the Kotlin test fixtures in kotlin/<name>/ (coroutines need the real Kotlin compiler's
# output to be meaningful). Every APK is INERT: hasCode="false"; the DEX is only for static analysis.
#
# Usage: ANDROID_HOME=/path/to/sdk ./build-kotlin.sh   ->  dist/<name>.apk
# Uses the Kotlin compiler and libraries from the Gradle cache of this project's build.
set -e
cd "$(dirname "$0")"
BT="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-36/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
C="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
pick() { ls $C/$1 2>/dev/null | head -1; }
STDLIB=$(pick "org.jetbrains.kotlin/kotlin-stdlib/2.2.0/*/kotlin-stdlib-2.2.0.jar")
COROUTINES=$(pick "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/*/kotlinx-coroutines-core-jvm-1.9.0.jar")
COMPILER="$(pick "org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21/*/*.jar"):$STDLIB:$(pick "org.jetbrains.kotlin/kotlin-script-runtime/2.2.21/*/*.jar"):$(pick "org.jetbrains.kotlin/kotlin-reflect/2.2.0/*/*.jar"):$(pick "org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.21/*/*.jar"):$(pick "org.jetbrains/annotations/13.0/*/*.jar"):$(pick "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar")"
mkdir -p dist
for dir in kotlin/*/; do
    name=$(basename "$dir")
    out="build/$name"
    rm -rf "$out" && mkdir -p "$out/classes" "$out/dex" "$out/pack"
    java -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 \
        -cp "$JAR:$STDLIB:$COROUTINES" -d "$out/classes" "$dir/src"
    "$BT/d8" --min-api 24 --lib "$JAR" --classpath "$STDLIB" --classpath "$COROUTINES" \
        --output "$out/dex" $(find "$out/classes" -name '*.class')
    "$BT/aapt2" link -I "$JAR" --manifest "$dir/AndroidManifest.xml" -o "$out/unsigned.apk"
    cp "$out/dex/classes.dex" "$out/pack/classes.dex"
    (cd "$out/pack" && zip -q -X ../unsigned.apk classes.dex)
    "$BT/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --out "dist/$name.apk" "$out/aligned.apk"
    rm -f "dist/$name.apk.idsig"
    echo "  built dist/$name.apk"
done
rm -rf build
