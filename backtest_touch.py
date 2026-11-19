#!/usr/bin/env python3
"""boringmech 터치형 시장 전용 백테스트.

가설 (사용자): 비트코인 월내 가격 도달 같은 터치형 시장은 만기가 멀어도
조기 종결로 자금을 빨리 회전시킬 수 있으니 매수 우선순위를 올려야 한다.
반대 가설 (사전 분석): 터치형에서 전략이 사는 쪽은 보통 No(도달 안 할 쪽)
인데, No의 조기 종료는 대개 터치로 인한 전액 손실 확정이라 방향별로 갈린다.

판정 기준 (결과를 보기 전에 고정 — 2026-11-19):
- 방향(No/Yes)별로 따로 판정한다.
- "터치형 우선 가점 채택"은 해당 방향에서 다음을 모두 만족할 때만 성립:
  a) 터치형 해당 방향 표본 n >= 10
  b) 자금 회전 수익률(건별 net_pnl / 투입액 / 실현 보유일)의 평균과
     중앙값이 모두 고정형 같은 방향보다 높다
  c) 조기 종료가 손실에 몰려 있지 않다: 조기 종료 건의 패배 비율 < 70%
     이고, 조기 종료 건 승률이 해당 방향 전체 승률보다 20%p 넘게
     낮지 않다
- 조기 종료 정의: close_ts가 end_ts보다 24시간 이상 이른 경우.
- 데이터 요건(출처·기간, 스텁 스캔, 실제 소비 건수, 핵심 가정) 중
  하나라도 비면 그 방향은 "판정 불가"로 낸다. 표본 부족도 판정 불가.

기계 재사용: 진입·수수료·판정은 backtest_boring.py와 동일하게 맞춘다
(기준선 bt_results.json이 그 기계로 만들어졌고, 비교는 같은 방식이어야
성립한다).
- 진입: CLOB 가격 히스토리(Yes 토큰, fidelity=60)에서 만기까지
  24h~45일 남은 구간 안에 어느 한쪽이 밴드 0.70~0.90에 처음 진입하는
  시점에 그쪽을 $100 매수, 정산까지 보유 (backtest_boring main 규칙)
- 수수료: 마켓별 Gamma feeSchedule.rate, 테이커 = 수량 x rate x p x (1-p)
- 룩어헤드 금지: 진입은 정산(closedTime) 이전 가격만 사용

유니버스: Gamma closed=true, endDate 2025-10-29..2026-10-29 (기준선과
같은 기간), UMA 정산 완료 바이너리, 스포츠·up-or-down 제외, 카테고리
crypto, 질문에 가격 목표($ 금액)와 도달/이탈 패턴(reach, dip, hit,
touch, above $, below $ 등)이 있는 시장. 거래량 하한 $10,000 (실전
스캐너와 동일). $50,000 기준은 같은 표본의 부분집합으로 따로 집계한다.

데이터: 한 번 받은 응답은 ~/workspace/data-cache/boringmech-touch-20261119/
에 캐시해 재사용한다. 429/401/403이 나오면 그 공급자는 즉시 중단한다
(재시도·우회 없음).
"""
import hashlib
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from statistics import mean, median

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_boring import (  # noqa: E402
    BAND_HI, BAND_LO, SHORT_TERM_RE, STAKE, classify, first_entry,
    parse_ts, pnl_for,
)

BASE = os.path.dirname(os.path.abspath(__file__))
GAMMA = "https://gamma-api.polymarket.com/markets"
CLOB_HIST = "https://clob.polymarket.com/prices-history"
HEADERS = {"User-Agent": "pm-touch-backtest/1.0"}
CACHE_DIR = os.path.expanduser(
    "~/workspace/data-cache/boringmech-touch-20261119")
os.makedirs(CACHE_DIR, exist_ok=True)

END_MIN = "2025-10-29T00:00:00Z"
END_MAX = "2026-10-29T00:00:00Z"
VOLUME_MIN = 10_000
VOLUME_HIGH = 50_000
MAX_HIST_FETCH = 800  # 가격 히스토리 조회 상한 (거래량 내림차순 우선)

