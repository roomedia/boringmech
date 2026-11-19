#!/usr/bin/env python3
"""boringmech 익절 백테스트 3차 — 상대 임계 (진입가 + Δ) 버전.

2차(절대 임계 X) 결과 확정 후, 같은 표본(A 실제 진입 + B 가상 진입)에
대해 매도 판정가를 진입가 + Δ 로 두는 상대 임계를 같은 방식으로 측정한다.

- Δ ∈ {0.25, 0.30, 0.35, 0.40}, 판정가는 0.99로 캡한다.
  진입가 + Δ > 0.99 인 건은 캡 적용 건으로, > 1.0 인 건은 캡 없이는
  발동 불가능한 건으로 세어 결과에 명시한다.
- 잔여 조건 R ∈ {없음, 7일, 30일, 90일}, 도달 시 (도달가 - 스프레드
  0.01) 전량 매도, 수수료 정식 커브 — 전부 1·2차와 동일.
- 산출: 건별 그리드 (A 단독 / B 단독 / 합산), Δ별 도달 후 패배율,
  스프레드 민감도와 부호 반전, 슬롯 4개 포트폴리오 시뮬레이션 (표본 B,
  2차와 동일 방식, 손절 미적용).
- 결과는 bt_takeprofit2_results.json 의 relative_threshold 섹션에
  추가한다 (절대 임계 결과와 한 파일에서 비교 가능하게).

가격 경로: 표본 A는 1차 캐시 체계 (backtest_takeprofit.load_history
재사용), 표본 B는 takeprofit 캐시의 전 구간 파일.
"""
import json
import os
import statistics
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
CACHE_TP = os.path.expanduser("~/workspace/data-cache/boringmech-takeprofit")
RESULTS_A = os.path.join(BASE, "bt_takeprofit_results.json")
RESULTS_2 = os.path.join(BASE, "bt_takeprofit2_results.json")

GRID_D = [0.25, 0.30, 0.35, 0.40]
GRID_R = [None, 7, 30, 90]
SPREAD = 0.01
CAP = 0.99


def fee_of(shares, rate, p):
    return shares * rate * p * (1 - p)


def rel_hits(entry_price, series, shares, rate, cost):
    """Δ별 첫 도달 (trigger는 0.99 캡). series: 진입 이후 (t, side_price)."""
    out = {}
    for d in GRID_D:
        trig = min(entry_price + d, CAP)
        h = next(((t, p) for t, p in series if p >= trig), None)
        if h is None:
            out[str(d)] = None
        else:
            t_hit, p_hit = h
            sp = p_hit - SPREAD
            tp = (round(shares * sp - fee_of(shares, rate, sp) - cost, 4)
                  if sp > 0 else None)
            out[str(d)] = {"t": t_hit, "price": p_hit, "trigger": trig,
                           "tp_pnl": tp}
    return out


def cap_stats(details):
    out = {}
    for d in GRID_D:
        capped = sum(1 for x in details if x["entry_price"] + d > CAP)
        impossible = sum(1 for x in details if x["entry_price"] + d > 1.0)
        out[str(d)] = {"capped": capped,
                       "impossible_without_cap": impossible,
                       "n": len(details)}
    return out


def grid_of(details, end_key):
    reach, grid = {}, {}
    for d in GRID_D:
        reached = [x for x in details if x["rel_hits"][str(d)]]
        lost = [x for x in reached if not x["won"]]
        reach[str(d)] = {
            "reached": len(reached), "lost_after_reach": len(lost),
            "loss_rate_after_reach": (round(len(lost) / len(reached), 4)
                                      if reached else None)}
    for d in GRID_D:
        for r in GRID_R:
            fired = []
            for x in details:
                h = x["rel_hits"][str(d)]
                if h is None or h.get("tp_pnl") is None:
                    continue
                h = dict(h)
                h["remain_days"] = round((x[end_key] - h["t"]) / 86400, 2)
                if r is not None and h["remain_days"] < r:
                    continue
                fired.append((x, h))
            key = f"D={d}|R={'none' if r is None else str(r) + 'd'}"
            if not fired:
                grid[key] = {"n_fired": 0}
                continue
            deltas = [h["tp_pnl"] - x["hold_pnl"] for x, h in fired]
            remains = [h["remain_days"] for x, h in fired]
            grid[key] = {
                "n_fired": len(fired),
                "avg_delta_per_fired": round(sum(deltas) / len(deltas), 4),
                "total_delta": round(sum(deltas), 4),
                "avoided_losses": sum(1 for x, h in fired if not x["won"]),
                "early_release_days_avg": round(sum(remains) / len(remains), 2),
                "early_release_days_median": round(
                    statistics.median(remains), 2)}
    return reach, grid


