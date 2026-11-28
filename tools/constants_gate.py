#!/usr/bin/env python3
"""삼중 구현 상수 대조 게이트 (공리적 설계 분리 5/7, 결합 5).

출하본 앱(JS engine.js 정본 + Java 미러)과 Python 트랙(scan_boring.py,
stop_loss.py, live/live_executor.py, paper-temp-scanner/scanner.py)에 흩어진
판단 상수를 전수 대조한다. 어느 한 곳의 값이 정본과 어긋나면 실패(exit 1).

정본 지정: 공유 상수의 정본은 출하본 JS `app/src/engine.js`다. Java는 그
미러이고, Python 페이퍼 트랙은 평가 일관성을 위해 같은 값을 따라야 한다.
`live/live_executor.py`(boringmech-micro-live)는 자체 SPEC.md가 정본인 별개
실험이라, 앱과 의도적으로 다른 값(검증 밴드·사이징)은 "pinned"로 고정해
추가 드리프트만 막는다. 지위 상세는 docs/PYTHON-TRACK.md.

실행: python3 tools/constants_gate.py  (app npm test에도 연결돼 있다)
"""
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

JS = "app/src/engine.js"
J_SCAN = "android/src/com/roomedia/boringmech/engine/ScanFilter.java"
J_VERIFY = "android/src/com/roomedia/boringmech/engine/CandidateVerifier.java"
J_BOOK = "android/src/com/roomedia/boringmech/engine/PositionBook.java"
J_CYCLE = "android/src/com/roomedia/boringmech/engine/NativeCycle.java"
J_FETCH = "android/src/com/roomedia/boringmech/engine/PositionsFetcher.java"
J_STOP = "android/src/com/roomedia/boringmech/engine/StopLoss.java"
J_TEMP = "android/src/com/roomedia/boringmech/engine/TempNo.java"
J_REDEEM = "android/src/com/roomedia/boringmech/engine/RedeemPlan.java"
PY_SCAN = "scan_boring.py"
PY_STOP = "stop_loss.py"
PY_LIVE = "live/live_executor.py"
PY_TEMP = "paper-temp-scanner/scanner.py"


def js_const(name):
    return (JS, rf"(?:export\s+)?const\s+{name}\s*=\s*([0-9][0-9_.]*)", 1)


def java_const(path, name):
    return (path, rf"{name}\s*=\s*([0-9][0-9_.]*)", 1)


def py_const(path, name):
    return (path, rf"(?m)^{name}\s*=\s*([0-9][0-9_.]*)", 1)