# 도달/이탈(배리어) 패턴과 레벨 패턴을 나눠 기록한다 — above/below $는
# 특정일 종가 기준 시장일 수 있어 배리어형과 성격이 다를 수 있기 때문.
BARRIER_RE = re.compile(r"\b(reach|dip|hit|touch|exceed|surpass)\b", re.I)
LEVEL_RE = re.compile(r"(above|below|over|under)\s*\$", re.I)
PRICE_RE = re.compile(r"\$[\d,]+")


class ProviderBlocked(Exception):
    """429/401/403 — 그 공급자는 즉시 중단한다."""

    def __init__(self, provider, status):
        super().__init__(f"{provider} HTTP {status}")
        self.provider = provider
        self.status = status


STATS = {"http_calls": 0, "cache_hits": 0, "blocked": None}


def _cache_key(url, params):
    raw = url + "?" + json.dumps(params or {}, sort_keys=True)
    return hashlib.sha1(raw.encode()).hexdigest()


def get_json(url, params=None, provider="unknown"):
    """캐시 우선 조회. 200만 캐시한다. 429/401/403은 즉시 하드스톱."""
    key = _cache_key(url, params)
    path = os.path.join(CACHE_DIR, key + ".json")
    if os.path.exists(path):
        STATS["cache_hits"] += 1
        with open(path) as f:
            return json.load(f)["json"]
    try:
        r = requests.get(url, params=params, headers=HEADERS, timeout=30)
    except requests.RequestException:
        return None
    STATS["http_calls"] += 1
    if r.status_code in (429, 401, 403):
        raise ProviderBlocked(provider, r.status_code)
    if r.status_code != 200:
        return None
    data = r.json()
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump({"json": data}, f)
    os.replace(tmp, path)
    return data


def is_touch(question):
    q = question or ""
    if not PRICE_RE.search(q):
        return None
    if BARRIER_RE.search(q):
        return "barrier"
    if LEVEL_RE.search(q):
        return "level"
    return None


def _collect_pass(extra):
    out = []
    offset = 0
    while offset <= 30000:
        params = {
            "closed": "true", "limit": 100, "offset": offset,
            "volume_min": VOLUME_MIN, "end_date_min": END_MIN,
            "end_date_max": END_MAX,
        }
        params.update(extra)
        page = get_json(GAMMA, params, provider="gamma")
        if not page:
            break
        out.extend(page)
        if len(page) < 100:
            break
        offset += 100
        time.sleep(0.05)
    return out


def collect_markets():
    """Gamma 페이지네이션은 기본 순서에서 2,100건에서 끊긴다 (실측:
    offset 2100부터 빈 응답, 순서는 거래량순이 아니라 임의). 기본 패스만
    으로는 거래량 높은 터치형이 절단 밖에 남을 수 있어, 거래량 내림차순
    패스를 합쳐 같은 유니버스를 더 넓게 덮는다. 병합 후 slug로 중복
    제거. 거래량 하한은 backtest_boring과 같이 클라이언트에서 건다
    (Gamma의 volume_min 파라미터는 실측상 무시된다)."""
    a = _collect_pass({})
    b = _collect_pass({"order": "volumeNum", "ascending": "false"})
    seen, out = set(), []
    for m in a + b:
        key = m.get("slug") or m.get("id")
        if key in seen:
            continue
        seen.add(key)
        out.append(m)
    return out, len(a), len(b)


def eligible_touch(m):
    """backtest_boring.eligible과 같은 골격, 거래량 하한과 터치 조건만 교체."""
    if m.get("sportsMarketType"):
        return None
    slug = m.get("slug") or ""
    if SHORT_TERM_RE.search(slug):
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
    if classify(m.get("question")) != "crypto":
        return None
    kind = is_touch(m.get("question"))
    if not kind:
        return None
    start_ts = parse_ts(m.get("startDate"))
    end_ts = parse_ts(m.get("endDate"))
    close_ts = parse_ts(m.get("closedTime"))
    if not start_ts or not end_ts or not close_ts:
        return None
    fee = (m.get("feeSchedule") or {}).get("rate")
    ev = (m.get("events") or [{}])[0]
    return {
        "slug": slug,
        "question": m.get("question") or "",
        "event_slug": ev.get("slug") or "",
        "yes_token": toks[0],
        "yes_won": yes_won,
        "volume": vol,
        "fee_rate": fee,
        "neg_risk": bool(m.get("negRisk")),
        "start_ts": start_ts,
        "end_ts": end_ts,
        "close_ts": close_ts,
        "category": "crypto",
        "touch_kind": kind,
    }


