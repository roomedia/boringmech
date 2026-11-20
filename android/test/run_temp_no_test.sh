#!/usr/bin/env bash
# boringmech 기온 No 페이드 JVM 단위 테스트.
# TempNo·ScanFilter·CandidateVerifier·StopLoss 순수 로직만 컴파일해
# 네트워크 없이 판정·밴드·창 경계를 대조한다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" \
  "$SRC/engine/TempNo.java" \
  "$SRC/engine/BoughtReconcile.java" \
  "$SRC/engine/ScanFilter.java" \
  "$SRC/engine/CandidateVerifier.java" \
  "$SRC/engine/StopLoss.java" \
  "$SRC/engine/Js.java" \
  "$SRC/engine/JsonText.java" \
  "$SRC/crypto/JsNumber.java" \
  "$SRC/write/ManualSell.java" \
  "$HERE/TempNoTest.java"
java -cp "$OUT/classes:$BC" TempNoTest
