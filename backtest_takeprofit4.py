#!/usr/bin/env python3
"""boringmech 익절 백테스트 4차 — 잔여 기간 R 5일 단위 스윕.

1~3차의 R 그리드 {없음, 7일, 30일, 90일}가 성겨서, 도입 판정 보류 후
R을 5일 단위로 촘촘히 스윕한다. 규칙·표본·가정은 1~3차와 동일하다.

- R ∈ {0(조건 없음), 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60일}
- 절대 X ∈ {0.90, 0.95, 0.98, 0.99}, 상대 Δ ∈ {0.25, 0.30, 0.35, 0.40}
  (판정가 0.99 캡 동일)
- 표본: A(실제 진입 52건) + B(가상 진입 140건). B 단독과 합산을 분리.
- 산출 1: 건별 그리드 (발동 n, 총손익 차이, 건당 평균 차이, 패배 회피)
- 산출 2: 슬롯 4 포트폴리오 시뮬레이션 (표본 B, 베이스라인 대비)

가격 경로는 1~3차 캐시에 이미 확보돼 있어 재수집하지 않는다.
네트워크 fetch 경로는 코드로 차단한다 (캐시 미스 건은 no_path로 분류).
표본 B의 수명은 최대 86.7일이라 R이 큰 구간은 발동이 구조적으로
줄어든다 — n을 그대로 남겨 판단 가능하게 한다.

잔여 기준은 기존과 동일: A는 예정 만기(endDate), B는 실제 정산
(closedTime). 베이스라인은 손절 없는 만기 보유, 포트폴리오도 손절
미적용. 매도가 = 도달가 - 스프레드 0.01, 수수료 정식 커브.
"""
import json
import os
import statistics
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
RESULTS_A = os.path.join(BASE, "bt_takeprofit_results.json")
RESULTS_2 = os.path.join(BASE, "bt_takeprofit2_results.json")
OUT = os.path.join(BASE, "bt_takeprofit4_results.json")

R_SWEEP = [None, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60]
ABS_X = [0.90, 0.95, 0.98, 0.99]
REL_D = [0.25, 0.30, 0.35, 0.40]


def rkey(r):
    return "none" if r is None else f"{r}d"


# ------------------------------------------------------------ 표본 로드
def load_abs_details():
    """절대 임계용: 1·2차 결과의 건별 hits (remain_days 포함) 그대로."""
    a = json.load(open(RESULTS_A))
    a_det = []
    for d in a["details"]:
        nd = dict(d)
        nd["source"] = "A_" + d["source"]
        a_det.append(nd)
    b = json.load(open(RESULTS_2))
    b_det = b["sample_B"]["details"]
    return a_det, b_det


def load_rel_details():
    """상대 임계용: 3차의 rel_hits 재계산을 재사용 (캐시만, 수집 차단)."""
    import backtest_takeprofit as bt1
    bt1.fetch_range_f1 = lambda *a, **k: None  # 재수집 금지
    import backtest_takeprofit3 as bt3
    a_det, a_np = bt3.load_a()
    b_det, b_np = bt3.load_b()
    return a_det, b_det, {"A": a_np, "B": b_np}


# ------------------------------------------------------------ 건별 그리드
def grid_abs(details):
    grid = {}
    for x in ABS_X:
        for r in R_SWEEP:
            fired = []
            for d in details:
                h = d["hits"][str(x)]
                if h is None or h.get("tp_pnl") is None:
                    continue
                if r is not None and h["remain_days"] < r:
                    continue
                fired.append((d, h))
            key = f"X={x}|R={rkey(r)}"
            grid[key] = summarize(fired)
    return grid


def grid_rel(details):
    grid = {}
    for dl in REL_D:
        for r in R_SWEEP:
            fired = []
            for d in details:
                h = d["rel_hits"][str(dl)]
                if h is None or h.get("tp_pnl") is None:
                    continue
                remain = (d["end_key_ts"] - h["t"]) / 86400
                if r is not None and remain < r:
                    continue
                hh = dict(h)
                hh["remain_days"] = round(remain, 2)
                fired.append((d, hh))
            key = f"D={dl}|R={rkey(r)}"
            grid[key] = summarize(fired)
    return grid


def summarize(fired):
    if not fired:
        return {"n_fired": 0}
    deltas = [h["tp_pnl"] - d["hold_pnl"] for d, h in fired]
    remains = [h["remain_days"] for d, h in fired]
    return {
        "n_fired": len(fired),
        "total_delta": round(sum(deltas), 4),
        "avg_delta_per_fired": round(sum(deltas) / len(deltas), 4),
        "avoided_losses": sum(1 for d, h in fired if not d["won"]),
        "early_release_days_avg": round(sum(remains) / len(remains), 2),
        "early_release_days_median": round(statistics.median(remains), 2),
    }


