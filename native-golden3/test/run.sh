#!/usr/bin/env bash
# 네이티브 이관 3단계 골든 대조 — engine 판정 패키지 + 테스트 하네스를 JVM으로 컴파일·실행.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
GOLDEN_DIR="$(dirname "$HERE")"
REPO="$(dirname "$GOLDEN_DIR")"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"
OUT="${TMPDIR:-/tmp}/boringmech-golden3-classes"

rm -rf "$OUT"
mkdir -p "$OUT"

# TradeSettings가 PositionBook 상수를 참조하므로 engine/write/
# crypto 전체를 컴파일한다 (JsonModel은 org.json(Android) 전용이라 제외 —
# NativeCycle 테스트와 같은 방식).
find "$SRC/engine" "$SRC/write" "$SRC/crypto" -name '*.java' ! -name 'JsonModel.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$BC" -d "$OUT" @"$OUT/sources.txt" \
  "$HERE/Json.java" \
  "$HERE/Golden3Test.java"

java -cp "$OUT:$BC" Golden3Test "$GOLDEN_DIR"
