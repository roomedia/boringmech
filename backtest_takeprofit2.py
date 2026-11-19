#!/usr/bin/env python3
"""boringmech 익절 임계 백테스트 2차 — 표본 B (가상 진입, 장기 마켓) 확장.

1차(backtest_takeprofit.py)는 실제 진입 정산 52건으로 측정했지만, 정산
표본이 단기 마켓 위주라 "임계 도달 시 잔여 30일 이상" 구간 발동이 0건
이었다. 2차는 정산 완료된 장기 마켓(수명 60일 이상, 스포츠 제외)에
가상 진입을 적용해 장기 잔여 구간을 채운다.

표본 B 구성:
- Gamma API closed 마켓 페이지네이션으로 후보 풀 수집. 필터: 정산 완료
  (umaResolutionStatus=resolved, outcomePrices 결정적), 수명
  (closedTime - startDate) 60일 이상, feeType에 sport 포함 시 제외
  (앱의 스포츠 제외 규칙과 동일 기준), 이진 마켓만.
- 가격 경로: CLOB prices-history (fidelity=60 시간봉), 구간 분할 수집.
  ~/workspace/data-cache/boringmech-takeprofit/ 에 토큰ID 파일로 캐시.
  403/429 첫 발생 시 그 공급자 수집을 즉시 중단 (하드스톱 규칙).

가상 진입 규칙:
- Yes 토큰 가격 p에 대해 진입 쪽 가격 (p 또는 1-p)이 실제 매수 밴드
  0.60~0.90 안에 처음 들어온 포인트를 진입으로 잡는다 (마켓당 1회).
- 진입가는 그 포인트의 진입 쪽 가격. 건당 자본 $100 명목 정규화:
  shares = 100 / entry, cost = 100 + 매수 수수료(정식 커브).
- 거래량 등 당시 스캔 조건은 소급 복원이 안 되므로 가격 조건만으로
  근사한다. 경로 포인트가 20개 미만인 마켓은 데이터 품질상 제외한다.

시뮬레이션 (1차와 동일):
- 진입 이후 진입 쪽 가격이 처음 X 이상이 된 포인트에서 도달 판정.
- 도달 시점 잔여(closedTime - 도달 시각)가 R 이상이면 도달가에서
  스프레드 0.01을 차감한 값으로 전량 매도한 것으로 계산.
- 수수료: 정식 커브 fee = shares x rate x p x (1-p), 매도 시 1회.
  rate는 Gamma feeSchedule.rate (없으면 0).
- 미도달·조건 미달이면 만기 보유 (승 1.0 / 패 0). 승패는 Gamma
  outcomePrices로 확정하고 경로 마지막 가격과 대조해 플래그를 남긴다.

한계 (결과 파일에도 명시):
- 가상 진입 표본이다. 실제 boringmech 진입은 거래량·스프레드·스캔
  타이밍 조건이 더 있어, 가상 진입 시점·가격이 실진입과 다를 수 있다.
- 생존 편향: 정산 완료 마켓만 소급 수집했다. 도중에 상장폐지·무효화된
  마켓은 풀에 없다.
- 표본 B의 잔여 기간은 실제 정산 시각(closedTime) 기준이다. 1차 표본
  A는 예정 만기(endDate) 기준이라 합산 시 기준 차이가 있다 (A는 대부분
  정산이 만기 직후라 차이 작음).
- 베이스라인은 손절 없는 순수 만기 보유다 (1차와 동일).
"""
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = os.path.dirname(os.path.abspath(__file__))
CACHE_TP = os.path.expanduser("~/workspace/data-cache/boringmech-takeprofit")
RESULTS_A = os.path.join(BASE, "bt_takeprofit_results.json")
os.makedirs(CACHE_TP, exist_ok=True)

GAMMA = "https://gamma-api.polymarket.com/markets"
CLOB = "https://clob.polymarket.com/prices-history"
UA = {"User-Agent": "Mozilla/5.0"}

