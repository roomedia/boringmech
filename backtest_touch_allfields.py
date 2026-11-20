"""All-fields occurrence-type (touch-structure) backtest.

Spec: backtest-touch-allfields-SPEC.md (frozen 2026-11-19).
- Universe: Gamma closed, endDate 2025-10-29..2026-10-29, volume >= $10k,
  binary Yes/No, UMA resolved, sports & crypto up/down excluded.
- Classifier: touch_classifier.classify_touch (question text only).
- Entry/accounting identical to backtest_touch (band 0.60-0.90 first entry,
  $100/trade, Gamma feeSchedule rate, hold to settlement).
- Cheap-band cut: survival side only, entry caps 0.70 / 0.75.
- Fixed baseline: bt_touch_band060_results.json baseline trades,
  reclassified with the same classifier.
Output: bt_touch_allfields_results.json + bt_touch_allfields_audit_sample.json
"""
import json
import os
import random
import statistics
import sys
from collections import Counter, defaultdict

import backtest_boring as bb

bb.BAND_LO, bb.BAND_HI = 0.60, 0.90  # live boringmech band

import backtest_touch as bt  # noqa: E402
import touch_classifier as tc  # noqa: E402

VOLUME_MIN = 10_000
MAX_FETCH = 1200
OCC_KINDS = ("price_barrier", "survival_frame", "count", "occurrence")
SUB_CAPS = (0.70, 0.75)


def eligible_all(m):
    """backtest_touch.eligible_touch skeleton, all categories + classifier."""
    if m.get("sportsMarketType"):
        return None
    slug = m.get("slug") or ""
    if bt.SHORT_TERM_RE.search(slug):
        return None
    try:
        outcomes = json.loads(m.get("outcomes") or "[]")
        prices = [float(x) for x in json.loads(m.get("outcomePrices") or "[]")]
        toks = json.loads(m.get("clobTokenIds") or "[]")
    except (ValueError, TypeError):
        return None
    if outcomes != ["Yes", "No"] or len(prices) != 2 or len(toks) != 2:
        return None
    if m.get("umaResolutionStatus") != "resolved":
        return None
    yes_won = prices[0] > 0.99
    no_won = prices[1] > 0.99
    if yes_won == no_won:
        return None
    vol = m.get("volumeNum")
    if vol is None:
        try:
            vol = float(m.get("volume") or 0)
        except (TypeError, ValueError):
            vol = 0
    if vol < VOLUME_MIN:
        return None
    start_ts = bt.parse_ts(m.get("startDate"))
    end_ts = bt.parse_ts(m.get("endDate"))
    close_ts = bt.parse_ts(m.get("closedTime"))
    if not start_ts or not end_ts or not close_ts:
        return None
    kind, touch_side = tc.classify_touch(m.get("question") or "")
    ev = (m.get("events") or [{}])[0]
    return {
        "slug": slug,
        "question": m.get("question") or "",
        "description": (m.get("description") or "")[:500],
        "event_slug": ev.get("slug") or "",
        "yes_token": toks[0],
        "yes_won": yes_won,
        "volume": vol,
        "fee_rate": (m.get("feeSchedule") or {}).get("rate"),
        "neg_risk": bool(m.get("negRisk")),
        "start_ts": start_ts,
        "end_ts": end_ts,
        "close_ts": close_ts,
        "category": bb.classify(m.get("question") or ""),
        "kind": kind,
        "touch_side": touch_side,
    }


def fetch_histories(c):
    """(tail_hist, full_hist_or_None, had) — same shape as bt.fetch_trade."""
    tail_start = max(c["start_ts"], c["end_ts"] - 46 * 86400)
    tail = bt.fetch_range(c["yes_token"], tail_start, c["close_ts"])
    if not tail:
        return None, None, False
    tail_hist = sorted(tail.items())
    full_hist = None
    life = c["close_ts"] - c["start_ts"]
    if tail_start <= c["start_ts"]:
        full_hist = tail_hist
    elif life <= 170 * 86400:
        head = bt.fetch_range(c["yes_token"], c["start_ts"], tail_start)
        if head is not None:
            merged = dict(head)
            merged.update(tail)
            full_hist = sorted(merged.items())
    return tail_hist, full_hist, True