def fetch_range(tok, s, e, depth=0):
    """backtest_boring.fetch_range와 같은 재귀 분할, 조회만 캐시 경유."""
    s, e = int(s), int(e)
    if e - s < 3600:
        return {}
    h = get_json(CLOB_HIST, {"market": tok, "startTs": s, "endTs": e,
                             "fidelity": 60}, provider="clob")
    if h is not None:
        return {pt["t"]: float(pt["p"]) for pt in h.get("history", [])}
    if depth >= 3 or e - s <= 3 * 86400:
        return None
    mid = (s + e) // 2
    a = fetch_range(tok, s, mid, depth + 1)
    if a is None:
        return None
    b = fetch_range(tok, mid, e, depth + 1)
    if b is None:
        return None
    a.update(b)
    return a


def fetch_trade(c):
    """(main_trade, sens_trade, had_history) — backtest_boring.fetch_hist와
    같은 구성. 기준선(bt_results)이 trades_main+trades_sens를 합쳐
    계산되므로, 터치형도 같은 방식으로 main(만기 24h~45일 창 첫 진입)과
    sens(수명 170일 이하 전 구간 첫 진입)를 함께 낸다."""
    tail_start = max(c["start_ts"], c["end_ts"] - 46 * 86400)
    tail = fetch_range(c["yes_token"], tail_start, c["close_ts"])
    if not tail:
        return None, None, False
    base = {k: c[k] for k in ("slug", "question", "event_slug", "yes_won",
                             "volume", "fee_rate", "neg_risk", "category",
                             "end_ts", "close_ts", "touch_kind")}

    def make(hist, windowed, rule):
        e_ = first_entry(hist, c["end_ts"], windowed=windowed)
        if not e_:
            return None
        t, side, price = e_
        tr = dict(base, entry_ts=t, side=side, entry_price=price, rule=rule)
        won, fee, net = pnl_for(tr)
        tr.update(won=won, fee=fee, net_pnl=net)
        return tr

    tail_hist = sorted(tail.items())
    main_tr = make(tail_hist, True, "main")
    sens_tr = None
    life = c["close_ts"] - c["start_ts"]
    if tail_start <= c["start_ts"]:
        sens_tr = make(tail_hist, False, "sens")
    elif life <= 170 * 86400:
        head = fetch_range(c["yes_token"], c["start_ts"], tail_start)
        if head is not None:
            merged = dict(head)
            merged.update(tail)
            sens_tr = make(sorted(merged.items()), False, "sens")
    return main_tr, sens_tr, True


def group_metrics(trades):
    """방향 판정·회전율에 쓰는 공통 지표. trades는 bt_results와 같은 필드."""
    n = len(trades)
    if n == 0:
        return {"n": 0}
    wins = sum(1 for t in trades if t["won"])
    early = [t for t in trades if t["close_ts"] < t["end_ts"] - 24 * 3600]
    early_wins = sum(1 for t in early if t["won"])
    hold = [(t["close_ts"] - t["entry_ts"]) / 86400 for t in trades]
    sched = [(t["end_ts"] - t["entry_ts"]) / 86400 for t in trades]
    daily, excluded = [], 0
    for t in trades:
        hd = (t["close_ts"] - t["entry_ts"]) / 86400
        if hd <= 0.04:  # 보유 1시간 미만은 회전율 분모로 쓰지 않는다
            excluded += 1
            continue
        daily.append(t["net_pnl"] / STAKE / hd)
    m = {
        "n": n,
        "win_rate": wins / n,
        "net_pnl_sum": sum(t["net_pnl"] for t in trades),
        "early_n": len(early),
        "early_ratio": len(early) / n,
        "early_wins": early_wins,
        "early_losses": len(early) - early_wins,
        "early_win_rate": (early_wins / len(early)) if early else None,
        "avg_hold_days": mean(hold),
        "avg_sched_days": mean(sched),
        "turnover_excluded": excluded,
    }
    if daily:
        m["turnover_mean_daily"] = mean(daily)
        m["turnover_median_daily"] = median(daily)
        m["turnover_mean_annual"] = mean(daily) * 365
        m["turnover_median_annual"] = median(daily) * 365
    return m