GRID_X = [0.90, 0.92, 0.94, 0.95, 0.96, 0.97, 0.98, 0.99]
GRID_R = [None, 7, 30, 90]  # days
SPREAD = 0.01
BAND_LO, BAND_HI = 0.60, 0.90
CAPITAL = 100.0
MIN_LIFE_DAYS = 60
MIN_POINTS = 20
TARGET_MEASURED = 140
TARGET_R30_X095 = 40
MAX_OFFSET = 5000

hardstop = {"hit": False, "where": None}


def iso_ts(s):
    return int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp())


def jload(v):
    if isinstance(v, str):
        try:
            return json.loads(v)
        except Exception:  # noqa: BLE001
            return None
    return v


def get_json(url, params=None, tries=3):
    if params:
        url = url + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=UA)
    for i in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.loads(r.read()), 200
        except urllib.error.HTTPError as e:
            if e.code in (403, 429):
                hardstop["hit"] = True
                hardstop["where"] = f"{url.split('?')[0]} http_{e.code}"
                return None, e.code
            body = ""
            try:
                body = e.read().decode(errors="replace")
            except Exception:  # noqa: BLE001
                pass
            if e.code == 400 and "too long" in body:
                return None, "too_long"
            time.sleep(2 * (i + 1))
        except Exception:  # noqa: BLE001
            time.sleep(2 * (i + 1))
    return None, "failed"


# ---------------------------------------------------------------- 후보 풀
def collect_candidates():
    cands, scanned = [], 0
    off = 0
    while off < MAX_OFFSET and not hardstop["hit"]:
        ms, code = get_json(GAMMA, {"closed": "true", "limit": 100,
                                    "offset": off, "order": "endDate",
                                    "ascending": "false"})
        if not ms:
            break
        scanned += len(ms)
        for m in ms:
            if not m.get("closedTime") or not m.get("startDate"):
                continue
            if m.get("umaResolutionStatus") != "resolved":
                continue
            op = jload(m.get("outcomePrices"))
            if not op or sorted(str(x) for x in op) != ["0", "1"]:
                continue
            if "sport" in (m.get("feeType") or "").lower():
                continue
            outcomes = jload(m.get("outcomes"))
            tokens = jload(m.get("clobTokenIds"))
            if not outcomes or not tokens or len(outcomes) != 2:
                continue
            start, closed = iso_ts(m["startDate"]), iso_ts(m["closedTime"])
            life = (closed - start) / 86400
            if life < MIN_LIFE_DAYS:
                continue
            rate = ((m.get("feeSchedule") or {}).get("rate")) or 0.0
            cands.append({
                "slug": m["slug"], "question": m.get("question"),
                "fee_type": m.get("feeType"), "rate": rate,
                "outcomes": outcomes, "tokens": tokens,
                "winner_idx": [str(x) for x in op].index("1"),
                "start_ts": start, "closed_ts": closed,
                "end_ts": iso_ts(m["endDate"]) if m.get("endDate") else closed,
                "life_days": round(life, 1),
                "volume_clob": m.get("volumeClob"),
            })
        off += 100
    # 수명 60일 이상 중 짧은 쪽부터 처리 (마켓당 수집 비용이 수명에 비례.
    # 60~200일 마켓도 잔여 30/90일 구간을 충분히 만든다)
    cands.sort(key=lambda c: c["life_days"])
    return cands, scanned


# ---------------------------------------------------------------- 가격 경로
def fetch_chunk(token, s, e, depth=0):
    """fidelity=60 구간 수집. 'too long' 400이면 반으로 나눈다."""
    if hardstop["hit"]:
        return None
    if e - s < 3600:
        return []
    h, code = get_json(CLOB, {"market": token, "startTs": int(s),
                              "endTs": int(e), "fidelity": 60})
    if h is not None:
        return sorted((pt["t"], float(pt["p"])) for pt in h.get("history", []))
    if hardstop["hit"]:
        return None
    if code == "too_long" and depth < 6 and e - s > 86400:
        mid = (s + e) // 2
        a = fetch_chunk(token, s, mid, depth + 1)
        if a is None:
            return None
        b = fetch_chunk(token, mid, e, depth + 1)
        if b is None:
            return None
        return sorted(a + b)
    return None