def sensitivity_of(details, end_key):
    out = {}
    for spread in (0.0, 0.02):
        for d in GRID_D:
            for r in GRID_R:
                deltas = []
                for x in details:
                    h = x["rel_hits"][str(d)]
                    if h is None:
                        continue
                    remain = (x[end_key] - h["t"]) / 86400
                    if r is not None and remain < r:
                        continue
                    sp = h["price"] - spread
                    if sp <= 0:
                        continue
                    tp = (x["shares"] * sp
                          - fee_of(x["shares"], x["rate"], sp) - x["cost"])
                    deltas.append(tp - x["hold_pnl"])
                if deltas:
                    out[f"spread={spread}|D={d}|R="
                        f"{'none' if r is None else r}"] = {
                        "n_fired": len(deltas),
                        "total_delta": round(sum(deltas), 4)}
    return out


def flips_of(grid, sensitivity):
    flips = []
    for d in GRID_D:
        for r in GRID_R:
            key = f"D={d}|R={'none' if r is None else str(r) + 'd'}"
            g = grid.get(key) or {}
            if not g.get("n_fired"):
                continue
            pos = g["total_delta"] > 0
            for spread in (0.0, 0.02):
                sv = sensitivity.get(
                    f"spread={spread}|D={d}|R={'none' if r is None else r}")
                if sv and (sv["total_delta"] > 0) != pos:
                    flips.append({"combo": f"D={d}|R={r}",
                                  "spread_alt": spread,
                                  "total_delta_alt": sv["total_delta"],
                                  "total_delta_base": g["total_delta"]})
    return flips


def portfolio_sim_rel(details_b, slots_n=4):
    """2차 portfolio_sim과 동일 방식, 판정만 상대 임계로 교체."""
    cands = sorted(details_b, key=lambda d: d["entry_ts"])

    def run_rule(dlt=None, r=None):
        busy = [0] * slots_n
        total_pnl, entries, skipped, occ = 0.0, 0, 0, 0.0
        for x in cands:
            et = x["entry_ts"]
            slot = next((i for i in range(slots_n) if busy[i] <= et), None)
            if slot is None:
                skipped += 1
                continue
            exit_t, pnl = x["end_ts"], x["hold_pnl"]
            if dlt is not None:
                h = x["rel_hits"][str(dlt)]
                if h is not None and h.get("tp_pnl") is not None:
                    remain = (x["end_ts"] - h["t"]) / 86400
                    if r is None or remain >= r:
                        exit_t, pnl = h["t"], h["tp_pnl"]
            busy[slot] = exit_t
            total_pnl += pnl
            entries += 1
            occ += (exit_t - et) / 86400
        return {"total_pnl": round(total_pnl, 4), "entries": entries,
                "skipped": skipped,
                "avg_occupancy_days": (round(occ / entries, 2)
                                       if entries else None)}

    out = {"slots": slots_n, "candidates": len(cands),
           "no_takeprofit": run_rule()}
    grid = {}
    for d in GRID_D:
        for r in GRID_R:
            key = f"D={d}|R={'none' if r is None else str(r) + 'd'}"
            grid[key] = run_rule(d, r)
    out["grid"] = grid
    return out


