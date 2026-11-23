#!/usr/bin/env bash
# boringmech 주문 경로 측정 기능 JVM 테스트.
# 지정가 빌더 검증·취소 요청 구성·열린 주문 조회·체결 판정·통계와
# 측정 순수 로직(OrderPathTest)을 가짜 전송으로 대조한다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# JsonModel은 org.json(Android) 변환 전용이라 JVM 테스트 집합에서 뺀다.
find "$SRC/engine" "$SRC/write" "$SRC/crypto" -name '*.java' ! -name 'JsonModel.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" "$HERE/OrderPathFeatureTest.java"
java -cp "$OUT/classes:$BC" OrderPathFeatureTest
