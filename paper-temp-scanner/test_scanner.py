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

추가 가드 (Q24 후속): above_hi 탈락 중 상한 근처 시장은 표본
오더북 프로브가 scan_probe 레코드로 찍히는가 — bestAsk·bestBid와
밴드 안 여부, 패스당 상한, 조회 실패 기록, 본 흐름 (후보·판정)에
영향이 없는지를 본다.

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
    market("temp-seoul-27c-hi", "27°C", [0.001, 0.999],
           clobTokenIds=json.dumps(["tok-yes-hi", "tok-no-hi"])),
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

    # 표본 프로브: above_hi 상한 근처 (0.999, 초과분 0.001) 시장만
    # scan_probe로 찍히고, 스냅샷은 밴드 밖인데 ask (0.85)는 밴드
    # 안인 역방향 유입이 inBand로 드러나야 한다
    probes = [r for r in recs if r.get("type") == "scan_probe"]
    assert len(probes) == 1, probes
    pr = probes[0]
    assert pr["slug"] == "temp-seoul-27c-hi", pr
    assert pr["ok"] is True and pr["priceNo"] == 0.999, pr
    assert pr["bestAsk"] == 0.85 and pr["bestBid"] == 0.84, pr
    assert pr["inBand"] is True, pr
    assert ps["scan_probes_eligible"] == 1, ps
    assert ps["scan_probes_planned"] == 1, ps
    summ = next(r for r in recs if r.get("type") == "summary")
    assert summ["probes"] == 1 and summ["probe_in_band"] == 1, summ
    # 프로브가 본 흐름을 바꾸지 않는다 — 후보는 여전히 2건뿐이다
    assert summ["candidates"] == 2, summ
    assert not [c for c in recs if c.get("type") == "candidate"
                and c["slug"] == "temp-seoul-27c-hi"], recs

    # 표본 선정 상한: 초과분이 작은 순으로 MAX_PROBES_PER_PASS까지
    many = [{"slug": f"s{i}", "title": "t", "priceYes": 0.0,
             "priceNo": round(0.9981 + i * 0.0001, 6), "tokenId": "tok",
             "endDate": END_ISO} for i in range(15)]
    picked = scanner.select_probe_targets(many)
    assert len(picked) == scanner.MAX_PROBES_PER_PASS == 10, picked
    assert picked[0]["priceNo"] == min(t["priceNo"] for t in many), picked
    orig_pcap = scanner.MAX_PROBES_PER_PASS
    scanner.MAX_PROBES_PER_PASS = 1
    try:
        assert len(scanner.select_probe_targets(many)) == 1
    finally:
        scanner.MAX_PROBES_PER_PASS = orig_pcap

    # 근접 구간 밖 (초과분 > 0.002)과 below_lo는 표본 대상이 아니다
    far_stats, far_targets = {}, []
    far_event = {"slug": SEOUL_SLUG, "endDate": END_ISO, "markets": [
        market("temp-seoul-far", "30°C", [0.0, 1.001]),
        market("temp-seoul-lo2", "31°C", [0.25, 0.75]),
    ]}
    scanner.scan_candidates([far_event], NOW_MS, far_stats, [], far_targets)
    assert far_targets == [], far_targets
    assert far_stats.get("temp_band_out") == 2, far_stats

    # 조회 실패는 실패 레코드로 남는다 (토큰 없음, 네트워크 오류)
    rec_no_tok = scanner.probe_book(
        {"slug": "x", "title": "t", "priceYes": 0.0, "priceNo": 0.999,
         "tokenId": None, "endDate": END_ISO})
    assert rec_no_tok["ok"] is False, rec_no_tok
    assert rec_no_tok["reason"] == "probe_no_token", rec_no_tok
    orig_fetch = scanner.fetch_json
    def boom(url):
        raise RuntimeError("net down")
    scanner.fetch_json = boom
    try:
        rec_err = scanner.probe_book(
            {"slug": "y", "title": "t", "priceYes": 0.0, "priceNo": 0.999,
             "tokenId": "tok-y", "endDate": END_ISO})
    finally:
        scanner.fetch_json = orig_fetch
    assert rec_err["ok"] is False and rec_err["reason"] == "probe_error", rec_err
    assert "net down" in rec_err["error"], rec_err

    print("PASS: requeryPrice recorded on mismatch and pass verdicts")
    print("PASS: scan_drop records for band-out drops, cap truncation noted")
    print("PASS: scan_probe records near-cap above_hi sample, cap and failure noted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
