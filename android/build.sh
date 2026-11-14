#!/usr/bin/env bash
# boringmech Android build — aapt2 pipeline, no Gradle.
# Requires: JDK 17, Android SDK platform 34 + build-tools 34.0.0 (ANDROID_HOME).
set -euo pipefail

cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
JAR="$ANDROID_HOME/platforms/android-34/android.jar"
OUT=build
APP=../app/index.html
APK=boringmech-v0.3.7.apk
KS=keystore.jks
KS_PASS="${KEYSTORE_PASS:-boringmech}"

rm -rf "$OUT"
mkdir -p "$OUT/assets" "$OUT/gen" "$OUT/classes" "$OUT/dex"
cp "$APP" "$OUT/assets/index.html"
cp ../app/engine.js "$OUT/assets/engine.js"

"$BT/aapt2" link \
  -o "$OUT/unsigned.apk" \
  -I "$JAR" \
  --manifest AndroidManifest.xml \
  -A "$OUT/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 10 --version-name 0.3.7

find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"
"$BT/d8" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/unsigned.apk" "$OUT/with-dex.apk"
(cd "$OUT/dex" && zip -q ../with-dex.apk classes.dex)

"$BT/zipalign" -f 4 "$OUT/with-dex.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias boringmech -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=boringmech, O=roomedia" > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -3
echo "built: $APK"
