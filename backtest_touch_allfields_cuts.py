"""Secondary cuts for the all-fields touch backtest (SPEC: 추가 축 3종).

Reads bt_touch_allfields_results.json (occurrence trades) and
bt_touch_band060_results.json (baseline population), adds three axes
computable from saved trade records — entry-price band, TTM at entry,
negRisk — crossed with role. No new API calls.
Output: bt_touch_allfields_cuts.json
"""
import json
import statistics
from collections import defaultdict

import touch_classifier as tc

OCC_KINDS = ("price_barrier", "survival_frame", "count", "occurrence")


def enrich(t, role):
    hold = max((t["close_ts"] - t["entry_ts"]) / 86400.0, 1e-9)
    return {
        "role": role, "won": t["won"], "entry": t["entry_price"],
        "pnl": t["net_pnl"], "turn": (t["net_pnl"] / 100.0) / hold,
        "ttm": (t["end_ts"] - t["entry_ts"]) / 86400.0,
        "neg": bool(t.get("neg_risk")),
        "cat": t["category"],
    }


def entry_band(p):
    if p < 0.70:
        return "0.60-0.70"
    if p < 0.80:
        return "0.70-0.80"
    return "0.80-0.90"


def ttm_band(d):
    if d <= 3:
        return "<=3d"
    if d <= 7:
        return "4-7d"
    if d <= 14:
        return "8-14d"
    if d <= 30:
        return "15-30d"
    return "31-45d"


def agg(ts):
    n = len(ts)
    if n == 0:
        return {"n": 0}
    return {"n": n, "win_rate": sum(1 for x in ts if x["won"]) / n,
            "avg_entry": sum(x["entry"] for x in ts) / n,
            "pnl_per_trade": sum(x["pnl"] for x in ts) / n,
            "turnover_mean_daily": statistics.mean(x["turn"] for x in ts)}


def cuts(rows):
    out = {}
    for axis, keyf in (("entry_band", lambda x: entry_band(x["entry"])),
                       ("ttm_band", lambda x: ttm_band(x["ttm"])),
                       ("neg_risk", lambda x: str(x["neg"]))):
        cells = defaultdict(list)
        for x in rows:
            cells[(x["role"], keyf(x))].append(x)
            cells[("ALL", keyf(x))].append(x)
        out[axis] = {f"{k[0]}|{k[1]}": agg(v) for k, v in cells.items()}
    return out


def main():
    res = json.load(open("bt_touch_allfields_results.json"))
    occ_rows = [enrich(t, t["role"]) for t in res["trades"]]
    base = json.load(open("bt_touch_band060_results.json"))
    base_rows = []
    for t in base["baseline_trades_main"] + base["baseline_trades_sens"]:
        kind, tside = tc.classify_touch(t["question"])
        if kind in OCC_KINDS:
            role = "touch" if t["side"] == tside else "survival"
            base_rows.append(enrich(t, role))
        else:
            base_rows.append(enrich(t, "fixed"))
    out = {"occurrence_10k": cuts(occ_rows),
           "baseline_population_50k": cuts(base_rows)}
    with open("bt_touch_allfields_cuts.json", "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("occurrence_10k rows:", len(occ_rows),
          "| baseline rows:", len(base_rows))


if __name__ == "__main__":
    main()
