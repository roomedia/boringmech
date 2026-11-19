#!/usr/bin/env python3
"""boringmech 익절 임계 백테스트 — 임계 X 도달 + 잔여 기간 R 이상이면 전량 매도.

질문: 익절 없이 만기 보유가 기본인 현행 규칙에서, 가격이 임계까지 오르고
만기가 많이 남았으면 조기 매도(익절 탈출)가 나았는가. 0.95는 임의이므로
임계·잔여 조건을 그리드로 수치화한다 (판정 단정 없음, 수치 보고만).

표본: boringmech 규칙으로 진입한 정산 완료 건 (개별 buy 트레이드 단위).
- 페이퍼: paper.db trades/positions 정본 (정산 보유 건 = sell 매칭 없이
  positions.is_resolved=1인 키에 속한 buy).
- 앱(실계정): Data API v2 positions CLOSED + activity로 진입 시각 확정.
- buy 뒤 sell이 매칭되는 건(손절 등 실제 청산)은 표본에서 제외하고 수를 센다.

가격 경로: 보유 outcome 토큰의 CLOB prices-history (fidelity=1 분봉 우선).
캐시 우선순위: takeprofit 전용 캐시 -> 손절 감사 캐시(fidelity=1) ->
재진입 캐시(fidelity=60, Yes 토큰만) -> 신규 수집.
403/429 첫 발생 시 그 즉시 전체 수집을 중단한다 (하드스톱 규칙).

시뮬레이션:
- 진입 이후 포인트 가격이 처음 X 이상이 된 시점에 도달로 판정.
- 도달 시점 잔여(만기 - 도달 시각)가 R 이상이면 그 포인트 가격에서
  스프레드 0.01을 차감한 값으로 전량 매도한 것으로 계산.
- 수수료: 정식 커브 fee = shares x rate x p_sell x (1 - p_sell), 매도 시 1회.
  rate는 건별 실측 fee_rate_bps/10000 (페이퍼) 또는 Gamma feeSchedule.rate (앱).
  (손절 감사가 쓴 회수액 x bps 단순 비례식과 다르다 — 실측 매도 fee가
  정식 커브와 일치함을 확인하고 이쪽을 쓴다.)
- 미도달·조건 미달이면 만기 보유 결과 (승 1.0 / 패 0) 그대로.

한계 (결과 파일에도 명시):
- 베이스라인은 손절 없는 순수 만기 보유다. 실제 운용은 손절 병행이라,
  손절이 먼저 발동했을 건에서는 익절 효과가 과대평가될 수 있다.
- 정산 완료 건만 표본이라 생존 편향이 있다. 특히 만기가 먼 건은 아직
  미정산이라 빠져 있어, 큰 R 조건의 표본이 구조적으로 얇다.
- 도달 판정은 분봉 포인트 가격(최근 거래가 계열) 기준이라 실체결 bid와
  차이가 있을 수 있다. 스프레드 0.01 차감으로 일부만 보정한다.
"""
import json
import os
import sqlite3
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.expanduser(
    "~/workspace/goals/goal/hidden_files/pm-trader/boringmech/paper.db")
CACHE_TP = os.path.expanduser("~/workspace/data-cache/boringmech-takeprofit")
CACHE_SL = os.path.expanduser("~/workspace/data-cache/boringmech-stoploss-audit")
CACHE_RE = os.path.expanduser("~/workspace/data-cache/boringmech-reentry")
os.makedirs(CACHE_TP, exist_ok=True)

FUNDER = "0x6e91a46ff73f36d4f3a00ab929e16b62eb014fbb"
DATA = "https://data-api.polymarket.com/v2"
GAMMA = "https://gamma-api.polymarket.com/markets"
CLOB = "https://clob.polymarket.com/prices-history"
UA = {"User-Agent": "Mozilla/5.0"}

GRID_X = [0.90, 0.92, 0.94, 0.95, 0.96, 0.97, 0.98, 0.99]
GRID_R = [None, 7, 30, 90]  # days
SPREAD = 0.01

hardstop = {"hit": False}


def to_ts(s):
    return int(datetime.strptime(s, "%Y-%m-%d %H:%M:%S")
               .replace(tzinfo=timezone.utc).timestamp())


def iso_ts(s):
    return int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp())


def get_json(url, params=None, tries=3):
    if params:
        url = url + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=UA)
    for i in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code in (403, 429):
                hardstop["hit"] = True
                return None
            time.sleep(2 * (i + 1))
        except Exception:  # noqa: BLE001
            time.sleep(2 * (i + 1))
    return None


