#!/usr/bin/env python3
"""boringmech 터치형 백테스트 재측정 — 진입 밴드 0.60~0.90 (실전 밴드).

개정 로그: 밴드 0.70~0.90 → 0.60~0.90, 2026-11-19 사용자 지시로
재측정 전에 변경. 그 외 유니버스·진입 기계·수수료·판정 기준은
backtest_touch.py / backtest-touch-20261119.md와 동일하다.

구성:
- 터치형: backtest_touch의 수집·적격·거래 생성을 그대로 재사용한다.
  네트워크는 전부 backtest_touch.get_json(캐시 + 429/401/403 하드스톱)
  경유라 기존 캐시(boringmech-touch-20261119)를 그대로 쓴다.
- 고정형 기준선: backtest_boring.eligible과 같은 유니버스(거래량
  $50k 이상, 전 카테고리)를 같은 방식으로 다시 만들어 밴드만 바꾼
  거래를 생성한다. 기존 bt_results.json(0.70~0.90)은 덮지 않는다.
- 밴드 교체는 backtest_boring 모듈 전역(BAND_LO)을 실행 시점에
  바꾸는 방식으로 한다. first_entry가 호출 시점에 전역을 읽는다.
- 산출물: bt_touch_band060_results.json (원본 결과 파일과 분리).
"""
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import backtest_boring as bb  # noqa: E402

bb.BAND_LO, bb.BAND_HI = 0.60, 0.90

import backtest_touch as bt  # noqa: E402

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "bt_touch_band060_results.json")


def collect_baseline_markets():
    """backtest_boring.collect_markets와 같은 파라미터, 조회만 캐시 경유."""
    out, offset = [], 0
    while offset <= 20000:
        page = bt.get_json(bb.GAMMA, {
            "closed": "true", "limit": 100, "offset": offset,
            "volume_min": bb.VOLUME_MIN, "end_date_min": bb.END_MIN,
            "end_date_max": bb.END_MAX,
        }, provider="gamma")
        if not page:
            break
        out.extend(page)
        if len(page) < 100:
            break
        offset += 100
    return out


def fetch_range_cached(tok, s, e, depth=0):
    """backtest_boring.fetch_range와 같은 재귀 분할, 조회만 캐시 경유."""
    s, e = int(s), int(e)
    if e - s < 3600:
        return {}
    h = bt.get_json(bb.CLOB_HIST, {"market": tok, "startTs": s, "endTs": e,
                                   "fidelity": 60}, provider="clob")
    if h is not None:
        return {pt["t"]: float(pt["p"]) for pt in h.get("history", [])}
    if depth >= 3 or e - s <= 3 * 86400:
        return None
    mid = (s + e) // 2
    a = fetch_range_cached(tok, s, mid, depth + 1)
    if a is None:
        return None
    b = fetch_range_cached(tok, mid, e, depth + 1)
    if b is None:
        return None
    a.update(b)
    return a


def fetch_hist_cached(c):
    """backtest_boring.fetch_hist와 같은 구성, 조회만 캐시 경유."""
    tail_start = max(c["start_ts"], c["end_ts"] - 46 * 86400)
    tail = fetch_range_cached(c["yes_token"], tail_start, c["close_ts"])
    if not tail:
        return None, None, False
    base = {k: c[k] for k in ("slug", "question", "event_slug", "yes_won",
                              "volume", "fee_rate", "neg_risk", "category",
                              "end_ts", "close_ts")}

    def make(hist, windowed):
        e_ = bb.first_entry(hist, c["end_ts"], windowed=windowed)
        if not e_:
            return None
        t, side, price = e_
        tr = dict(base, entry_ts=t, side=side, entry_price=price)
        won, fee, net = bb.pnl_for(tr)
        tr.update(won=won, fee=fee, net_pnl=net)
        return tr

    tail_hist = sorted(tail.items())
    main_tr = make(tail_hist, True)
    sens_tr = None
    life = c["close_ts"] - c["start_ts"]
    if tail_start <= c["start_ts"]:
        sens_tr = make(tail_hist, False)
    elif life <= 170 * 86400:
        head = fetch_range_cached(c["yes_token"], c["start_ts"], tail_start)
        if head is not None:
            merged = dict(head)
            merged.update(tail)
            sens_tr = make(sorted(merged.items()), False)
    return main_tr, sens_tr, True


def run_pool(fn, items):
    results = []
    with ThreadPoolExecutor(max_workers=12) as ex:
        futs = {ex.submit(fn, c): c for c in items}
        for fut in as_completed(futs):
            results.append(fut.result())
    return results


