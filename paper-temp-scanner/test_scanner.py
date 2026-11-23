#!/usr/bin/env python3
"""scanner 단위 테스트 — 네트워크를 모킹해 판정 경로를 태운다.

핵심 가드: verdict 레코드에 재조회 outcomePrices의 선정 쪽 가격
(requeryPrice)이 실제로 찍히는가. snapshot_mismatch 판정 (Q8 후속)과
통과 판정 양쪽을 확인하고, 재조회 전에 탈락한 판정에는 필드가 없어
기존 로그 형식과 충돌하지 않는지도 본다.

추가 가드 (Q17 후속): 선정 단계 가격 탈락 (temp_band_out)이 scan_drop
레코드로 개별 기록되는가 — slug·스냅샷 가격 쌍·밴드 방향을 확인하고,
가격 외 사유 탈락은 레코드를 만들지 않으며, 패스당 상한 초과 시
scan_drop_truncated로 생략 개수가 명시되는지도 본다.

실행: python3 test_scanner.py (네트워크 없음, 종료 코드 0 = 통과)
"""
import json
import sys
import tempfile
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import scanner

NOW_ISO = "2026-11-23T07:00:00Z"
END_ISO = "2026-11-23T12:00:00Z"
NOW_MS = scanner.parse_iso_ms(NOW_ISO)
SEOUL_SLUG = scanner.event_slug_for("seoul", NOW_MS)


def market(slug, title, prices, **kw):
    m = {"slug": slug, "question": f"q {slug}", "groupItemTitle": title,
         "active": True, "closed": False, "acceptingOrders": True,
         "archived": False, "outcomePrices": json.dumps(prices),
         "endDate": END_ISO}
    m.update(kw)
    return m


EVENT = {"slug": SEOUL_SLUG, "endDate": END_ISO, "markets": [
    market("temp-seoul-25c-a", "25°C", [0.15, 0.85]),
    market("temp-seoul-26c-b", "26°C", [0.15, 0.85]),
    # 선정 단계 가격 탈락: 하한 아래 (No 0.75), 상한 위 (No 0.999)
    market("temp-seoul-24c-lo", "24°C", [0.25, 0.75]),
    market("temp-seoul-27c-hi", "27°C", [0.001, 0.999]),
    # 가격 외 사유 탈락: 비활성 — scan_drop 레코드를 만들면 안 된다
    market("temp-seoul-28c-off", "28°C", [0.15, 0.85], active=False),
]}

REQUERY = {
    # A: 재조회 No 가격이 0.85 → 0.95로 벌어져 snapshot_mismatch
    "temp-seoul-25c-a": market(
        "temp-seoul-25c-a", "25°C", [0.05, 0.95],
        clobTokenIds=json.dumps(["tok-yes-a", "tok-no-a"])),
    # B: 재조회 가격이 같아 통과 경로로 진행
    "temp-seoul-26c-b": market(
        "temp-seoul-26c-b", "26°C", [0.15, 0.85],
        clobTokenIds=json.dumps(["tok-yes-b", "tok-no-b"])),
}

BOOK = {"asks": [{"price": "0.85", "size": "100"}],
        "bids": [{"price": "0.84", "size": "100"}],
        "tick_size": "0.01", "min_order_size": "5", "neg_risk": False}


def fake_fetch(url):
    q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    if "/events" in url:
        return [EVENT] if q.get("slug") == [SEOUL_SLUG] else []
    if "/markets" in url:
        m = REQUERY.get(q["slug"][0])
        return [m] if m else []
    if "/book" in url:
        return BOOK
    raise AssertionError(f"unexpected url: {url}")