# ---------------------------------------------------------------- 표본: 페이퍼
def paper_sample():
    db = sqlite3.connect(DB)
    db.row_factory = sqlite3.Row
    trades = db.execute(
        "SELECT * FROM trades ORDER BY created_at, id").fetchall()
    resolved = {(r[0], r[1]) for r in db.execute(
        "SELECT market_condition_id, outcome FROM positions "
        "WHERE is_resolved = 1").fetchall()}
    pos = {(r["market_condition_id"], r["outcome"]): dict(r) for r in
           db.execute("SELECT * FROM positions").fetchall()}
    cache_map = {}
    for key, data in db.execute(
            "SELECT cache_key, data FROM market_cache "
            "WHERE cache_key LIKE 'market:%'").fetchall():
        cache_map[key[len("market:"):]] = json.loads(data)

    # (condition, outcome)별로 buy에 뒤따르는 sell을 매칭 (실제 청산 건)
    by_key = {}
    for t in trades:
        by_key.setdefault((t["market_condition_id"], t["outcome"]),
                          []).append(dict(t))
    liquidated = set()  # trade id
    for key, rows in by_key.items():
        open_buys = []
        for t in rows:
            if t["side"] == "buy":
                open_buys.append(t)
            elif t["side"] == "sell" and open_buys:
                liquidated.add(open_buys.pop(0)["id"])

    sample, excluded = [], []
    for t in trades:
        if t["side"] != "buy":
            continue
        key = (t["market_condition_id"], t["outcome"])
        base = {"source": "paper", "slug": t["market_slug"],
                "outcome": t["outcome"], "trade_id": t["id"]}
        if t["id"] in liquidated:
            excluded.append({**base, "reason": "liquidated_by_sell"})
            continue
        if key not in resolved:
            excluded.append({**base, "reason": "not_settled"})
            continue
        m = cache_map.get(t["market_slug"])
        if m is None or "clobTokenIds" not in m:
            excluded.append({**base, "reason": "no_market_cache"})
            continue
        outcomes = [o.lower() for o in json.loads(m["outcomes"])]
        token = json.loads(m["clobTokenIds"])[outcomes.index(t["outcome"])]
        p = pos[key]
        cost = t["amount_usd"] + t["fee"]
        sample.append({
            **base, "token": token,
            "entry_price": t["avg_price"], "shares": t["shares"],
            "buy_fee": t["fee"], "cost": cost,
            "rate": t["fee_rate_bps"] / 10000.0,
            "entry_ts": to_ts(t["created_at"]),
            "end_ts": iso_ts(m["endDate"]),
            "resolved_ts": to_ts(p["resolved_at"]),
            "pos_realized": p["realized_pnl"],
            "pos_total_cost": p["total_cost"],
        })
    return sample, excluded, cache_map


def resolve_winners_paper(sample, cache_map):
    """승패 확정은 가격 경로 마지막 가격(정산 수렴값)으로 한다.

    Gamma markets 조회는 표본 마켓 전부가 빈 결과를 돌려줘 (아카이브됨)
    확정 경로로 쓸 수 없었다. positions 역산은 재진입이 섞인 키에서
    건별로 성립하지 않아 보조 대조로만 쓴다."""
    return 0


