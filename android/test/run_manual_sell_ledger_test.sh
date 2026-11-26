#!/usr/bin/env bash
# boringmech 수동 매도 장부·attempted 단일 소유자 JVM 테스트.
# ManualSellLedger가 종전 NativeCycle 인라인 합성과 같은 저장 결과를
# 내는지 전 단계 대조로 검증하고, NativeCycle이 수동 매도 장부 키를
# 직접 만지지 않는지 구조 가드로 확인한다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# JsonModel은 org.json(Android) 변환 전용이라 JVM 테스트 집합에서 뺀다
# (NativeCycle 경로는 쓰지 않는다 — 골든 러너들과 같은 방식).
find "$SRC/engine" "$SRC/write" "$SRC/crypto" -name '*.java' ! -name 'JsonModel.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" "$HERE/ManualSellLedgerTest.java"
java -cp "$OUT/classes:$BC" ManualSellLedgerTest \
  "$SRC/engine/NativeCycle.java" \
  "$SRC/write/ManualSellLedger.java"