# ------------------------------------------------------- 포트폴리오 시뮬
def portfolio(details_b_abs, details_b_rel, slots_n=4):
    """표본 B 시간순, 슬롯 4개. 2·3차와 동일 방식, R만 스윕으로 교체."""
    cands = sorted(details_b_abs, key=lambda d: d["entry_ts"])
    rel_by_slug = {d["slug"]: d for d in details_b_rel}

    def run(hit_of):
        busy = [0] * slots_n
        total_pnl, entries, skipped, occ = 0.0, 0, 0, 0.0
        for d in cands:
            et = d["entry_ts"]
            slot = next((i for i in range(slots_n) if busy[i] <= et), None)
            if slot is None:
                skipped += 1
                continue
            exit_t, pnl = d["end_ts"], d["hold_pnl"]
            got = hit_of(d)
            if got is not None:
                exit_t, pnl = got
            busy[slot] = exit_t
            total_pnl += pnl
            entries += 1
            occ += (exit_t - et) / 86400
        return {"total_pnl": round(total_pnl, 4), "entries": entries,
                "skipped": skipped,
                "avg_occupancy_days": (round(occ / entries, 2)
                                       if entries else None)}

    baseline = run(lambda d: None)

    def with_base(res):
        res["delta_vs_baseline"] = round(
            res["total_pnl"] - baseline["total_pnl"], 4)
        return res

    abs_grid = {}
    for x in ABS_X:
        for r in R_SWEEP:
            def hit_of(d, x=x, r=r):
                h = d["hits"][str(x)]
                if (h is not None and h.get("tp_pnl") is not None
                        and (r is None or h["remain_days"] >= r)):
                    return h["t"], h["tp_pnl"]
                return None
            abs_grid[f"X={x}|R={rkey(r)}"] = with_base(run(hit_of))

    rel_grid = {}
    for dl in REL_D:
        for r in R_SWEEP:
            def hit_of(d, dl=dl, r=r):
                rd = rel_by_slug.get(d["slug"])
                if rd is None:
                    return None
                h = rd["rel_hits"][str(dl)]
                if h is None or h.get("tp_pnl") is None:
                    return None
                remain = (rd["end_key_ts"] - h["t"]) / 86400
                if r is None or remain >= r:
                    return h["t"], h["tp_pnl"]
                return None
            rel_grid[f"D={dl}|R={rkey(r)}"] = with_base(run(hit_of))

    return {"slots": slots_n, "candidates": len(cands),
            "baseline_no_takeprofit": baseline,
            "absolute": abs_grid, "relative": rel_grid}


def main():
    a_abs, b_abs = load_abs_details()
    a_rel, b_rel, no_path = load_rel_details()
    print(f"abs: A={len(a_abs)} B={len(b_abs)} / "
          f"rel: A={len(a_rel)} B={len(b_rel)} no_path={no_path}",
          flush=True)

    out = {
        "assumptions": [
            "4차는 1~3차와 같은 표본·가격 경로·가정 위에서 잔여 기간 "
            "R만 5일 단위로 스윕한 것이다. 가격 경로는 캐시만 사용했고 "
            "재수집은 코드로 차단했다 (no_path 건은 상대 임계 재계산에서 "
            "경로를 못 읽은 건).",
            "R=0(none)은 잔여 조건 없음. 잔여 기준: 표본 A는 예정 만기"
            "(endDate), 표본 B는 실제 정산(closedTime) — 기존과 동일.",
            "표본 B 마켓 수명은 60.2~86.7일이라 도달 시점 잔여가 큰 "
            "구간(R 45일 이상)은 발동 표본이 구조적으로 얇다. n을 "
            "그대로 표기했다.",
            "절대 X는 {0.90, 0.95, 0.98, 0.99}만 스윕했다 (1·2차 전체 "
            "그리드의 부분집합). 상대 Δ는 3차와 동일 {0.25~0.40}, "
            "판정가 0.99 캡 동일.",
            "베이스라인은 손절 없는 만기 보유, 포트폴리오 시뮬레이션도 "
            "손절 미적용. 매도가 = 도달가 - 0.01, 수수료 정식 커브.",
            "합산(A+B) 그리드는 자본 규모가 다른 두 표본(A 실금액, "
            "B 건당 $100 정규화)의 단순 합이다 — 2차와 동일 한계.",
        ],
        "r_sweep_days": [0 if r is None else r for r in R_SWEEP],
        "samples": {"A": len(a_abs), "B": len(b_abs),
                    "rel_A": len(a_rel), "rel_B": len(b_rel),
                    "rel_no_path": no_path},
        "per_trade": {
            "absolute": {
                "B": grid_abs(b_abs),
                "A": grid_abs(a_abs),
                "combined": grid_abs(a_abs + b_abs),
            },
            "relative": {
                "B": grid_rel(b_rel),
                "A": grid_rel(a_rel),
                "combined": grid_rel(a_rel + b_rel),
            },
        },
        "portfolio_sim_B": portfolio(b_abs, b_rel),
    }
    with open(OUT, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("written:", OUT)

    ps = out["portfolio_sim_B"]
    print("== portfolio baseline ==", ps["baseline_no_takeprofit"])
    for fam in ("absolute", "relative"):
        print(f"== per-trade B ({fam}) ==")
        for k, v in out["per_trade"][fam]["B"].items():
            print(k, v)
        print(f"== per-trade combined ({fam}) ==")
        for k, v in out["per_trade"][fam]["combined"].items():
            print(k, v)
        print(f"== portfolio B ({fam}) ==")
        for k, v in ps[fam].items():
            print(k, v)


if __name__ == "__main__":
    raise SystemExit(main())
