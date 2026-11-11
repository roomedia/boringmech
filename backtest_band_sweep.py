#!/usr/bin/env python3
"""boring 전략 진입 밴드 파라미터 스윕.

기존 백테스트(backtest_boring.py)와 유니버스·진입 창(만기 24h~45일 전 첫 진입)·
수수료 모델·제외 규칙을 완전히 동일하게 유지하고, 밴드 (lo, hi)만 바꾼다.

- 주 스윕: hi=0.90 고정, lo = 0.70/0.65/0.60/0.55/0.50
- 보조 스윕: 주 스윕에서 z가 가장 높은 lo 고정, hi = 0.85/0.90/0.95
- 가격 히스토리는 최초 1회만 수집해 bt_hist_cache.json에 캐시한다 (셀 간 데이터 동일 보장).
- 통계는 bt_stats.stats()를 그대로 재사용한다 (z 정의·수수료 감도·연속 손실 계산 동일).
"""
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import backtest_boring as bb
from bt_stats import stats

BASE = os.path.dirname(os.path.abspath(__file__))
CACHE_PATH = os.path.join(BASE, "bt_hist_cache.json")
OUT_PATH = os.path.join(BASE, "bt_sweep_results.json")

PRIMARY_LOS = [0.70, 0.65, 0.60, 0.55, 0.50]
SECONDARY_HIS = [0.85, 0.90, 0.95]


def first_entry_band(hist, end_ts, lo, hi):
    """backtest_boring.first_entry와 동일 로직, 밴드만 파라미터화 (windowed 고정)."""
    for t, p in hist:
        if not (0.0 < p < 1.0):
            continue
        remain = end_ts - t
        if not (24 * 3600 <= remain <= 45 * 86400):
            continue
        if lo <= p <= hi:
            return t, "yes", p
        np_ = 1.0 - p
        if lo <= np_ <= hi:
            return t, "no", np_
    return None


def fetch_tail(c):
    tail_start = max(c["start_ts"], c["end_ts"] - 46 * 86400)
    tail = bb.fetch_range(c["yes_token"], tail_start, c["close_ts"])
    if not tail:
        return c["slug"], None
    return c["slug"], sorted(tail.items())


def load_or_fetch():
    if os.path.exists(CACHE_PATH):
        with open(CACHE_PATH) as f:
            cache = json.load(f)
        print(f"cache loaded: {len(cache['markets'])} markets, "
              f"{sum(1 for v in cache['hists'].values() if v)} with history",
              flush=True)
        return cache["markets"], {k: [tuple(x) for x in v] if v else None
                                  for k, v in cache["hists"].items()}
    raw = bb.collect_markets()
    print(f"gamma markets fetched: {len(raw)}", flush=True)
    cands = [e for m in raw if (e := bb.eligible(m))]
    print(f"eligible universe: {len(cands)}", flush=True)
    cands.sort(key=lambda x: -x["volume"])
    fetch_list = cands[:bb.MAX_HIST_FETCH]
    hists = {}
    done = 0
    with ThreadPoolExecutor(max_workers=12) as ex:
        futs = {ex.submit(fetch_tail, c): c for c in fetch_list}
        for fut in as_completed(futs):
            done += 1
            try:
                slug, hist = fut.result()
            except Exception:  # noqa: BLE001
                slug, hist = futs[fut]["slug"], None
            hists[slug] = hist
            if done % 100 == 0:
                print(f"  {done}/{len(fetch_list)} fetched", flush=True)
    with open(CACHE_PATH, "w") as f:
        json.dump({"markets": fetch_list, "hists": hists}, f)
    print(f"cache saved: {sum(1 for v in hists.values() if v)} with history",
          flush=True)
    return fetch_list, hists


def build_trades(cands, hists, lo, hi):
    trades = []
    for c in cands:
        hist = hists.get(c["slug"])
        if not hist:
            continue
        e = first_entry_band(hist, c["end_ts"], lo, hi)
        if not e:
            continue
        t, side, price = e
        tr = {k: c[k] for k in ("slug", "question", "event_slug", "yes_won",
                                "volume", "fee_rate", "neg_risk", "category",
                                "end_ts", "close_ts")}
        tr.update(entry_ts=t, side=side, entry_price=price)
        won, fee, net = bb.pnl_for(tr)
        tr.update(won=won, fee=fee, net_pnl=net)
        trades.append(tr)
    return trades


