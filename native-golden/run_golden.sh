#!/usr/bin/env bash
# boringmech 네이티브 암호 코어 골든 테스트.
# android/src/com/roomedia/boringmech/crypto/ 의 순수 Java 소스와
# GoldenTest.java를 JVM으로 컴파일해 golden.json 전 케이스를 대조한다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
BC="$REPO/android/libs/bcprov-jdk18on-1.78.1.jar"
CRYPTO_DIR="$REPO/android/src/com/roomedia/boringmech/crypto"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

find "$CRYPTO_DIR" -name '*.java' > "$OUT/sources.txt"
echo "$HERE/GoldenTest.java" >> "$OUT/sources.txt"

javac -encoding UTF-8 -cp "$BC" -d "$OUT/classes" @"$OUT/sources.txt"
java -cp "$OUT/classes:$BC" GoldenTest "$HERE/golden.json"
