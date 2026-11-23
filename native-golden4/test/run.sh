#!/usr/bin/env bash
# 네이티브 이관 4단계(쓰기 경로) 골든 대조 — write 패키지를 JVM으로 컴파일·실행.
# golden4.json은 gen_golden4.mjs가 SDK·engine.js·order-bridge.js 원본으로
# 생성한다 (재생성: node gen_golden4.mjs). 실제 네트워크는 쓰지 않는다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
GOLDEN_DIR="$(dirname "$HERE")"
REPO="$(dirname "$GOLDEN_DIR")"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"
OUT="${TMPDIR:-/tmp}/boringmech-golden4-classes"

rm -rf "$OUT"
mkdir -p "$OUT"

javac -encoding UTF-8 -cp "$BC" -d "$OUT" \
  "$SRC"/crypto/*.java \
  "$SRC/engine/Js.java" \
  "$SRC/engine/JsonText.java" \
  "$SRC"/write/*.java \
  "$HERE/Json.java" \
  "$HERE/Golden4Test.java"

java -cp "$OUT:$BC" Golden4Test "$GOLDEN_DIR"
