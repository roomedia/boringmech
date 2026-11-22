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

# TradeSettings가 PositionBook 상수를 참조하므로 순수 로직
# 집합이 아니라 engine/write/crypto 전체를 컴파일한다 (JsonModel은
# org.json(Android) 전용이라 제외 — NativeCycle 테스트와 같은 방식).
find "$SRC/engine" "$SRC/write" "$SRC/crypto" -name '*.java' ! -name 'JsonModel.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" \
  "$HERE/TempNoTest.java"
java -cp "$OUT/classes:$BC" TempNoTest
