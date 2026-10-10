#!/bin/bash
# Gunfight Android build without Gradle: aapt2 -> kotlinc -> d8 -> zipalign -> apksigner
#
# Required (override with env vars):
#   ANDROID_HOME/ANDROID_SDK_ROOT   Android SDK (default ~/android-sdk)
#   KOTLINC                         kotlinc launcher (default: kotlinc on PATH)
#   JAVA_HOME                       JDK 17 (default: java on PATH)
# Either a kotlinc launcher or KOTLIN_COMPILER_JAR (kotlin-compiler-embeddable) is enough.
set -e

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$PROJ/app"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
BT="${BUILD_TOOLS_VERSION:-34.0.0}"
PLATFORM="${ANDROID_PLATFORM:-android-34}"
APP_ID="${APP_ID:-com.gunfight.client}"
VERSION_CODE="${VERSION_CODE:-4}"
VERSION_NAME="${VERSION_NAME:-0.4-phase2}"
BUILD="$PROJ/build-apk"
OUT_APK="${OUT_APK:-$PROJ/dist/GunfightSpectate.apk}"

ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
AAPT2="$SDK/build-tools/$BT/aapt2"
D8="$SDK/build-tools/$BT/d8"
ZIPALIGN="$SDK/build-tools/$BT/zipalign"
APKSIGNER="$SDK/build-tools/$BT/apksigner"
AAPT="$SDK/build-tools/$BT/aapt"

if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; KEYTOOL="$JAVA_HOME/bin/keytool"; else JAVA="java"; KEYTOOL="keytool"; fi
KOTLINC="${KOTLINC:-$(command -v kotlinc || true)}"
KC_JAR="${KOTLIN_COMPILER_JAR:-$HOME/toolchain/kotlin-compiler-embeddable-1.9.24.jar}"
KOTLIN_CP="${KOTLIN_CP:-$(ls "$HOME"/toolchain/kotlin-stdlib-*.jar "$HOME"/toolchain/kotlin-reflect-*.jar "$HOME"/toolchain/kotlin-script-runtime-*.jar "$HOME"/toolchain/trove4j-*.jar "$HOME"/toolchain/annotations-*.jar 2>/dev/null | paste -sd:)}"
STDLIB="${KOTLIN_STDLIB:-$(ls "$HOME"/toolchain/kotlin-stdlib-*.jar 2>/dev/null | head -1)}"

echo "=== [1/6] toolchain check ==="
missing=0
for f in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  [ -f "$f" ] || { echo "missing: $f"; missing=1; }
done
if [ -z "$KOTLINC" ] && [ ! -f "$KC_JAR" ]; then
  echo "missing: neither kotlinc on PATH nor KOTLIN_COMPILER_JAR ($KC_JAR)"; missing=1
fi
[ -n "$STDLIB" ] || { echo "missing: kotlin-stdlib jar (set KOTLIN_STDLIB)"; missing=1; }
[ "$missing" = 0 ] || { echo "install the Android SDK (sdkmanager \"platforms;$PLATFORM\" \"build-tools;$BT\") and Kotlin, or set ANDROID_HOME/KOTLINC"; exit 1; }
echo "SDK: $SDK   kotlinc: ${KOTLINC:-embeddable}"

echo "=== [2/6] aapt2 link (manifest + assets) ==="
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
# AGP 8 takes the package from build.gradle.kts namespace; aapt2 still needs it in the manifest.
sed "s#<manifest #<manifest package=\"$APP_ID\" #" "$APP/src/main/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
"$AAPT2" link -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  -A "$APP/src/main/assets" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME"

echo "=== [3/6] kotlinc compile ==="
SOURCES=$(find "$PROJ/protocol/src/main/kotlin" "$APP/src/main/kotlin" -name '*.kt')
if [ -n "$KOTLINC" ]; then
  "$KOTLINC" -no-stdlib -jvm-target 17 -cp "$ANDROID_JAR:$STDLIB" -d "$BUILD/classes" $SOURCES
else
  "$JAVA" -Xmx1g -cp "$KC_JAR:$KOTLIN_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -nowarn -jvm-target 17 -cp "$ANDROID_JAR:$STDLIB" -d "$BUILD/classes" $SOURCES
fi

echo "=== [4/6] d8 dex ==="
(cd "$BUILD/classes" && jar cf "$BUILD/classes.jar" .)
"$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar" "$STDLIB" 2>&1 | grep -v '^$' | head -3 || true

echo "=== [5/6] package + align ==="
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/unsigned.apk" classes.dex)
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "=== [6/6] sign ==="
KEYSTORE="${KEYSTORE:-$PROJ/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/signed.apk" "$BUILD/aligned.apk"

mkdir -p "$(dirname "$OUT_APK")"
cp -f "$BUILD/signed.apk" "$OUT_APK"
ls -lh "$OUT_APK"
[ -x "$AAPT" ] && "$AAPT" dump badging "$OUT_APK" | head -6
echo "build ok: $OUT_APK"
