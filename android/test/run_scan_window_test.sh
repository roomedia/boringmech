#!/usr/bin/env bash
# boringmech 만기 창 경계 JVM 테스트 (v0.3.64).
# ScanFilter의 만기 창 기본 14일 경계(오늘 제외·14일 포함·15일 제외)를
# 고정한다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/android/src/com/roomedia/boringmech"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

find "$SRC/engine" "$SRC/write" "$SRC/crypto" -name '*.java' ! -name 'JsonModel.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt" \
  "$HERE/ScanWindowTest.java"
java -cp "$OUT/classes:$BC" ScanWindowTest
