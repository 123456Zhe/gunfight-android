#!/bin/bash
# gunfight-android 手动工具链构建脚本（Gradle 在本机跑不起来，用 aapt2+kotlinc+d8 直打）
# 流程：aapt2 link -> kotlinc -> d8 -> zipalign -> apksigner
set -e

PROJ="$HOME/workspace/gunfight-android"
APP="$PROJ/app"
SDK="$HOME/android-sdk"
BT="$SDK/build-tools/34.0.0"
BUILD="$PROJ/build-apk"
OUT_APK="$HOME/workspace/your_files/GunfightSpectate.apk"

ANDROID_JAR="$SDK/platforms/android-34/android.jar"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
AAPT="$BT/aapt"
STDLIB="$HOME/kotlin-compiler/lib/kotlin-stdlib.jar"
KOTLINC="$HOME/kotlin-compiler/bin/kotlinc"

echo "=== [1/6] 检查工具 ==="
for f in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$STDLIB"; do
  [ -f "$f" ] || { echo "缺失: $f"; exit 1; }
done
echo "工具齐全"

echo "=== [2/6] aapt2 link（manifest + assets） ==="
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
"$AAPT2" link -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  -A "$APP/src/main/assets" \
  --min-sdk-version 26 \
  --target-sdk-version 34

echo "=== [3/6] kotlinc 编译 ==="
"$KOTLINC" \
  -no-stdlib \
  -jvm-target 17 \
  -cp "$ANDROID_JAR:$STDLIB" \
  -d "$BUILD/classes" \
  $(find "$PROJ/protocol/src/main/kotlin" "$APP/src/main/kotlin" -name "*.kt")

echo "=== [4/6] d8 dex ==="
(cd "$BUILD/classes" && jar cf "$BUILD/classes.jar" .)
"$D8" --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$BUILD/dex" \
  "$BUILD/classes.jar" "$STDLIB" 2>&1 | grep -v "^$" | head -3 || true

echo "=== [5/6] 合包 + 对齐 ==="
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/unsigned.apk" classes.dex)
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "=== [6/6] 签名 ==="
KEYSTORE="$PROJ/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android \
  --out "$BUILD/signed.apk" "$BUILD/aligned.apk"

mkdir -p "$(dirname "$OUT_APK")"
cp -f "$BUILD/signed.apk" "$OUT_APK"
ls -lh "$OUT_APK"
"$AAPT" dump badging "$OUT_APK" | head -6
echo "构建完成: $OUT_APK"