# ------------------------------------------------------------------ 표본: 앱
def app_sample():
    pos = get_json(f"{DATA}/positions",
                   {"user": FUNDER, "status": "CLOSED", "sizeThreshold": 0})
    rows = (pos or {}).get("data", [])
    acts = get_json(f"{DATA}/activity",
                    {"user": FUNDER, "type": "TRADE", "limit": 500})
    trades = (acts or {}).get("data", [])
    buys, sells = {}, {}
    for a in trades:
        key = (a["condition_id"], a["token_id"])
        (buys if a["side"] == "BUY" else sells).setdefault(key, []).append(a)
    sample, excluded = [], []
    for r in rows:
        key = (r["condition_id"], r["token_id"])
        base = {"source": "app", "slug": r["slug"], "outcome": r["outcome"]}
        bl = buys.get(key, [])
        if not bl:
            excluded.append({**base, "reason": "no_buy_activity"})
            continue
        if sells.get(key):
            excluded.append({**base, "reason": "has_sell_activity"})
            continue
        shares = sum(a["size"] for a in bl)
        # positions의 total_cost_usdc는 CLOSED에서 비정상값이라 쓰지 않는다.
        # cost = activity 매수 usdc 합 + positions의 entry_fees_usdc.
        cost = sum(a["usdc_size"] for a in bl) + float(
            r.get("entry_fees_usdc") or 0)
        entry_ts = int(r.get("first_entry_at") or min(
            a["timestamp"] for a in bl))
        gm = get_json(GAMMA, {"slug": r["slug"]})
        rate = None
        if gm:
            rate = ((gm[0].get("feeSchedule") or {}).get("rate")
                    if isinstance(gm, list) and gm else None)
        sample.append({
            **base, "token": r["token_id"],
            "entry_price": r["avg_price"], "shares": shares,
            "buy_fee": None, "cost": cost + 0.0,  # activity usdc는 수수료 별도 확인 불가, 아래 검증 기록
            "rate": rate or 0.0,
            "entry_ts": entry_ts,
            "end_ts": iso_ts(r["end_date"]),
            "resolved_ts": None,
            "pos_realized": r["realized_pnl"],
            "won": None,
        })
    # 앱 승패는 realized와 최종가로 확정: 승자는 포지션이 1.0으로 정산
    for s in sample:
        r = next(x for x in rows if x["slug"] == s["slug"]
                 and x["token_id"] == s["token"])
        s["won"] = float(r.get("current_price") or 0) > 0.99
        s["won_source"] = "data_api_current_price"
        s["hold_pnl"] = round(s["pos_realized"], 4)
    return sample, excluded


# ---------------------------------------------------------------- 가격 경로
def load_history(token, start, end, outcome_is_yes):
    """반환: (points [(t,p)], 해상도 설명). 캐시 우선, 없으면 수집."""
    tp = os.path.join(CACHE_TP, token + ".json")
    if os.path.exists(tp):
        h = json.load(open(tp)).get("history", [])
        return [(x["t"], float(x["p"])) for x in h], "fidelity=1 (tp cache)"
    sl = os.path.join(CACHE_SL, token + ".json")
    if os.path.exists(sl):
        h = json.load(open(sl)).get("history", [])
        pts = [(x["t"], float(x["p"])) for x in h
               if start - 7200 <= x["t"] <= end + 7200]
        if pts:
            return pts, "fidelity=1 (stoploss-audit cache)"
    if outcome_is_yes:
        re_p = os.path.join(CACHE_RE, token + ".json")
        if os.path.exists(re_p):
            d = {int(k): v for k, v in json.load(open(re_p)).items()}
            pts = sorted((t, p) for t, p in d.items() if start <= t <= end)
            if pts:
                return pts, "fidelity=60 (reentry cache)"
    if hardstop["hit"]:
        return None, "skipped_after_hardstop"
    # 신규 수집: fidelity=1, 길면 구간 분할
    pts = fetch_range_f1(token, start, end)
    if pts is None:
        return None, "fetch_failed"
    json.dump({"history": [{"t": t, "p": p} for t, p in pts]}, open(tp, "w"))
    return pts, "fidelity=1 (fetched)"


def fetch_range_f1(token, s, e, depth=0):
    if hardstop["hit"] or e - s < 60:
        return [] if not hardstop["hit"] else None
    h = get_json(CLOB, {"market": token, "startTs": int(s), "endTs": int(e),
                        "fidelity": 1})
    if h is not None:
        return sorted((pt["t"], float(pt["p"])) for pt in h.get("history", []))
    if hardstop["hit"]:
        return None
    if depth >= 4 or e - s <= 86400:
        # fidelity를 낮춰 재시도 (장기 마켓)
        h = get_json(CLOB, {"market": token, "startTs": int(s),
                            "endTs": int(e), "fidelity": 60})
        if h is not None:
            return sorted((pt["t"], float(pt["p"]))
                          for pt in h.get("history", []))
        return None
    mid = (s + e) // 2
    a = fetch_range_f1(token, s, mid, depth + 1)
    if a is None:
        return None
    b = fetch_range_f1(token, mid, e, depth + 1)
    if b is None:
        return None
    return sorted(a + b)


# ---------------------------------------------------------------- 시뮬레이션
def simulate(s, pts):
    """건별: X별 첫 도달 (t, price)과 보유 결과를 반환."""
    path = [(t, p) for t, p in pts
            if s["entry_ts"] <= t <= max(s["end_ts"], s["entry_ts"])]
    first_hit = {}
    for x in GRID_X:
        first_hit[x] = next(((t, p) for t, p in path if p >= x), None)
    return first_hit


def tp_pnl(s, p_hit):
    sell_price = p_hit - SPREAD
    if sell_price <= 0:
        return None
    fee = s["shares"] * s["rate"] * sell_price * (1 - sell_price)
    return round(s["shares"] * sell_price - fee - s["cost"], 4)