def by_side(trades):
    return {
        "all": group_metrics(trades),
        "no": group_metrics([t for t in trades if t["side"] == "no"]),
        "yes": group_metrics([t for t in trades if t["side"] == "yes"]),
    }


def verdict_for(direction, touch_m, fixed_m):
    """스크립트 상단에 고정한 판정 기준을 그대로 적용한다."""
    if touch_m.get("n", 0) < 10:
        return "판정 불가 (표본 부족)"
    if fixed_m.get("n", 0) < 10:
        return "판정 불가 (기준선 표본 부족)"
    if "turnover_mean_daily" not in touch_m or \
            "turnover_mean_daily" not in fixed_m:
        return "판정 불가 (회전율 계산 불가)"
    turn_ok = (touch_m["turnover_mean_daily"] > fixed_m["turnover_mean_daily"]
               and touch_m["turnover_median_daily"]
               > fixed_m["turnover_median_daily"])
    early_ok = True
    if touch_m["early_n"] > 0:
        loss_ratio = touch_m["early_losses"] / touch_m["early_n"]
        early_ok = (loss_ratio < 0.70
                    and touch_m["early_win_rate"]
                    >= touch_m["win_rate"] - 0.20)
    if turn_ok and early_ok:
        return "채택 성립"
    reasons = []
    if not turn_ok:
        reasons.append("회전율 우위 없음")
    if not early_ok:
        reasons.append("조기 종료가 손실에 몰림")
    return "채택 불성립 (" + ", ".join(reasons) + ")"


def stub_scan(trades):
    """스텁/플레이스홀더 스캔: 구조적 마커 + 거래 필드 무결성.

    1차 스캔(부분 문자열)은 시장 설명문 안의 일반 단어("placeholder
    mayors" 같은 정산 조건 문구)에도 걸리는 오탐이 있어, 마커가 JSON
    키이거나 40자 미만의 짧은 값일 때만 구조적 히트로 센다. 긴 자유
    텍스트 안의 히트는 benign으로 따로 보고한다."""
    markers = ("stub", "placeholder", "lorem", "dummy")
    structural_hits = 0
    freetext_hits = 0
    files = 0

    def walk(obj):
        nonlocal structural_hits, freetext_hits
        if isinstance(obj, dict):
            for k, v in obj.items():
                if any(mk in str(k).lower() for mk in markers):
                    structural_hits += 1
                walk(v)
        elif isinstance(obj, list):
            for v in obj:
                walk(v)
        elif isinstance(obj, str):
            low = obj.lower()
            if any(mk in low for mk in markers):
                if len(obj) < 40:
                    structural_hits += 1
                else:
                    freetext_hits += 1

    for name in os.listdir(CACHE_DIR):
        if not name.endswith(".json"):
            continue
        files += 1
        try:
            with open(os.path.join(CACHE_DIR, name)) as f:
                walk(json.load(f))
        except (ValueError, OSError):
            structural_hits += 1  # 깨진 캐시는 데이터 결함으로 센다
    anomalies = 0
    for t in trades:
        ok = (0.0 < t["entry_price"] < 1.0
              and t["entry_ts"] < t["close_ts"]
              and t["side"] in ("yes", "no")
              and abs(t["net_pnl"]) <= STAKE * 20)
        if not ok:
            anomalies += 1
    return {"cache_files_scanned": files,
            "marker_hit_files": structural_hits,
            "freetext_marker_hits_benign": freetext_hits,
            "trade_anomalies": anomalies}