def first_entry_side(hist, end_ts, windowed, side, lo, hi):
    for t, p in hist:
        if not (0.0 < p < 1.0):
            continue
        if windowed:
            remain = end_ts - t
            if not (24 * 3600 <= remain <= 45 * 86400):
                continue
        price = p if side == "yes" else 1.0 - p
        if lo <= price <= hi:
            return t, side, price
    return None


def make_trade(c, hist, windowed, rule, side=None, cap=None):
    if side is not None:
        e_ = first_entry_side(hist, c["end_ts"], windowed, side, bb.BAND_LO, cap)
    else:
        e_ = bb.first_entry(hist, c["end_ts"], windowed=windowed)
    if not e_:
        return None
    t, s, price = e_
    tr = {k: c[k] for k in ("slug", "question", "event_slug", "yes_won",
                            "volume", "fee_rate", "neg_risk", "category",
                            "end_ts", "close_ts")}
    tr.update(entry_ts=t, side=s, entry_price=price, rule=rule,
              kind=c["kind"], touch_side=c["touch_side"],
              role=("touch" if s == c["touch_side"] else "survival"))
    won, fee, net = bb.pnl_for(tr)
    tr.update(won=won, fee=fee, net_pnl=net)
    return tr


def verdict(cell_trades, fixed_trades):
    m = bt.group_metrics(cell_trades)
    f = bt.group_metrics(fixed_trades)
    if m.get("n", 0) < 10:
        return "판정 불가 (n<10)"
    fails = []
    tm, fm = m.get("turnover_mean_daily"), f.get("turnover_mean_daily")
    td, fd = m.get("turnover_median_daily"), f.get("turnover_median_daily")
    if None in (tm, fm, td, fd) or not (tm > fm and td > fd):
        fails.append("회전율 우위 없음")
    if m.get("early_n"):
        loss_ratio = m["early_losses"] / m["early_n"]
        early_wr = m["early_wins"] / m["early_n"]
        if loss_ratio >= 0.70 or early_wr < m["win_rate"] - 0.20:
            fails.append("조기 종료가 손실에 몰림")
    if fails:
        return "채택 불성립 (" + ", ".join(fails) + ")"
    return "우선 가점 성립"


def self_check(fixed_trades):
    """판정 경로를 본 실행 전에 기준선 거래로 한 번 태워 조기 실패를 잡는다.

    2026-11-20 밤샘 런이 수집 완료 뒤 verdict의 KeyError로 죽은 재발 방지.
    """
    m = bt.group_metrics(fixed_trades)
    assert m.get("n", 0) >= 10, "baseline too small for self-check"
    verdict(fixed_trades, fixed_trades)
    for cap in SUB_CAPS:
        bt.group_metrics([t for t in fixed_trades
                          if t["entry_price"] <= cap])