def character(trades):
    """표본 성격 지표: 진입 시 잔여 일수(중앙값), No 비중, 건당 잠재 배당."""
    if not trades:
        return {}
    days = sorted((t["end_ts"] - t["entry_ts"]) / 86400 for t in trades)
    med = days[len(days) // 2]
    no_share = sum(1 for t in trades if t["side"] == "no") / len(trades)
    payoff = sum(100.0 / t["entry_price"] - 100.0 for t in trades) / len(trades)
    return {"median_days_remain": round(med, 1),
            "no_share": round(no_share, 3),
            "avg_gross_payoff_if_win": round(payoff, 2)}


def cell(cands, hists, lo, hi):
    trades = build_trades(cands, hists, lo, hi)
    s = stats(trades, f"band {lo:.2f}-{hi:.2f}")
    s.update(character(trades))
    s["lo"], s["hi"] = lo, hi
    return s, trades


def time_split(trades):
    ordered = sorted(trades, key=lambda t: t["entry_ts"])
    half = len(ordered) // 2
    return (stats(ordered[:half], "first half"),
            stats(ordered[half:], "second half"))


def dedup_event(trades):
    by_event = {}
    for t in sorted(trades, key=lambda t: t["entry_ts"]):
        by_event.setdefault(t["event_slug"] or t["slug"], t)
    return stats(list(by_event.values()), "dedup by event")


def main():
    cands, hists = load_or_fetch()

    out = {"primary": [], "secondary": []}

    # 동일성 검증: 기존 셀 (0.70-0.90) 재현
    base_cell, _ = cell(cands, hists, 0.70, 0.90)
    out["replication_70_90"] = base_cell
    print("replication 70-90:", json.dumps(
        {k: base_cell[k] for k in ("n", "win_rate", "avg_entry", "net_pnl", "z")},
        ensure_ascii=False), flush=True)

    primary_trades = {}
    for lo in PRIMARY_LOS:
        s, trades = cell(cands, hists, lo, 0.90)
        out["primary"].append(s)
        primary_trades[lo] = trades
        print(f"primary lo={lo:.2f}: n={s['n']} win={s.get('win_rate', 0):.3f} "
              f"net={s.get('net_pnl', 0):+.0f} z={s.get('z', 0):+.2f}", flush=True)

    best_lo = max(out["primary"], key=lambda s: s.get("z", -99))["lo"]
    out["best_lo_by_z"] = best_lo
    print(f"best lo by z: {best_lo}", flush=True)

    secondary_trades = {}
    for hi in SECONDARY_HIS:
        s, trades = cell(cands, hists, best_lo, hi)
        out["secondary"].append(s)
        secondary_trades[hi] = trades
        print(f"secondary hi={hi:.2f}: n={s['n']} win={s.get('win_rate', 0):.3f} "
              f"net={s.get('net_pnl', 0):+.0f} z={s.get('z', 0):+.2f}", flush=True)

    # 후보 셀 (보조 스윕 포함 전체에서 z 최대) 안정성 체크
    all_cells = out["primary"] + [s for s in out["secondary"] if s["hi"] != 0.90]
    cand = max(all_cells, key=lambda s: s.get("z", -99))
    out["candidate"] = {"lo": cand["lo"], "hi": cand["hi"]}
    ctrades = (primary_trades[cand["lo"]] if cand["hi"] == 0.90
               else secondary_trades[cand["hi"]])
    h1, h2 = time_split(ctrades)
    out["candidate_time_split"] = {"first": h1, "second": h2}
    out["candidate_dedup_event"] = dedup_event(ctrades)
    print(f"candidate {cand['lo']:.2f}-{cand['hi']:.2f}: "
          f"half1 z={h1.get('z', 0):+.2f} net={h1.get('net_pnl', 0):+.0f} / "
          f"half2 z={h2.get('z', 0):+.2f} net={h2.get('net_pnl', 0):+.0f}",
          flush=True)

    with open(OUT_PATH, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"saved {OUT_PATH}")


if __name__ == "__main__":
    raise SystemExit(main())
