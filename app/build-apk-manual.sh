#!/bin/bash
# Manual APK build (no Gradle daemon) for WeLiveThisWay Bots (unified bot app).
#
# Why this exists: Gradle builds its APK by talking to a Gradle daemon
# over a loopback TCP connection. In this VM raw TCP (including
# loopback) is intercepted by the runtime network sandbox, so the
# Gradle client can never reach its daemon ("Could not receive a
# message from the daemon"). The Gradle project files (build.gradle
# etc.) are still in this folder and are the canonical project — on a
# normal machine `gradle assembleDebug` builds the same app.
#
# This script builds the identical APK with the SDK command-line tools
# only: aapt2 -> javac (R.java) -> kotlinc -> d8 -> zipalign -> apksigner.
# No network needed. Kotlin comes from the Kotlin compiler embedded in
# the Gradle 8.5 distribution already in ~/workspace/tools.
#
# Usage:  ./build-apk-manual.sh
# Output: ../WeLiveThisWay-Bots.apk
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$(dirname "$ROOT")/WeLiveThisWay-Bots.apk"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
export JAVA_HOME="$HOME/workspace/tools/jdk-17.0.21+9"
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE_LIB="$HOME/workspace/tools/gradle-8.5/lib"
STDLIB="$ROOT/libs/kotlin-stdlib-1.9.20.jar"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
BUILD="$ROOT/build-manual"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/kt-classes" "$BUILD/dex"

echo "== 1. Compile resources (aapt2 compile)"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/compiled.zip"

echo "== 2. Link APK + generate R.java (aapt2 link)"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -R "$BUILD/compiled.zip" --java "$BUILD/gen" --auto-add-overlay

echo "== 3. Compile R.java (javac)"
find "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== 4. Compile Kotlin (kotlinc)"
find "$ROOT/app/src/main/java" -name '*.kt' > "$BUILD/kotlin-sources.txt"
java -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 \
  -cp "$ANDROID_JAR:$STDLIB:$BUILD/classes" \
  -d "$BUILD/kt-classes" @"$BUILD/kotlin-sources.txt"

echo "== 5. Dex (d8)"
# Jar the class dirs first: d8 rejects a directory holding non-class files
# (kotlinc's META-INF/*.kotlin_module), but ignores them inside a jar.
(cd "$BUILD/classes" && jar --create --file ../classes.jar .)
(cd "$BUILD/kt-classes" && jar --create --file ../kotlin-classes.jar .)
"$BT/d8" --output "$BUILD/dex" \
  "$BUILD/classes.jar" "$BUILD/kotlin-classes.jar" "$STDLIB" \
  --lib "$ANDROID_JAR"

echo "== 6. Add classes.dex to APK"
(cd "$BUILD/dex" && zip -q ../app-unsigned.apk classes.dex)

echo "== 7. Align (zipalign)"
"$BT/zipalign" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "== 8. Sign (apksigner, debug key)"
if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "   (generated debug.keystore — debug key only, do not use for Play Store releases)"
fi
"$BT/apksigner" sign --ks "$ROOT/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$BUILD/app-aligned.apk"

echo "== 9. Verify"
"$BT/apksigner" verify "$OUT"
"$BT/aapt" dump badging "$OUT" | head -5
ls -lh "$OUT"
echo "DONE: $OUT"