def load_history_full(token, s, e):
    """캐시 우선 (전 구간 커버 시), 아니면 전 구간 수집 후 캐시 저장."""
    tp = os.path.join(CACHE_TP, token + ".json")
    if os.path.exists(tp):
        h = json.load(open(tp)).get("history", [])
        pts = [(x["t"], float(x["p"])) for x in h]
        if pts and pts[0][0] <= s + 86400 and pts[-1][0] >= e - 86400:
            return pts, "cache"
    if hardstop["hit"]:
        return None, "skipped_after_hardstop"
    step = 14 * 86400
    spans = []
    cur = s
    while cur < e:
        spans.append((cur, min(e, cur + step)))
        cur += step
    from concurrent.futures import ThreadPoolExecutor
    pts = []
    with ThreadPoolExecutor(max_workers=6) as ex:
        parts = list(ex.map(lambda se: fetch_chunk(token, se[0], se[1]),
                            spans))
    if any(p is None for p in parts):
        return None, "fetch_failed"
    for p in parts:
        pts.extend(p)
    pts = sorted(set(pts))
    if pts:
        json.dump({"history": [{"t": t, "p": p} for t, p in pts]},
                  open(tp, "w"))
    return pts, "fetched"


# ---------------------------------------------------------------- 가상 진입
def virtual_entry(pts):
    """반환: (side_idx, entry_t, entry_price) 또는 None."""
    for t, p in pts:
        if BAND_LO <= p <= BAND_HI:
            return 0, t, p
        if BAND_LO <= 1 - p <= BAND_HI:
            return 1, t, round(1 - p, 6)
    return None


def fee_of(shares, rate, p):
    return shares * rate * p * (1 - p)


def simulate_detail(c, pts):
    ent = virtual_entry(pts)
    if ent is None:
        return None, "no_band_entry"
    side, entry_t, entry_p = ent
    series = [(t, p if side == 0 else round(1 - p, 6))
              for t, p in pts if t >= entry_t]
    if len(series) < 3:
        return None, "path_too_short_after_entry"
    shares = CAPITAL / entry_p
    cost = CAPITAL + fee_of(shares, c["rate"], entry_p)
    won = (side == c["winner_idx"])
    last_side = series[-1][1]
    disagree = (last_side >= 0.99) != won
    hold_pnl = round(shares * (1.0 if won else 0.0) - cost, 4)
    hits = {}
    for x in GRID_X:
        h = next(((t, p) for t, p in series if p >= x), None)
        if h is None:
            hits[str(x)] = None
        else:
            t_hit, p_hit = h
            sp = p_hit - SPREAD
            tp = (round(shares * sp - fee_of(shares, c["rate"], sp) - cost, 4)
                  if sp > 0 else None)
            hits[str(x)] = {
                "t": t_hit, "price": p_hit,
                "remain_days": round((c["closed_ts"] - t_hit) / 86400, 2),
                "tp_pnl": tp,
            }
    rec = {
        "source": "virtualB", "slug": c["slug"],
        "outcome": c["outcomes"][side],
        "token": c["tokens"][0], "side_idx": side,
        "fee_type": c["fee_type"], "rate": c["rate"],
        "life_days": c["life_days"], "volume_clob": c["volume_clob"],
        "entry_price": round(entry_p, 6), "entry_ts": entry_t,
        "entry_remain_days": round((c["closed_ts"] - entry_t) / 86400, 2),
        "shares": round(shares, 4), "cost": round(cost, 4),
        "won": won, "won_source": "gamma_outcomePrices",
        "last_price_side": last_side, "reverse_disagree": disagree,
        "hold_pnl": hold_pnl, "hits": hits,
        "start_ts": c["start_ts"], "end_ts": c["closed_ts"],
    }
    return rec, "ok"


