#!/usr/bin/env python3
"""bt_results.json에서 백테스트 통계를 계산한다."""
import json
import math
import os
from collections import defaultdict

BASE = os.path.dirname(os.path.abspath(__file__))
STAKE = 100.0

SPORTS_KW = ["super bowl", "world series", "nba", "nfl", "mlb", "nhl",
             "stanley cup", "win worlds", "worlds 2026", "champion",
             "grand prix", "wimbledon", "premier league", "world cup",
             "playoff", "heisman", "ufc", "f1 ", "masters tournament",
             "win the cy young", "win the mvp", "balon d'or", "ballon d'or"]


def reclassify(t):
    q = " " + (t["question"] or "").lower() + " "
    if t["category"] in ("crypto", "economy"):
        return t["category"]
    if any(k in q for k in SPORTS_KW):
        return "sports_outright"
    return t["category"]


def stats(trades, label):
    n = len(trades)
    if n == 0:
        return {"label": label, "n": 0}
    wins = sum(t["won"] for t in trades)
    sum_p = sum(t["entry_price"] for t in trades)
    var = sum(t["entry_price"] * (1 - t["entry_price"]) for t in trades)
    z = (wins - sum_p) / math.sqrt(var) if var > 0 else 0.0
    p_two = 2 * (1 - 0.5 * (1 + math.erf(abs(z) / math.sqrt(2))))
    net = sum(t["net_pnl"] for t in trades)
    fees = sum(t["fee"] for t in trades)
    gross = net + fees
    # 수수료 감도: fee_rate가 None/0인 건에 일괄 5% 가정
    net_fee5 = 0.0
    for t in trades:
        rate = t["fee_rate"] or 0.0
        if rate == 0.0:
            extra = STAKE * 0.05 * (1 - t["entry_price"])
            net_fee5 += t["net_pnl"] - extra
        else:
            net_fee5 += t["net_pnl"]
    # 연속 손실·최대 낙폭 (진입 시점 순)
    ordered = sorted(trades, key=lambda t: t["entry_ts"])
    streak = worst_streak = 0
    cum = peak = 0.0
    max_dd = 0.0
    for t in ordered:
        cum += t["net_pnl"]
        peak = max(peak, cum)
        max_dd = min(max_dd, cum - peak)
        if t["won"]:
            streak = 0
        else:
            streak += 1
            worst_streak = max(worst_streak, streak)
    return {
        "label": label, "n": n, "wins": wins,
        "win_rate": wins / n, "avg_entry": sum_p / n,
        "expected_wins": sum_p, "z": z, "p_two_sided": p_two,
        "net_pnl": net, "gross_pnl": gross, "fees_modeled": fees,
        "avg_pnl_per_trade": net / n, "roi_pct": net / (STAKE * n) * 100,
        "net_pnl_if_fee5": net_fee5,
        "max_consec_losses": worst_streak, "max_drawdown": max_dd,
        "worst_trade": min(t["net_pnl"] for t in trades),
        "best_trade": max(t["net_pnl"] for t in trades),
        "yes_side": sum(1 for t in trades if t["side"] == "yes"),
    }


def bucket(trades, keyfn):
    groups = defaultdict(list)
    for t in trades:
        groups[keyfn(t)].append(t)
    return {k: stats(v, k) for k, v in sorted(groups.items())}


def main():
    d = json.load(open(os.path.join(BASE, "bt_results.json")))
    trades = d["trades_main"]
    for t in trades:
        t["category2"] = reclassify(t)

    out = {"meta": d["meta"]}
    out["overall"] = stats(trades, "main (windowed)")
    out["sens"] = stats(d["trades_sens"], "sensitivity (unwindowed)")
    out["bands"] = bucket(trades, lambda t: (
        "70-75" if t["entry_price"] < 0.75 else
        "75-80" if t["entry_price"] < 0.80 else
        "80-85" if t["entry_price"] < 0.85 else "85-90"))
    out["categories"] = bucket(trades, lambda t: t["category2"])
    # 이벤트 중복 제거 (이벤트당 최초 진입 1건)
    by_event = {}
    for t in sorted(trades, key=lambda t: t["entry_ts"]):
        by_event.setdefault(t["event_slug"] or t["slug"], t)
    out["dedup_event"] = stats(list(by_event.values()), "dedup by event")
    out["n_events"] = len(by_event)
    boundary = sum(1 for t in trades
                   if (t["end_ts"] - t["entry_ts"]) >= 44 * 86400)
    out["entries_at_window_open"] = boundary
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
