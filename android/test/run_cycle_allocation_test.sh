#!/usr/bin/env bash
# boringmech 자금 배분 계층 패리티 JVM 테스트.
# CycleAllocation이 픽스처 기준선(JS 리팩터 전 판정 캡처) 및 TempNo
# 순수 판정과 같은지 대조한다 (픽스처는 JS 테스트와 공유).
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
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" "$HERE/CycleAllocationTest.java"
java -cp "$OUT/classes:$BC" CycleAllocationTest \
  "$REPO/app/test/fixtures/allocation-parity.json" \
  "$SRC/engine/NativeCycle.java"
