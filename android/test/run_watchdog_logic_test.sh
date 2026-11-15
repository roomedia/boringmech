#!/usr/bin/env bash
# boringmech 워치독 순수 판정 JVM 테스트.
# android/src/com/roomedia/boringmech/WatchdogLogic.java와 테스트를
# JVM으로 컴파일해 판정 케이스 전부를 대조한다.
set -euo pipefail

export PATH="$HOME/jdk-17/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/android/src/com/roomedia/boringmech/WatchdogLogic.java"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -d "$OUT/classes" "$SRC" "$HERE/WatchdogLogicTest.java"
java -cp "$OUT/classes" WatchdogLogicTest
