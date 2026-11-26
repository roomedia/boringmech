#!/usr/bin/env bash
# boringmech 기매수 장부 단일 소유자 JVM 테스트.
# BoughtLedger의 읽기·쓰기·검증 거동을 인메모리 Store로 대조하고,
# NativeCycle이 장부 키를 직접 만지지 않는지 구조 가드로 확인한다.
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
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" "$HERE/BoughtLedgerTest.java"
java -cp "$OUT/classes:$BC" BoughtLedgerTest \
  "$SRC/engine/NativeCycle.java" \
  "$SRC/engine/BoughtLedger.java"