# (id, 정본값, [(파일, 정규식, 그룹번호), ...]) — 전 소스가 정본값과 같아야 한다.
SHARED = [
    ("scan.bandLo", 0.6, [js_const("BAND_LO"), java_const(J_SCAN, "BAND_LO"),
                          (PY_SCAN, r"if price < ([0-9.]+) or price >", 1),
                          (PY_LIVE, r"if price < ([0-9.]+) or price >", 1)]),
    ("scan.bandHi", 0.9, [js_const("BAND_HI"), java_const(J_SCAN, "BAND_HI"),
                          (PY_SCAN, r"or price > ([0-9.]+):", 1),
                          (PY_LIVE, r"or price > ([0-9.]+):", 1)]),
    ("scan.priorityBandLo", 0.7, [js_const("PRIORITY_BAND_LO"),
                                  java_const(J_SCAN, "PRIORITY_BAND_LO")]),
    ("scan.priorityBandHi", 0.8, [js_const("PRIORITY_BAND_HI"),
                                  java_const(J_SCAN, "PRIORITY_BAND_HI")]),
    ("scan.volumeMin", 10000, [js_const("VOLUME_MIN"),
                               java_const(J_SCAN, "VOLUME_MIN"),
                               (PY_SCAN, r'\(m\.get\("volume"\) or 0\) < ([0-9_]+)', 1)]),
    ("scan.endWindowDays", 14, [js_const("END_WINDOW_DAYS"),
                                java_const(J_SCAN, "END_WINDOW_DAYS"),
                                (PY_SCAN, r"timedelta\(days=([0-9]+)\)", 1)]),
    ("scan.spreadMax", 0.02, [js_const("SPREAD_MAX"),
                              java_const(J_VERIFY, "SPREAD_MAX"),
                              py_const(PY_LIVE, "SPREAD_MAX"),
                              py_const(PY_TEMP, "SPREAD_MAX")]),
    ("scan.askDepthMinUsd", 10.0, [js_const("ASK_DEPTH_MIN_USD"),
                                   java_const(J_VERIFY, "ASK_DEPTH_MIN_USD"),
                                   py_const(PY_LIVE, "ASK_DEPTH_MIN_USD"),
                                   py_const(PY_TEMP, "ASK_DEPTH_MIN_USD")]),
    ("scan.slippageCap", 0.015, [js_const("SLIPPAGE_CAP"),
                                 java_const(J_VERIFY, "SLIPPAGE_CAP"),
                                 py_const(PY_LIVE, "SLIPPAGE_CAP"),
                                 py_const(PY_TEMP, "SLIPPAGE_CAP")]),
    ("scan.snapshotMatchTol", 0.03, [js_const("SNAPSHOT_MATCH_TOL"),
                                     java_const(J_VERIFY, "SNAPSHOT_MATCH_TOL"),
                                     py_const(PY_LIVE, "SNAPSHOT_MATCH_TOL"),
                                     py_const(PY_TEMP, "SNAPSHOT_MATCH_TOL")]),
    ("scan.askAboveSignal", 0.05, [js_const("ASK_ABOVE_SIGNAL"),
                                   java_const(J_VERIFY, "ASK_ABOVE_SIGNAL"),
                                   py_const(PY_TEMP, "ASK_ABOVE_SIGNAL")]),
    ("sizing.betUsd", 5.0, [js_const("BET_USD"), java_const(J_BOOK, "BET_USD")]),
    ("sizing.cashFloorPct", 0.3, [js_const("CASH_FLOOR_PCT"),
                                  java_const(J_BOOK, "CASH_FLOOR_PCT"),
                                  py_const(PY_LIVE, "CASH_FLOOR_PCT")]),
    ("sizing.reserveUsd", 2.87, [js_const("RESERVE_USD"),
                                 java_const(J_BOOK, "RESERVE_USD"),
                                 py_const(PY_LIVE, "DEFAULT_RESERVE_USD")]),
    ("sizing.maxPositions", 70, [js_const("MAX_POSITIONS"),
                                 java_const(J_BOOK, "MAX_POSITIONS"),
                                 py_const(PY_LIVE, "MAX_POSITIONS")]),
    ("sizing.verifyCap", 60, [js_const("VERIFY_CAP"),
                              java_const(J_CYCLE, "VERIFY_CAP"),
                              py_const(PY_LIVE, "VERIFY_CAP")]),
    ("sizing.positionsPageCap", 50, [js_const("POSITIONS_PAGE_CAP"),
                                     java_const(J_FETCH, "POSITIONS_PAGE_CAP")]),
    ("stoploss.stopDrop", 0.25, [js_const("STOP_DROP"),
                                 java_const(J_STOP, "STOP_DROP"),
                                 py_const(PY_STOP, "DROP")]),
    ("stoploss.tempStopDrop", 0.15, [js_const("TEMP_STOP_DROP"),
                                     java_const(J_STOP, "TEMP_STOP_DROP")]),
    ("stoploss.decidedHigh", 0.97, [js_const("DECIDED_HIGH"),
                                    java_const(J_STOP, "DECIDED_HIGH"),
                                    py_const(PY_STOP, "DECIDED_HIGH")]),
    ("stoploss.decidedLow", 0.03, [js_const("DECIDED_LOW"),
                                   java_const(J_STOP, "DECIDED_LOW"),
                                   py_const(PY_STOP, "DECIDED_LOW")]),
    ("temp.bandHi", 0.998, [js_const("TEMP_NO_BAND_HI"),
                            java_const(J_TEMP, "BAND_HI"),
                            py_const(PY_TEMP, "BAND_HI")]),
    ("temp.bandLoDefault", 0.8, [js_const("TEMP_NO_DEFAULT_BAND_LO"),
                                 java_const(J_TEMP, "DEFAULT_BAND_LO"),
                                 py_const(PY_TEMP, "BAND_LO")]),
    ("temp.feeRate", 0.05, [js_const("TEMP_NO_FEE_RATE"),
                            java_const(J_TEMP, "FEE_RATE")]),
    ("temp.entryHourUtc", 6, [js_const("TEMP_NO_ENTRY_HOUR_UTC"),
                              java_const(J_TEMP, "ENTRY_HOUR_UTC"),
                              py_const(PY_TEMP, "ENTRY_HOUR_UTC")]),
    ("temp.reserveSlotsDefault", 1, [js_const("TEMP_NO_DEFAULT_RESERVE_SLOTS"),
                                     java_const(J_TEMP, "DEFAULT_RESERVE_SLOTS")]),
    ("temp.reserveSlotsMax", 70, [js_const("TEMP_NO_MAX_RESERVE_SLOTS"),
                                  java_const(J_TEMP, "MAX_RESERVE_SLOTS")]),
    ("temp.betUsd", 5.0, [js_const("TEMP_NO_DEFAULT_BET_USD"),
                          java_const(J_TEMP, "DEFAULT_BET_USD"),
                          py_const(PY_TEMP, "DEFAULT_BET")]),
    ("redeem.tolAbs", 0.05, [js_const("REDEEM_TOL_ABS"),
                             java_const(J_REDEEM, "REDEEM_TOL_ABS"),
                             py_const(PY_LIVE, "REDEEM_TOL_ABS")]),
    ("redeem.tolRel", 0.02, [js_const("REDEEM_TOL_REL"),
                             java_const(J_REDEEM, "REDEEM_TOL_REL"),
                             py_const(PY_LIVE, "REDEEM_TOL_REL")]),
]