def main():
    meta = {"provider_blocked": None}
    n_pass_a = n_pass_b = 0
    try:
        raw, n_pass_a, n_pass_b = collect_markets()
        print(f"gamma markets fetched: {len(raw)} "
              f"(default {n_pass_a} + volume-ordered {n_pass_b}, deduped)",
              flush=True)
        cands = [e for m in raw if (e := eligible_touch(m))]
        print(f"eligible touch universe: {len(cands)}", flush=True)
        cands.sort(key=lambda x: -x["volume"])
        fetch_list = cands[:MAX_HIST_FETCH]
        print(f"history fetch targets: {len(fetch_list)}", flush=True)

        trades, trades_main_only = [], []
        no_hist, no_entry = 0, 0
        done = 0
        with ThreadPoolExecutor(max_workers=12) as ex:
            futs = {ex.submit(fetch_trade, c): c for c in fetch_list}
            for fut in as_completed(futs):
                done += 1
                tm, ts_, had = fut.result()
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
                if done % 100 == 0:
                    print(f"  {done}/{len(fetch_list)} processed, "
                          f"trades={len(trades)}", flush=True)
    except ProviderBlocked as b:
        meta["provider_blocked"] = {"provider": b.provider,
                                    "status": b.status}
        print(f"PROVIDER BLOCKED: {b.provider} HTTP {b.status} — 즉시 중단",
              flush=True)
        trades, trades_main_only = [], []
        fetch_list, cands, raw = [], [], []
        no_hist = no_entry = 0

    # 고정형 기준선 — 기존 bt_results.json을 같은 지표 함수로 계산.
    # 터치형(크립토 도달/이탈 패턴)은 기준선에서 제외한다.
    fixed_all = []
    bt_path = os.path.join(BASE, "bt_results.json")
    if os.path.exists(bt_path):
        with open(bt_path) as f:
            bt = json.load(f)
        for t in bt.get("trades_main", []) + bt.get("trades_sens", []):
            if t.get("category") == "crypto" and is_touch(t.get("question")):
                continue
            fixed_all.append(t)

    touch_all = trades
    touch_50k = [t for t in trades if t["volume"] >= VOLUME_HIGH]
    touch_barrier = [t for t in trades if t["touch_kind"] == "barrier"]
    touch_level = [t for t in trades if t["touch_kind"] == "level"]

    scan = stub_scan(trades)
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
        "http_calls": STATS["http_calls"],
        "cache_hits": STATS["cache_hits"],
        "stub_scan": scan,
        "universe_note": (
            f"Gamma closed=true, endDate {END_MIN}..{END_MAX}, "
            f"volume>=${VOLUME_MIN}, binary Yes/No, UMA resolved, crypto, "
            "touch pattern (reach/dip/hit/touch/above $/below $ + $ target), "
            "sports & up-or-down excluded"),
    })

    groups = {
        "touch_10k": by_side(touch_all),
        "touch_10k_main_only": by_side(trades_main_only),
        "touch_50k": by_side(touch_50k),
        "touch_barrier": by_side(touch_barrier),
        "touch_level": by_side(touch_level),
        "fixed_baseline": by_side(fixed_all),
    }
    verdicts = {}
    if meta["provider_blocked"] or scan["marker_hit_files"] or \
            scan["trade_anomalies"]:
        verdicts = {"no": "판정 불가 (데이터 요건 미충족)",
                    "yes": "판정 불가 (데이터 요건 미충족)"}
    else:
        for d in ("no", "yes"):
            verdicts[d] = verdict_for(
                d, groups["touch_10k"][d], groups["fixed_baseline"][d])

    out = {"meta": meta, "groups": groups, "verdicts": verdicts,
           "trades": trades}
    with open(os.path.join(BASE, "bt_touch_results.json"), "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(meta, ensure_ascii=False))
    print("verdicts:", json.dumps(verdicts, ensure_ascii=False))
    print(f"touch trades: {len(trades)} "
          f"(no={groups['touch_10k']['no'].get('n', 0)}, "
          f"yes={groups['touch_10k']['yes'].get('n', 0)})")


if __name__ == "__main__":
    raise SystemExit(main())