def main():
    # Fixed baseline first (disk only): self-check the verdict path
    # before the long fetch, so analysis bugs fail in the first minute.
    base = json.load(open("bt_touch_band060_results.json"))
    base_trades = base["baseline_trades_main"] + base["baseline_trades_sens"]
    fixed_trades, base_occ = [], []
    for t in base_trades:
        kind, tside = tc.classify_touch(t["question"])
        if kind in OCC_KINDS:
            base_occ.append(t)
        else:
            fixed_trades.append(t)
    fixed_by_cat = defaultdict(list)
    for t in fixed_trades:
        fixed_by_cat[t["category"]].append(t)
    self_check(fixed_trades)

    markets, na, nb = bt.collect_markets()
    all_elig, occ = [], []
    kind_dist = Counter()
    for m in markets:
        c = eligible_all(m)
        if c is None:
            continue
        all_elig.append(c)
        kind_dist[c["kind"] or "none"] += 1
        if c["kind"] in OCC_KINDS:
            occ.append(c)
    occ.sort(key=lambda c: -c["volume"])
    truncated = max(0, len(occ) - MAX_FETCH)
    targets = occ[:MAX_FETCH]

    # Checkpoint: derived trades are saved every 200 targets and once at
    # the end of the fetch, so a crash or VM replacement during analysis
    # (or mid-fetch) resumes instead of restarting from zero.
    ckpt_path = "bt_touch_allfields_checkpoint.json"
    trades = sub_trades = None
    no_hist = no_entry = early_close_cands = 0
    missed_sub = {cap: 0 for cap in SUB_CAPS}

    def save_checkpoint():
        json.dump({"trades": trades, "sub_trades": sub_trades,
                   "missed_sub": missed_sub, "no_hist": no_hist,
                   "no_entry": no_entry,
                   "early_close_cands": early_close_cands},
                  open(ckpt_path, "w"))

    if os.path.exists(ckpt_path):
        d = json.load(open(ckpt_path))
        trades = d["trades"]
        sub_trades = {float(k): v for k, v in d["sub_trades"].items()}
        missed_sub = {float(k): v for k, v in d["missed_sub"].items()}
        no_hist, no_entry = d["no_hist"], d["no_entry"]
        early_close_cands = d["early_close_cands"]
        print(f"[allfields] resumed from checkpoint: {len(trades)} trades",
              flush=True)
    if trades is None:
        trades, sub_trades = [], {cap: [] for cap in SUB_CAPS}
        for i, c in enumerate(targets):
            if c["close_ts"] < c["end_ts"] - 86400:
                early_close_cands += 1
            tail_hist, full_hist, had = fetch_histories(c)
            if not had:
                no_hist += 1
                continue
            made = []
            for hist, windowed, rule in ((tail_hist, True, "main"),
                                         (full_hist, False, "sens")):
                if hist is None:
                    continue
                tr = make_trade(c, hist, windowed, rule)
                if tr:
                    made.append(tr)
                    trades.append(tr)
                surv_side = "no" if c["touch_side"] == "yes" else "yes"
                for cap in SUB_CAPS:
                    st = make_trade(c, hist, windowed, rule,
                                    side=surv_side, cap=cap)
                    if st:
                        sub_trades[cap].append(st)
            if not made:
                no_entry += 1
            else:
                surv_full = any(t["role"] == "survival" for t in made)
                if surv_full:
                    for cap in SUB_CAPS:
                        got = any(t["slug"] == c["slug"]
                                  for t in sub_trades[cap])
                        if not got:
                            missed_sub[cap] += 1
            if (i + 1) % 200 == 0:
                print(f"[allfields] {i + 1}/{len(targets)} processed, "
                      f"trades={len(trades)}", flush=True)
                save_checkpoint()
        save_checkpoint()

    # Cross cells: category x kind x role (occurrence run, 10k universe)
    cells = defaultdict(list)
    for t in trades:
        cells[(t["category"], t["kind"], t["role"])].append(t)
    cross = {f"{k[0]}|{k[1]}|{k[2]}": bt.group_metrics(v)
             for k, v in cells.items()}
    rollup_cat_role = defaultdict(list)
    for t in trades:
        rollup_cat_role[(t["category"], t["role"])].append(t)
    pooled_role = defaultdict(list)
    for t in trades:
        pooled_role[t["role"]].append(t)

    verdicts = {}
    for (cat, role), ts in rollup_cat_role.items():
        verdicts[f"{cat}|{role}"] = verdict(ts, fixed_by_cat.get(cat, []))
    for role, ts in pooled_role.items():
        verdicts[f"ALL|{role}"] = verdict(ts, fixed_trades)

    # Cheap-band cut (survival only)
    full_surv = [t for t in trades if t["role"] == "survival"]
    cheap = {}
    for cap in SUB_CAPS:
        ts = sub_trades[cap]
        m = bt.group_metrics(ts)
        f = bt.group_metrics(full_surv)
        ok = (m.get("n", 0) >= 10 and f.get("n", 0) > 0
              and m["net_pnl_sum"] / m["n"] > f["net_pnl_sum"] / f["n"]
              and m.get("turnover_mean_daily") is not None
              and f.get("turnover_mean_daily") is not None
              and m["turnover_mean_daily"] > f["turnover_mean_daily"])
        cheap[str(cap)] = {
            "metrics": m, "missed_vs_full_survival": missed_sub[cap],
            "verdict": "우위 성립" if ok else "우위 불성립",
            "by_category": {
                cat: bt.group_metrics([t for t in ts if t["category"] == cat])
                for cat in sorted({t["category"] for t in ts})},
            "crypto_barrier": bt.group_metrics(
                [t for t in ts if t["category"] == "crypto"
                 and t["kind"] == "price_barrier"]),
        }

    # Category boring/lazy/profit table over the baseline population (215)
    def derived(t):
        hold = max((t["close_ts"] - t["entry_ts"]) / 86400.0, 1e-9)
        return hold, (t["net_pnl"] / 100.0) / hold, \
            t["close_ts"] < t["end_ts"] - 86400

    cat_rows = {}
    by_cat_all = defaultdict(list)
    for t in base_trades:
        by_cat_all[t["category"]].append(t)
    for cat, ts in by_cat_all.items():
        ds = [derived(t) for t in ts]
        cat_rows[cat] = {
            "n": len(ts),
            "win_rate": sum(1 for t in ts if t["won"]) / len(ts),
            "pnl_per_trade": sum(t["net_pnl"] for t in ts) / len(ts),
            "turnover_mean_daily": statistics.mean(d[1] for d in ds),
            "avg_hold_days": statistics.mean(d[0] for d in ds),
            "early_share": sum(1 for d in ds if d[2]) / len(ts),
        }
    ranked = [c for c, r in cat_rows.items() if r["n"] >= 10]
    axes = {"win_rate": True, "calm": True, "avg_hold_days": True,
            "pnl_per_trade": True, "turnover_mean_daily": True}
    rank_sum = {c: 0 for c in ranked}
    for axis in axes:
        def keyf(c, axis=axis):
            r = cat_rows[c]
            return (1 - r["early_share"]) if axis == "calm" else r[axis]
        for i, c in enumerate(sorted(ranked, key=keyf, reverse=True)):
            rank_sum[c] += i + 1
    cat_table = {"rows": cat_rows, "rank_sum": rank_sum,
                 "note": "rank_sum over win_rate, calm(1-early_share), "
                         "avg_hold_days, pnl_per_trade, turnover_mean; "
                         "n>=10 only; lower is better"}

    # Audit sample (deterministic seed)
    rng = random.Random(20261119)
    classified = [c for c in all_elig if c["kind"] in OCC_KINDS]
    unclassified = [c for c in all_elig if c["kind"] not in OCC_KINDS]
    sample = {"classified": [], "unclassified": []}
    for pool, key in ((classified, "classified"), (unclassified, "unclassified")):
        for c in rng.sample(pool, min(len(pool), 40 if key == "classified" else 20)):
            sample[key].append({k: c[k] for k in
                                ("slug", "question", "description", "category",
                                 "kind", "touch_side", "volume")})
    with open("bt_touch_allfields_audit_sample.json", "w") as f:
        json.dump(sample, f, ensure_ascii=False, indent=1)

    meta = {
        "gamma_fetched": len(markets), "gamma_pass_default": na,
        "gamma_pass_volume_ordered": nb,
        "eligible_all": len(all_elig), "kind_distribution": dict(kind_dist),
        "occ_candidates": len(occ), "fetch_targets": len(targets),
        "truncated_by_cap": truncated, "no_history": no_hist,
        "no_entry": no_entry, "trades": len(trades),
        "baseline_trades_total": len(base_trades),
        "baseline_fixed_after_reclassification": len(fixed_trades),
        "baseline_occurrence_reclassified": len(base_occ),
        "occ_candidate_early_close_share":
            early_close_cands / len(targets) if targets else None,
        "http_calls": bt.STATS["http_calls"],
        "cache_hits": bt.STATS["cache_hits"],
        "provider_blocked": None,
        "stub_scan": bt.stub_scan(trades),
        "universe_note": "Gamma closed=true, endDate 2025-10-29..2026-10-29; "
                         "occurrence pool volume>=$10000 all categories, "
                         "classifier per SPEC; fixed baseline = band060 "
                         "baseline trades (volume>=$50000) reclassified, "
                         "band 0.60-0.90 both",
    }
    out = {"meta": meta, "cross": cross,
           "rollup_category_role": {f"{k[0]}|{k[1]}": bt.group_metrics(v)
                                    for k, v in rollup_cat_role.items()},
           "pooled_role": {k: bt.group_metrics(v)
                           for k, v in pooled_role.items()},
           "fixed_baseline": {"ALL": bt.group_metrics(fixed_trades),
                              **{c: bt.group_metrics(v)
                                 for c, v in fixed_by_cat.items()}},
           "verdicts": verdicts, "cheap_band": cheap,
           "category_table": cat_table, "trades": trades}
    with open("bt_touch_allfields_results.json", "w") as f:
        json.dump(out, f, ensure_ascii=False)
    if os.path.exists(ckpt_path):
        os.remove(ckpt_path)
    print(json.dumps(meta, ensure_ascii=False))
    print("verdicts:", json.dumps(verdicts, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except bt.ProviderBlocked as e:
        print(json.dumps({"provider_blocked": str(e)}), file=sys.stderr)
        sys.exit(2)
