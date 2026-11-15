#!/usr/bin/env bash
# boringmech 네이티브 사이클 오케스트레이션 JVM 테스트.
# engine/write/crypto 순수 Java 전체와 NativeCycleTest를 JVM으로
# 컴파일해 가짜 전송으로 사이클 완주·단계 순서·예외 격리를 대조한다.
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
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" "$HERE/NativeCycleTest.java"
java -cp "$OUT/classes:$BC" NativeCycleTest