# ------------------------------------------------------- 포트폴리오 시뮬
def portfolio_sim(details, slots_n=4):
    """슬롯 4개 고정 포트폴리오 시뮬레이션 (표본 B 가상 진입 후보 대상).

    후보를 진입 시점 시간순으로 처리한다. 빈 슬롯이 있을 때만 진입하고
    슬롯이 다 차 있으면 그 후보는 건너뛴다 (규칙별 skipped로 기록).
    슬롯당 자본은 표본 B 정규화와 동일하게 건당 $100 명목으로 균일하다.
    익절 규칙이 발동하면 그 시점에 슬롯이 비어 이후 후보를 받을 수 있다.
    손절(-0.25)은 적용하지 않는다 (만기 보유 베이스라인과 동일 가정).
    """
    cands = sorted(details, key=lambda d: d["entry_ts"])

    def run_rule(x=None, r=None):
        busy = [0] * slots_n  # 슬롯별 해제 시각
        total_pnl, entries, skipped = 0.0, 0, 0
        occ_days = 0.0
        for d in cands:
            et = d["entry_ts"]
            slot = next((i for i in range(slots_n) if busy[i] <= et), None)
            if slot is None:
                skipped += 1
                continue
            exit_t, pnl = d["end_ts"], d["hold_pnl"]
            if x is not None:
                h = d["hits"][str(x)]
                if (h is not None and h.get("tp_pnl") is not None
                        and (r is None or h["remain_days"] >= r)):
                    exit_t, pnl = h["t"], h["tp_pnl"]
            busy[slot] = exit_t
            total_pnl += pnl
            entries += 1
            occ_days += (exit_t - et) / 86400
        return {
            "total_pnl": round(total_pnl, 4), "entries": entries,
            "skipped": skipped,
            "avg_occupancy_days": (round(occ_days / entries, 2)
                                   if entries else None),
        }

    out = {"slots": slots_n, "candidates": len(cands),
           "no_takeprofit": run_rule()}
    grid = {}
    for x in GRID_X:
        for r in GRID_R:
            key = f"X={x}|R={'none' if r is None else str(r) + 'd'}"
            grid[key] = run_rule(x, r)
    out["grid"] = grid
    return out


# ---------------------------------------------------------------- 그리드
def grid_of(details):
    reach, grid = {}, {}
    for x in GRID_X:
        reached = [d for d in details if d["hits"][str(x)]]
        lost = [d for d in reached if not d["won"]]
        reach[str(x)] = {
            "reached": len(reached), "lost_after_reach": len(lost),
            "loss_rate_after_reach": (round(len(lost) / len(reached), 4)
                                      if reached else None),
        }
    for x in GRID_X:
        for r in GRID_R:
            fired = []
            for d in details:
                h = d["hits"][str(x)]
                if h is None or h.get("tp_pnl") is None:
                    continue
                if r is not None and h["remain_days"] < r:
                    continue
                fired.append((d, h))
            key = f"X={x}|R={'none' if r is None else str(r) + 'd'}"
            if not fired:
                grid[key] = {"n_fired": 0}
                continue
            deltas = [h["tp_pnl"] - d["hold_pnl"] for d, h in fired]
            remains = [h["remain_days"] for d, h in fired]
            grid[key] = {
                "n_fired": len(fired),
                "avg_delta_per_fired": round(sum(deltas) / len(deltas), 4),
                "total_delta": round(sum(deltas), 4),
                "avoided_losses": sum(1 for d, h in fired if not d["won"]),
                "early_release_days_avg": round(sum(remains) / len(remains), 2),
                "early_release_days_median": round(
                    statistics.median(remains), 2),
            }
    return reach, grid


def sensitivity_of(details):
    """스프레드 0 / 0.02 총차이. 부호 판정용."""
    out = {}
    for spread in (0.0, 0.02):
        for x in GRID_X:
            for r in GRID_R:
                deltas = []
                for d in details:
                    h = d["hits"][str(x)]
                    if h is None:
                        continue
                    if r is not None and h["remain_days"] < r:
                        continue
                    sp = h["price"] - spread
                    if sp <= 0:
                        continue
                    tp = (d["shares"] * sp
                          - fee_of(d["shares"], d["rate"], sp) - d["cost"])
                    deltas.append(tp - d["hold_pnl"])
                if deltas:
                    out[f"spread={spread}|X={x}|R="
                        f"{'none' if r is None else r}"] = {
                        "n_fired": len(deltas),
                        "total_delta": round(sum(deltas), 4)}
    return out


