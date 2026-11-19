#!/usr/bin/env bash
# boringmech 축소 코드 골든 스모크 — R8 축소를 거친 바이트코드로 서명을
# 실제로 수행해 골든 벡터와 대조한다.
#
# 배경: JVM 골든 테스트는 축소 전 클래스를 돌리므로 R8이 만든 구조
# 결함(클래스 검증 실패·동작 변형)을 못 잡는다 (v0.3.33 서명
# VerifyError). 이 스모크는 출하와 같은 keep.pro로 R8을 classfile
# 출력 모드로 돌려 축소 jar를 만들고, 그 jar만으로 GoldenTest 전
# 케이스(주문 서명·L1·배치 서명 포함)를 실행한다. 축소된 서명 경로가
# 검증 단계에서 죽거나 서명이 달라지면 여기서 실패한다.
#
# 출하 dex 자체의 구조 게이트는 android/dex_gate.py가 build.sh에서
# 맡는다 — 이 스모크는 "축소 코드가 실제로 도는가"를 맡는 별도 층이다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ANDROID="$REPO/android"
BC="$ANDROID/libs/bcprov-jdk18on-1.78.1.jar"
ANDROID_JAR="$HOME/android-sdk/platforms/android-34/android.jar"
R8_JAR="$HOME/android-sdk/build-tools/34.0.0/lib/d8.jar"

if [ -n "${BM_SMOKE_OUT:-}" ]; then
  OUT="$BM_SMOKE_OUT"
  rm -rf "$OUT"
  mkdir -p "$OUT"
else
  OUT="$(mktemp -d)"
  trap 'rm -rf "$OUT"' EXIT
fi

# 1) 앱 소스를 출하와 같은 방식으로 컴파일한다 (android.jar 기준).
mkdir -p "$OUT/gen" "$OUT/classes"
find "$ANDROID/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# gen이 비어 있으면 find가 실패하므로 소스만으로 다시 모은다
if [ ! -s "$OUT/sources.txt" ]; then
  find "$ANDROID/src" -name '*.java' > "$OUT/sources.txt"
fi
javac -encoding UTF-8 -cp "$ANDROID_JAR:$BC" -d "$OUT/classes" @"$OUT/sources.txt"

# 2) 출하 keep.pro 그대로 + classfile 모드 전용 dontwarn만 덧붙여 축소한다.
#    (dontwarn은 Android 프레임워크 클래스의 부재 경고 억제용이며
#     keep 의미론에는 영향을 주지 않는다.)
cp "$ANDROID/keep.pro" "$OUT/keep-smoke.pro"
printf -- "-dontwarn **\n" >> "$OUT/keep-smoke.pro"
java -cp "$R8_JAR" com.android.tools.r8.R8 --release --classfile \
  --lib "$HOME/jdk-17" --lib "$ANDROID_JAR" \
  --pg-conf "$OUT/keep-smoke.pro" --output "$OUT/shrunk.jar" \
  $(find "$OUT/classes" -name '*.class') "$BC"

# 3) 골든 전 케이스를 축소 jar로 실행한다. 컴파일은 비축소 클래스로
#    한다 — R8 classfile 출력은 InnerClasses 속성을 벗기므로 축소 jar에
#    대고 소스 컴파일하면 내부 클래스 참조가 깨진다. 실행 클래스패스는
#    축소 jar를 앞에 둬서 crypto 클래스가 전부 축소본으로 로드되게 한다.
javac -encoding UTF-8 -cp "$OUT/classes:$BC" -d "$OUT/test-classes" \
  "$REPO/native-golden/GoldenTest.java" "$HERE/ShrunkExtra.java"
java -cp "$OUT/test-classes:$OUT/shrunk.jar" GoldenTest "$REPO/native-golden/golden.json"
# 골든이 닿지 않는 쓰기 경로 직렬화 (리딤 제출 본문)도 축소본으로 실행한다.
java -cp "$OUT/test-classes:$OUT/shrunk.jar" ShrunkExtra
echo "shrunk smoke: PASSED"
