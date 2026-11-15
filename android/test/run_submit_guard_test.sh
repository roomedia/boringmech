#!/usr/bin/env bash
# boringmech 제출 가드 겹침 시나리오 JVM 테스트.
# SubmitGuard·StopLossLedger와 그 의존(engine 순수 로직)을 JVM으로
# 컴파일해 세션 겹침·세대 게이트·원장 동시 기록 케이스를 대조한다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# JsonModel은 org.json(Android) 변환 전용이라 JVM 테스트 집합에서 뺀다
# (NativeCycle 러너와 같은 방식).
find "$SRC/engine" "$SRC/write" "$SRC/crypto" -name '*.java' ! -name 'JsonModel.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" "$HERE/SubmitGuardTest.java"
java -cp "$OUT/classes:$BC" SubmitGuardTest