def main():
    paper, paper_ex, cache_map = paper_sample()
    app, app_ex = app_sample()
    sample = paper + app
    print(f"sample={len(sample)} (paper {len(paper)}, app {len(app)}), "
          f"excluded={len(paper_ex) + len(app_ex)}", flush=True)

    details, no_path = [], []
    for s in sample:
        end = s["resolved_ts"] or s["end_ts"]
        pts, reso = load_history(s["token"], s["entry_ts"] - 3600,
                                 max(end, s["end_ts"]) + 3600,
                                 s["outcome"] == "yes")
        if not pts:
            no_path.append({**{k: s[k] for k in ("source", "slug", "outcome")},
                            "reason": reso})
            continue
        # 승패 확정: 경로 마지막 가격의 정산 수렴값 (페이퍼).
        # 앱은 Data API current_price가 이미 확정값이라 그쪽을 쓴다.
        last_p = pts[-1][1]
        if s["source"] == "paper":
            if last_p >= 0.99:
                s["won"] = True
            elif last_p <= 0.01:
                s["won"] = False
            else:
                no_path.append({
                    **{k: s[k] for k in ("source", "slug", "outcome")},
                    "reason": f"won_unknown_last_price={last_p}"})
                continue
            s["won_source"] = "path_last_price"
            # 보조 대조: positions 역산과 다르면 플래그 (재진입 키에서 흔함)
            hold_win = s["shares"] * 1.0 - s["cost"]
            reverse_won = abs(hold_win - s["pos_realized"]) < 0.01
            s["reverse_disagree"] = (reverse_won != s["won"])
            s["hold_pnl"] = round(
                s["shares"] * (1.0 if s["won"] else 0.0) - s["cost"], 4)
        else:
            s["reverse_disagree"] = ((last_p >= 0.99) != s["won"])
        hits = simulate(s, pts)
        rec = {k: s[k] for k in ("source", "slug", "outcome", "entry_price",
                                 "shares", "cost", "rate", "won",
                                 "won_source", "hold_pnl")}
        rec["token"] = s["token"]
        rec["last_price"] = last_p
        rec["reverse_disagree"] = s.get("reverse_disagree")
        rec["entry_ts"] = s["entry_ts"]
        rec["end_ts"] = s["end_ts"]
        rec["resolution"] = reso
        rec["hits"] = {}
        for x in GRID_X:
            h = hits[x]
            if h is None:
                rec["hits"][str(x)] = None
            else:
                t_hit, p_hit = h
                rec["hits"][str(x)] = {
                    "t": t_hit, "price": p_hit,
                    "remain_days": round((s["end_ts"] - t_hit) / 86400, 2),
                    "tp_pnl": tp_pnl(s, p_hit),
                }
        details.append(rec)
        time.sleep(0.1)
    print(f"measured={len(details)}, no_path={len(no_path)}", flush=True)

    # X별 도달 후 최종 패배율 (R 무관)
    reach_stats = {}
    for x in GRID_X:
        reached = [d for d in details if d["hits"][str(x)]]
        lost = [d for d in reached if not d["won"]]
        reach_stats[str(x)] = {
            "reached": len(reached),
            "lost_after_reach": len(lost),
            "loss_rate_after_reach": (round(len(lost) / len(reached), 4)
                                      if reached else None),
        }

    grid = {}
    for x in GRID_X:
        for r in GRID_R:
            fired = []
            for d in details:
                h = d["hits"][str(x)]
                if h is None or h["tp_pnl"] is None:
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
                "avg_delta_per_sample": round(
                    sum(deltas) / len(details), 4) if details else None,
                "total_delta": round(sum(deltas), 4),
                "avoided_losses": sum(1 for d, h in fired if not d["won"]),
                "early_release_days_avg": round(sum(remains) / len(remains), 2),
                "early_release_days_median": round(
                    statistics.median(remains), 2),
            }

    # 민감도: 스프레드 가정을 0 / 0.02로 바꾸면 부호가 뒤집히는 조합
    sensitivity = {}
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
                    fee = (d["shares"] * d["rate"] * sp * (1 - sp))
                    tp = d["shares"] * sp - fee - d["cost"]
                    deltas.append(tp - d["hold_pnl"])
                key = f"spread={spread}|X={x}|R={'none' if r is None else r}"
                if deltas:
                    sensitivity[key] = {
                        "n_fired": len(deltas),
                        "total_delta": round(sum(deltas), 4),
                    }
    base_sign = {}
    for x in GRID_X:
        for r in GRID_R:
            key = f"X={x}|R={'none' if r is None else str(r) + 'd'}"
            if grid[key].get("n_fired"):
                base_sign[(x, r)] = grid[key]["total_delta"] > 0
    flips = []
    for (x, r), pos in base_sign.items():
        for spread in (0.0, 0.02):
            skey = f"spread={spread}|X={x}|R={'none' if r is None else r}"
            sv = sensitivity.get(skey)
            if sv and (sv["total_delta"] > 0) != pos:
                flips.append({"combo": f"X={x}|R={r}",
                              "spread_alt": spread,
                              "total_delta_alt": sv["total_delta"],
                              "total_delta_base": grid[
                                  f"X={x}|R={'none' if r is None else str(r) + 'd'}"
                              ]["total_delta"]})

    assumptions = [
        "체결 시점: CLOB prices-history 포인트 가격(주로 fidelity=1 분봉, "
        "일부 fidelity=60)이 임계 X 이상이 된 첫 포인트에서 도달로 판정하고 "
        "그 포인트 가격으로 체결됐다고 가정한다. 실시간 bid가 아닌 거래가 "
        "계열이라 실체결가와 차이가 있을 수 있다.",
        "매도가: 도달 포인트 가격 - 스프레드 0.01. 수수료는 정식 커브 "
        "fee = shares x rate x p_sell x (1 - p_sell) 로 매도 시 1회 적용. "
        "rate는 건별 실측 fee_rate_bps/10000 (페이퍼) 또는 Gamma "
        "feeSchedule.rate (앱). 손절 감사가 쓴 회수액 x bps 단순 비례식과 "
        "다르며, 실측 매도 fee와 대조해 정식 커브가 맞음을 확인했다.",
        "가격 히스토리는 지시문의 'Gamma'가 아니라 CLOB prices-history다 "
        "(기존 백테스트·감사 전례와 동일. Gamma는 마켓 메타 전용).",
        "베이스라인은 손절 없는 순수 만기 보유 결과다. 실제 운용은 손절 "
        "병행이라 손절이 먼저 발동했을 건에서는 익절 효과가 과대평가될 수 "
        "있다. 실제로 sell로 청산된 건은 표본에서 제외했다.",
        "생존 편향: 정산 완료 건만 표본이다. 아직 미정산인 보유 건(특히 "
        "만기가 먼 선거 마켓)이 빠져 있어 큰 R 조건의 표본이 구조적으로 "
        "얇고, 도달 후 패배율도 정산된 건 안에서만 잰 값이다.",
        "페이퍼 건의 보유 PnL은 건별 shares x (1 or 0) - (amount+fee)로 "
        "재계산했고 승패는 가격 경로 마지막 가격의 정산 수렴값(>=0.99 승, "
        "<=0.01 패)으로 확정했다. Gamma markets 조회는 표본 마켓 전부가 "
        "빈 결과라(아카이브) 쓸 수 없었고, positions 실현손익 역산은 "
        "재진입이 섞인 키에서 건별로 성립하지 않아 보조 대조로만 썼다 "
        "(불일치 건은 details의 reverse_disagree 플래그로 남겼다).",
        "앱 건의 cost는 activity 매수 usdc 합 + Data API entry_fees_usdc"
        "(positions의 total_cost_usdc는 CLOSED에서 비정상값이라 제외), "
        "hold_pnl은 Data API realized_pnl을 그대로 썼다. "
        "앱 표본은 6건 이하로 작아 페이퍼와 합산하되 source로 구분했다.",
    ]
    out = {
        "assumptions": assumptions,
        "sample": {
            "total_measured": len(details),
            "paper": sum(1 for d in details if d["source"] == "paper"),
            "app": sum(1 for d in details if d["source"] == "app"),
            "excluded": paper_ex + app_ex,
            "no_path": no_path,
            "hardstop_hit": hardstop["hit"],
        },
        "reach_stats": reach_stats,
        "grid": grid,
        "sensitivity_spread": sensitivity,
        "sign_flips_vs_base": flips,
        "details": details,
    }
    with open(os.path.join(BASE, "bt_takeprofit_results.json"), "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps({"reach_stats": reach_stats,
                      "sample": out["sample"]["total_measured"],
                      "no_path": len(no_path),
                      "hardstop": hardstop["hit"]},
                     ensure_ascii=False, indent=1))
    for k, v in grid.items():
        print(k, v)


if __name__ == "__main__":
    raise SystemExit(main())