def load_a():
    import backtest_takeprofit as bt1
    a = json.load(open(RESULTS_A))
    details, no_path = [], []
    for d in a["details"]:
        end = max(d["end_ts"], d["entry_ts"])
        pts, reso = bt1.load_history(d["token"], d["entry_ts"] - 3600,
                                     end + 3600, d["outcome"] == "yes")
        if not pts:
            no_path.append({"slug": d["slug"], "reason": reso})
            continue
        series = [(t, p) for t, p in pts if d["entry_ts"] <= t <= end]
        nd = {k: d[k] for k in ("slug", "outcome", "entry_price", "shares",
                                "cost", "rate", "won", "hold_pnl",
                                "entry_ts", "end_ts")}
        nd["source"] = "A_" + d["source"]
        nd["rel_hits"] = rel_hits(d["entry_price"], series, d["shares"],
                                  d["rate"], d["cost"])
        # remain 계산용 종료 시각 키 통일
        nd["end_key_ts"] = d["end_ts"]
        details.append(nd)
    return details, no_path


def load_b():
    r2 = json.load(open(RESULTS_2))
    details, no_path = [], []
    for d in r2["sample_B"]["details"]:
        tp = os.path.join(CACHE_TP, d["token"] + ".json")
        if not os.path.exists(tp):
            no_path.append({"slug": d["slug"], "reason": "cache_missing"})
            continue
        h = json.load(open(tp)).get("history", [])
        side = d["side_idx"]
        series = [(x["t"], float(x["p"]) if side == 0
                   else round(1 - float(x["p"]), 6))
                  for x in h if x["t"] >= d["entry_ts"]]
        nd = {k: d[k] for k in ("slug", "outcome", "entry_price", "shares",
                                "cost", "rate", "won", "hold_pnl",
                                "entry_ts")}
        nd["source"] = "virtualB"
        nd["end_key_ts"] = d["end_ts"]  # B의 end_ts는 closedTime
        nd["end_ts"] = d["end_ts"]
        nd["rel_hits"] = rel_hits(d["entry_price"], series, d["shares"],
                                  d["rate"], d["cost"])
        details.append(nd)
    return details, no_path


def main():
    a_det, a_np = load_a()
    b_det, b_np = load_b()
    print(f"A={len(a_det)} (no_path {len(a_np)}), B={len(b_det)} "
          f"(no_path {len(b_np)})", flush=True)

    section = {"assumptions": [
        "상대 임계: 매도 판정가 = 진입가 + Δ (Δ ∈ 0.25/0.30/0.35/0.40), "
        "0.99 캡 적용. 캡 적용 건수와 캡 없이는 발동 불가능한 건수"
        "(진입가 + Δ > 1.0)는 cap_stats에 표본별로 명시했다.",
        "표본·가격 경로·수수료·스프레드 가정은 1·2차와 동일하다. "
        "표본 A의 잔여는 예정 만기(endDate), 표본 B의 잔여는 실제 정산"
        "(closedTime) 기준인 점도 2차와 동일하다.",
        "포트폴리오 시뮬레이션은 표본 B 대상 슬롯 4개, 2차와 동일 "
        "방식이며 손절(-0.25)은 적용하지 않았다.",
    ]}
    for name, det in (("A", a_det), ("B", b_det), ("combined", a_det + b_det)):
        reach, grid = grid_of(det, "end_key_ts")
        sens = sensitivity_of(det, "end_key_ts")
        section[name] = {
            "n": len(det), "cap_stats": cap_stats(det),
            "reach_stats": reach, "grid": grid,
            "sensitivity_spread": sens,
            "sign_flips_vs_base": flips_of(grid, sens),
        }
    section["portfolio_sim_B"] = portfolio_sim_rel(b_det)
    section["no_path"] = {"A": a_np, "B": b_np}

    r2 = json.load(open(RESULTS_2))
    r2["relative_threshold"] = section
    with open(RESULTS_2, "w") as f:
        json.dump(r2, f, ensure_ascii=False, indent=1)
    print("written: relative_threshold section ->", RESULTS_2)
    for name in ("A", "B", "combined"):
        print(f"== {name} cap_stats ==", section[name]["cap_stats"])
        print(f"== {name} reach ==", json.dumps(section[name]["reach_stats"]))
        for k, v in section[name]["grid"].items():
            print(name, k, v)
        print(f"== {name} flips ==", section[name]["sign_flips_vs_base"])
    print("== portfolio B (relative) ==")
    print("no_takeprofit", section["portfolio_sim_B"]["no_takeprofit"])
    for k, v in section["portfolio_sim_B"]["grid"].items():
        print(k, v)


if __name__ == "__main__":
    raise SystemExit(main())