def flips_of(grid, sensitivity):
    flips = []
    for x in GRID_X:
        for r in GRID_R:
            key = f"X={x}|R={'none' if r is None else str(r) + 'd'}"
            g = grid.get(key) or {}
            if not g.get("n_fired"):
                continue
            pos = g["total_delta"] > 0
            for spread in (0.0, 0.02):
                sv = sensitivity.get(
                    f"spread={spread}|X={x}|R={'none' if r is None else r}")
                if sv and (sv["total_delta"] > 0) != pos:
                    flips.append({"combo": f"X={x}|R={r}",
                                  "spread_alt": spread,
                                  "total_delta_alt": sv["total_delta"],
                                  "total_delta_base": g["total_delta"]})
    return flips


def a_details_normalized():
    """1차 표본 A 상세를 그리드 계산용 형태로 변환 (hits에 shares/rate/cost 필요)."""
    a = json.load(open(RESULTS_A))
    out = []
    for d in a["details"]:
        nd = dict(d)
        nd["source"] = "A_" + d["source"]
        out.append(nd)
    return out


def main():
    cands, scanned = collect_candidates()
    print(f"gamma scanned={scanned}, candidates={len(cands)}, "
          f"hardstop={hardstop}", flush=True)

    details, excluded = [], []
    fired_r30_095 = 0
    for c in cands:
        if hardstop["hit"]:
            excluded.append({"slug": c["slug"],
                             "reason": "skipped_after_hardstop"})
            continue
        if len(details) >= TARGET_MEASURED and fired_r30_095 >= TARGET_R30_X095:
            break
        pts, reso = load_history_full(c["tokens"][0], c["start_ts"],
                                      c["closed_ts"])
        if not pts:
            excluded.append({"slug": c["slug"], "reason": reso})
            continue
        if len(pts) < MIN_POINTS:
            excluded.append({"slug": c["slug"],
                             "reason": f"too_few_points={len(pts)}"})
            continue
        rec, st = simulate_detail(c, pts)
        if rec is None:
            excluded.append({"slug": c["slug"], "reason": st})
            continue
        rec["resolution"] = reso
        details.append(rec)
        h = rec["hits"].get("0.95")
        if h and h["remain_days"] >= 30:
            fired_r30_095 += 1
        if len(details) % 20 == 0:
            print(f"measured_B={len(details)}, fired_R30_X0.95="
                  f"{fired_r30_095}", flush=True)
    print(f"measured_B={len(details)}, excluded_B={len(excluded)}, "
          f"fired_R30_X0.95={fired_r30_095}, hardstop={hardstop}", flush=True)

    reach_b, grid_b = grid_of(details)
    sens_b = sensitivity_of(details)
    flips_b = flips_of(grid_b, sens_b)

    a_det = a_details_normalized()
    combined = a_det + details
    reach_c, grid_c = grid_of(combined)
    sens_c = sensitivity_of(combined)
    flips_c = flips_of(grid_c, sens_c)

    assumptions = [
        "표본 B는 가상 진입 표본이다. 정산 완료 장기 마켓의 가격 경로에 "
        "대해 진입 쪽 가격이 실제 매수 밴드 0.60~0.90에 처음 들어온 "
        "포인트를 진입으로 잡았다 (마켓당 1회). 실제 boringmech 진입은 "
        "거래량·스프레드·스캔 타이밍 등 당시 조건이 더 있어 소급 복원이 "
        "안 되므로, 가격 조건만으로 근사한 진입이다. 실진입 시점·가격과 "
        "다를 수 있다.",
        "생존 편향: 정산 완료된 마켓만 Gamma에서 소급 수집했다. 도중 "
        "상장폐지·무효·취소된 마켓은 풀에 포함되지 않는다.",
        "표본 B 필터: 마켓 수명(closedTime - startDate) 60일 이상, "
        "feeType에 sport 포함 시 제외 (앱 규칙과 동일), 이진 마켓, "
        "umaResolutionStatus=resolved, outcomePrices 결정적. 경로 "
        "포인트 20개 미만 마켓은 데이터 품질상 제외했다.",
        "표본 B의 잔여 기간은 실제 정산 시각(closedTime) 기준이다. "
        "1차 표본 A는 예정 만기(endDate) 기준이라 합산 그리드에는 기준 "
        "차이가 섞여 있다 (A는 대부분 정산이 만기 직후라 차이 작음).",
        "자본 정규화: 표본 B는 건당 $100 명목 (shares = 100/entry, "
        "cost = 100 + 매수 수수료 정식 커브). 표본 A는 실제 진입 금액"
        "(페이퍼·앱 실측)이라, 합산 그리드의 총차이는 규모가 다른 두 "
        "표본의 단순 합이다. 건당 평균 차이도 A의 실금액 규모에 "
        "영향을 받는다.",
        "체결·수수료 가정은 1차와 동일: 도달 판정은 prices-history "
        "포인트 가격 (표본 B는 fidelity=60 시간봉) 기준, 매도가 = "
        "도달가 - 스프레드 0.01, 수수료 정식 커브 shares x rate x p x "
        "(1-p) 매도 시 1회. rate는 Gamma feeSchedule.rate.",
        "베이스라인은 손절 없는 순수 만기 보유다 (1차와 동일). 실제 "
        "운용은 손절 병행이라 손절이 먼저 발동했을 건에서는 익절 "
        "효과가 과대평가될 수 있다.",
        "승패 확정: 표본 B는 Gamma outcomePrices, 경로 마지막 진입 쪽 "
        "가격과 불일치하면 details의 reverse_disagree 플래그로 남겼다.",
    ]
    out = {
        "assumptions": assumptions,
        "pool": {"gamma_scanned": scanned, "candidates": len(cands),
                 "measured_B": len(details), "excluded_B": excluded,
                 "hardstop": hardstop,
                 "fired_R30_X0.95_B": fired_r30_095},
        "sample_B": {"reach_stats": reach_b, "grid": grid_b,
                     "sensitivity_spread": sens_b,
                     "sign_flips_vs_base": flips_b, "details": details},
        "combined_A_plus_B": {
            "n_A": len(a_det), "n_B": len(details),
            "reach_stats": reach_c, "grid": grid_c,
            "sensitivity_spread": sens_c, "sign_flips_vs_base": flips_c},
        "portfolio_sim": portfolio_sim(details),
    }
    out["assumptions"].append(
        "포트폴리오 시뮬레이션(portfolio_sim)은 표본 B 후보를 진입 시점 "
        "순으로 슬롯 4개에 배정하는 별도 계산이다. 빈 슬롯이 없으면 "
        "후보를 건너뛰고, 익절 발동 시 그 시점에 슬롯이 빈다. 슬롯당 "
        "자본은 건당 $100 명목 균일. 손절(-0.25)은 적용하지 않았다 "
        "(익절 없음 조건 포함 전 규칙 공통 가정).")
    with open(os.path.join(BASE, "bt_takeprofit2_results.json"), "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps({"pool": out["pool"]["candidates"],
                      "measured_B": len(details),
                      "excluded_B": len(excluded),
                      "hardstop": hardstop}, ensure_ascii=False))
    print("== B reach ==")
    print(json.dumps(reach_b, ensure_ascii=False))
    print("== B grid ==")
    for k, v in grid_b.items():
        print(k, v)
    print("== B flips ==", flips_b)
    print("== combined grid (R>=30 only) ==")
    for k, v in grid_c.items():
        if "|R=30d" in k or "|R=90d" in k:
            print(k, v)
    print("== combined flips ==", flips_c)
    ps = out["portfolio_sim"]
    print("== portfolio_sim (slots=4) ==")
    print("no_takeprofit", ps["no_takeprofit"])
    for k, v in ps["grid"].items():
        print(k, v)


if __name__ == "__main__":
    raise SystemExit(main())