def main():
    scanner.fetch_json = fake_fetch
    with tempfile.TemporaryDirectory() as td:
        scanner.run_pass(NOW_MS, 5.0, td, simulated=True)
        lines = (Path(td) / "scan-20261123.jsonl").read_text(
            encoding="utf-8").splitlines()
    recs = [json.loads(x) for x in lines]
    verdicts = {r["slug"]: r for r in recs if r.get("type") == "verdict"}
    assert set(verdicts) == {"temp-seoul-25c-a", "temp-seoul-26c-b"}, verdicts

    a = verdicts["temp-seoul-25c-a"]
    assert a["ok"] is False and a["reason"] == "snapshot_mismatch", a
    assert a.get("requeryPrice") == 0.95, a

    b = verdicts["temp-seoul-26c-b"]
    assert b["ok"] is True, b
    assert b.get("requeryPrice") == 0.85, b

    # 재조회 가격 확정 전에 탈락하는 판정에는 필드가 없어야 한다
    v = scanner.verify_candidate(
        {"slug": "no-such-market", "side": "no", "price": 0.85,
         "bandLo": 0.80, "bandHi": 0.998}, 5.0, NOW_MS)
    assert v["reason"] == "gamma_market_missing" and "requeryPrice" not in v, v

    # 선정 단계 가격 탈락이 scan_drop 레코드로 개별 기록돼야 한다
    drops = [r for r in recs if r.get("type") == "scan_drop"]
    by_slug = {r["slug"]: r for r in drops}
    assert set(by_slug) == {"temp-seoul-24c-lo", "temp-seoul-27c-hi"}, drops
    lo = by_slug["temp-seoul-24c-lo"]
    assert lo["reason"] == "temp_band_out" and lo["side"] == "below_lo", lo
    assert lo["priceYes"] == 0.25 and lo["priceNo"] == 0.75, lo
    assert lo["bandLo"] == scanner.BAND_LO and lo["bandHi"] == scanner.BAND_HI, lo
    hi = by_slug["temp-seoul-27c-hi"]
    assert hi["side"] == "above_hi" and hi["priceNo"] == 0.999, hi
    # 가격 외 사유 (비활성) 탈락은 레코드를 만들지 않는다
    assert "temp-seoul-28c-off" not in by_slug, drops
    # 기존 집계 카운트는 그대로고, 기록 수가 pass_start에 명시된다
    ps = next(r for r in recs if r.get("type") == "pass_start")
    assert ps["scan_drops"]["temp_band_out"] == 2, ps
    assert ps["scan_drops"]["temp_inactive"] == 1, ps
    assert ps["band_drop_records"] == 2, ps
    assert ps["band_drop_records_omitted"] == 0, ps
    assert not [r for r in recs if r.get("type") == "scan_drop_truncated"], recs

    # 패스당 상한 초과 시 잘라낸 개수가 별도 레코드로 명시돼야 한다
    orig_cap = scanner.MAX_BAND_DROP_RECORDS
    scanner.MAX_BAND_DROP_RECORDS = 1
    try:
        with tempfile.TemporaryDirectory() as td2:
            scanner.run_pass(NOW_MS, 5.0, td2, simulated=True)
            lines2 = (Path(td2) / "scan-20261123.jsonl").read_text(
                encoding="utf-8").splitlines()
    finally:
        scanner.MAX_BAND_DROP_RECORDS = orig_cap
    recs2 = [json.loads(x) for x in lines2]
    assert len([r for r in recs2 if r.get("type") == "scan_drop"]) == 1, recs2
    trunc = [r for r in recs2 if r.get("type") == "scan_drop_truncated"]
    assert len(trunc) == 1 and trunc[0]["omitted"] == 1, trunc
    ps2 = next(r for r in recs2 if r.get("type") == "pass_start")
    assert ps2["band_drop_records"] == 1, ps2
    assert ps2["band_drop_records_omitted"] == 1, ps2
    assert ps2["scan_drops"]["temp_band_out"] == 2, ps2

    print("PASS: requeryPrice recorded on mismatch and pass verdicts")
    print("PASS: scan_drop records for band-out drops, cap truncation noted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