# 문자열·목록형 공유 상수.
SHARED_TEXT = [
    ("scan.shortTermPattern",
     "(bitcoin|ethereum|solana|xrp|dogecoin|cardano)-up-or-down|up-or-down-",
     [(JS, r"const\s+SHORT_TERM_RE\s*=\s*/(.+?)/;", 1),
      (J_SCAN, r'SHORT_TERM_RE\s*=\s*Pattern\.compile\("([^"]+)"\)', 1),
      (PY_SCAN, r'SHORT_TERM_RE\s*=\s*re\.compile\(r"([^"]+)"\)', 1)]),
]

CITIES = [
    "amsterdam", "ankara", "austin", "beijing", "buenos-aires", "busan",
    "cape-town", "chengdu", "chongqing", "dallas", "guangzhou", "helsinki",
    "hong-kong", "houston", "istanbul", "jeddah", "kuala-lumpur", "london",
    "los-angeles", "madrid", "miami", "milan", "munich", "nyc", "paris",
    "qingdao", "san-francisco", "sao-paulo", "seattle", "seoul", "shanghai",
    "shenzhen", "singapore", "taipei", "tel-aviv", "tokyo", "toronto",
    "warsaw", "wellington", "wuhan",
]
SHARED_LIST = [
    ("temp.cities", CITIES,
     [(JS, r"TEMP_NO_CITIES\s*=\s*\[(.*?)\];", 1),
      (J_TEMP, r"CITIES\s*=\s*Arrays\.asList\((.*?)\);", 1),
      (PY_TEMP, r"CITIES\s*=\s*\[(.*?)\]", 1)]),
]

# 구현 고유값 — 정본과 달라도 되는 대신 이 값에서 더 drift하면 실패한다.
PINNED = [
    ("live.verifyBandLo (SPEC 확정, 앱 밴드와 의도적으로 다름)", 0.70,
     [(PY_LIVE, r"(?m)^BAND_LO, BAND_HI = ([0-9.]+),", 1)]),
    ("live.verifyBandHi (SPEC 확정)", 0.90,
     [(PY_LIVE, r"(?m)^BAND_LO, BAND_HI = [0-9.]+, ([0-9.]+)", 1)]),
    ("live.betPct (SPEC 확정, 앱 고정 $5와 의도적으로 다름)", 0.01,
     [py_const(PY_LIVE, "BET_PCT")]),
    ("live.betMinUsd (SPEC 확정)", 5.0, [py_const(PY_LIVE, "BET_MIN_USD")]),
    ("live.haltEquity (live 전용)", 35.0, [py_const(PY_LIVE, "HALT_EQUITY")]),
    ("scan.resurfaceMove (페이퍼 스캔 전용)", 0.05,
     [py_const(PY_SCAN, "RESURFACE_MOVE")]),
]


def read(rel):
    with open(os.path.join(BASE, rel), encoding="utf-8") as f:
        return f.read()


def extract(cache, rel, pattern, group):
    if rel not in cache:
        cache[rel] = read(rel)
    m = re.search(pattern, cache[rel], re.DOTALL)
    if not m:
        return None
    return m.group(group)


def num(raw):
    return float(raw.replace("_", "").rstrip("L"))


def main():
    cache = {}
    failures = []
    checked = 0

    def check(rule_id, expected, sources, conv, kind):
        nonlocal checked
        for rel, pattern, group in sources:
            raw = extract(cache, rel, pattern, group)
            checked += 1
            if raw is None:
                failures.append(f"{rule_id}: {rel}에서 추출 실패 (패턴 불일치)")
                continue
            got = conv(raw)
            if got != expected:
                failures.append(
                    f"{rule_id}: {rel} = {got!r}, 기대 {expected!r} ({kind})")

    for rule_id, expected, sources in SHARED:
        check(rule_id, float(expected), sources, num, "공유 상수")
    for rule_id, expected, sources in SHARED_TEXT:
        check(rule_id, expected, sources, str.strip, "공유 패턴")
    for rule_id, expected, sources in SHARED_LIST:
        check(rule_id, expected, sources,
              lambda raw: re.findall(r'"([^"]+)"', raw), "공유 목록")
    for rule_id, expected, sources in PINNED:
        check(rule_id, float(expected), sources, num, "고정값")

    total_rules = len(SHARED) + len(SHARED_TEXT) + len(SHARED_LIST) + len(PINNED)
    if failures:
        print(f"constants gate: FAIL — 규칙 {total_rules}개, 대조 {checked}건 중 "
              f"{len(failures)}건 어긋남")
        for f_ in failures:
            print("  " + f_)
        return 1
    print(f"constants gate: PASS — 규칙 {total_rules}개 "
          f"(공유 {len(SHARED) + len(SHARED_TEXT) + len(SHARED_LIST)}, "
          f"고정 {len(PINNED)}), 대조 {checked}건 전부 일치")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
