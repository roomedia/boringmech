#!/usr/bin/env bash
# 네이티브 이관 2단계 골든 대조 — engine 패키지 + 테스트 하네스를 JVM으로 컴파일·실행.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
GOLDEN_DIR="$(dirname "$HERE")"
REPO="$(dirname "$GOLDEN_DIR")"
OUT="${TMPDIR:-/tmp}/boringmech-golden2-classes"

rm -rf "$OUT"
mkdir -p "$OUT"

javac -encoding UTF-8 -d "$OUT" \
  "$REPO/android/src/com/roomedia/boringmech/crypto/JsNumber.java" \
  "$REPO/android/src/com/roomedia/boringmech/engine/Js.java" \
  "$REPO/android/src/com/roomedia/boringmech/engine/PositionBook.java" \
  "$REPO/android/src/com/roomedia/boringmech/engine/PositionsFetcher.java" \
  "$HERE/Json.java" \
  "$HERE/Golden2Test.java"

java -cp "$OUT" Golden2Test "$GOLDEN_DIR"
