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

javac -encoding UTF-8 -cp "$BC" -d "$OUT" \
  "$SRC/crypto/JsNumber.java" \
  "$SRC/crypto/Hex.java" \
  "$SRC/crypto/Keccak.java" \
  "$SRC/crypto/Secp256k1.java" \
  "$SRC/crypto/Abi.java" \
  "$SRC/crypto/CallData.java" \
  "$SRC/crypto/Redeem.java" \
  "$SRC/engine/Js.java" \
  "$SRC/engine/JsonText.java" \
  "$SRC/engine/PositionBook.java" \
  "$SRC/engine/ScanFilter.java" \
  "$SRC/engine/CandidateVerifier.java" \
  "$SRC/engine/StopLoss.java" \
  "$SRC/engine/RedeemPlan.java" \
  "$SRC/engine/FillJudge.java" \
  "$SRC/engine/BoughtReconcile.java" \
  "$SRC/engine/SettledBaseline.java" \
  "$HERE/Json.java" \
  "$HERE/Golden3Test.java"

java -cp "$OUT:$BC" Golden3Test "$GOLDEN_DIR"