def main():
    meta = {"provider_blocked": None, "band": [bb.BAND_LO, bb.BAND_HI]}
    try:
        # --- 터치형 (기존 파이프라인, 밴드만 교체) ---
        raw, n_pass_a, n_pass_b = bt.collect_markets()
        print(f"[touch] gamma fetched: {len(raw)} "
              f"(default {n_pass_a} + volume {n_pass_b})", flush=True)
        cands = [e for m in raw if (e := bt.eligible_touch(m))]
        cands.sort(key=lambda x: -x["volume"])
        fetch_list = cands[:bt.MAX_HIST_FETCH]
        print(f"[touch] eligible {len(cands)}, fetch targets "
              f"{len(fetch_list)}", flush=True)
        trades, trades_main_only = [], []
        no_hist = no_entry = 0
        for tm, ts_, had in run_pool(bt.fetch_trade, fetch_list):
            if not had:
                no_hist += 1
            else:
                if tm:
                    trades.append(tm)
                    trades_main_only.append(tm)
                else:
                    no_entry += 1
                if ts_:
                    trades.append(ts_)
        print(f"[touch] trades {len(trades)} "
              f"(main {len(trades_main_only)}), no_hist {no_hist}, "
              f"no_entry {no_entry}", flush=True)

        # --- 고정형 기준선 (밴드만 바꾼 재생성) ---
        braw = collect_baseline_markets()
        bcands = [e for m in braw if (e := bb.eligible(m))]
        bcands.sort(key=lambda x: -x["volume"])
        bfetch = bcands[:bb.MAX_HIST_FETCH]
        print(f"[fixed] gamma fetched: {len(braw)}, eligible "
              f"{len(bcands)}, fetch targets {len(bfetch)}", flush=True)
        b_main, b_sens = [], []
        b_no_hist = 0
        done = 0
        with ThreadPoolExecutor(max_workers=12) as ex:
            futs = {ex.submit(fetch_hist_cached, c): c for c in bfetch}
            for fut in as_completed(futs):
                done += 1
                tm, ts_, had = fut.result()
                if not had:
                    b_no_hist += 1
                else:
                    if tm:
                        b_main.append(tm)
                    if ts_:
                        b_sens.append(ts_)
                if done % 200 == 0:
                    print(f"[fixed] {done}/{len(bfetch)} processed, "
                          f"main trades={len(b_main)}", flush=True)
        print(f"[fixed] main {len(b_main)}, sens {len(b_sens)}, "
              f"no_hist {b_no_hist}", flush=True)
    except bt.ProviderBlocked as b:
        meta["provider_blocked"] = {"provider": b.provider,
                                    "status": b.status}
        print(f"PROVIDER BLOCKED: {b.provider} HTTP {b.status} — 즉시 중단",
              flush=True)
        trades, trades_main_only = [], []
        fetch_list, cands, raw = [], [], []
        no_hist = no_entry = 0
        b_main, b_sens, bfetch, bcands, braw = [], [], [], [], []
        b_no_hist = 0
        n_pass_a = n_pass_b = 0

    fixed_all = []
    for t in b_main + b_sens:
        if t.get("category") == "crypto" and bt.is_touch(t.get("question")):
            continue
        fixed_all.append(t)

    scan = bt.stub_scan(trades)
    meta.update({
        "gamma_fetched": len(raw),
        "gamma_pass_default": n_pass_a,
        "gamma_pass_volume_ordered": n_pass_b,
        "eligible_touch": len(cands),
        "fetch_targets": len(fetch_list),
        "no_history": no_hist,
        "no_entry": no_entry,
        "trades": len(trades),
        "trades_main_only": len(trades_main_only),
        "baseline_gamma_fetched": len(braw),
        "baseline_eligible": len(bcands),
        "baseline_fetch_targets": len(bfetch),
        "baseline_no_history": b_no_hist,
        "baseline_trades_main": len(b_main),
        "baseline_trades_sens": len(b_sens),
        "baseline_fixed_after_touch_exclusion": len(fixed_all),
        "http_calls": bt.STATS["http_calls"],
        "cache_hits": bt.STATS["cache_hits"],
        "stub_scan": scan,
        "universe_note": (
            f"Gamma closed=true, endDate {bt.END_MIN}..{bt.END_MAX}, "
            f"touch: volume>=${bt.VOLUME_MIN}, crypto, touch pattern; "
            f"fixed baseline: volume>=${bb.VOLUME_MIN}, all categories, "
            "same construction as backtest_boring, band 0.60-0.90, "
            "crypto touch excluded"),
    })

    groups = {
        "touch_10k": bt.by_side(trades),
        "touch_10k_main_only": bt.by_side(trades_main_only),
        "touch_50k": bt.by_side(
            [t for t in trades if t["volume"] >= bt.VOLUME_HIGH]),
        "touch_barrier": bt.by_side(
            [t for t in trades if t["touch_kind"] == "barrier"]),
        "touch_level": bt.by_side(
            [t for t in trades if t["touch_kind"] == "level"]),
        "fixed_baseline": bt.by_side(fixed_all),
    }
    if meta["provider_blocked"] or scan["marker_hit_files"] or \
            scan["trade_anomalies"]:
        verdicts = {"no": "판정 불가 (데이터 요건 미충족)",
                    "yes": "판정 불가 (데이터 요건 미충족)"}
    else:
        verdicts = {d: bt.verdict_for(d, groups["touch_10k"][d],
                                      groups["fixed_baseline"][d])
                    for d in ("no", "yes")}

    out = {"meta": meta, "groups": groups, "verdicts": verdicts,
           "trades": trades,
           "baseline_trades_main": b_main, "baseline_trades_sens": b_sens}
    with open(OUT, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(meta, ensure_ascii=False))
    print("verdicts:", json.dumps(verdicts, ensure_ascii=False))


if __name__ == "__main__":
    raise SystemExit(main())
