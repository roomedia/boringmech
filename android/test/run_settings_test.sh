#!/usr/bin/env bash
# boringmech 설정 파서 단일화 패리티 JVM 테스트.
# Settings 정본 해석과 TradeSettings·TempNo 투영이 픽스처 기준선과
# 같은지 대조한다 (픽스처는 JS 테스트와 공유).
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

find "$SRC/engine" "$SRC/write" "$SRC/crypto" -name '*.java' ! -name 'JsonModel.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" \
  "$HERE/SettingsTest.java"
java -cp "$OUT/classes:$BC" SettingsTest "$REPO/app/test/fixtures/settings-parity.json"
