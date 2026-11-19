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
APK=boringmech-v0.3.34.apk
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
  --version-code 37 --version-name 0.3.34

find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$JAR:libs/bcprov-jdk18on-1.78.1.jar" -d "$OUT/classes" @"$OUT/sources.txt"
# R8로 축소·dexing한다 (Bouncy Castle 전체를 넣으면 dex가 4.4MB가 된다.
# keep.pro로 앱 패키지 이름·멤버를 보존하고 나머지는 도달 가능성으로 줄인다.)
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 \
  --lib "$JAR" --pg-conf keep.pro --pg-map-output "$OUT/mapping.txt" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') libs/bcprov-jdk18on-1.78.1.jar
cp "$OUT/unsigned.apk" "$OUT/with-dex.apk"
(cd "$OUT/dex" && zip -q ../with-dex.apk classes.dex)

# 출하 dex 게이트 — 서명보다 먼저 돌린다. R8 축소가 만든 구조 결함
# (원시 타입 instance-of, 쓰기 경로 클래스 소실, JavascriptInterface
# 누락)이 있으면 여기서 빌드가 중단되고, 게이트를 통과하지 못한
# 서명 APK가 출하본으로 오인될 일도 없다.
python3 dex_gate.py "$OUT/with-dex.apk" "$OUT/mapping.txt"

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
