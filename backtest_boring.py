#!/usr/bin/env python3
"""boring 전략 기계적 코어 백테스트.

규칙 (boring-strategy.md의 기계적 부분만, 판단 단계 제외):
- 유니버스: 바이너리 Yes/No, UMA 정산 완료, endDate가 최근 12개월 내, 거래량 $50k 이상
  - 제외: 스포츠·이스포츠 경기 마켓(Gamma sportsMarketType 존재), 크립토 단타(up-or-down/updown slug),
    50/50 정산, 가격 히스토리를 못 구한 마켓
- 진입(주 규칙): CLOB 가격 히스토리(Yes 토큰, fidelity=60)에서 어느 한쪽이 70~90센트 밴드에
  처음 진입하는 시점에 그쪽을 해당 가격으로 $100 매수, 만기 정산까지 보유
  - main = 라이브 스캔 창(만기까지 24h~45일 남은 구간 안에서 첫 진입) 적용
  - sensitivity = 창 제한 없는 첫 진입 (수명 170일 이하 마켓만 전 구간 조회)
- 수수료: 마켓별 Gamma feeSchedule.rate, 테이커 = 수량 x rate x p x (1-p)
  - $100으로 주식을 사고 수수료는 별도 지불로 모델링 (건당 fee = 100 x rate x (1-p))
- 룩어헤드 금지: 진입은 정산(closedTime) 이전 가격만 사용
- CLOB prices-history는 구간 ~15일 초과 시 400을 반환하므로 구간을 재귀적으로 쪼갠다.
"""
import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

import requests

BASE = os.path.dirname(os.path.abspath(__file__))
GAMMA = "https://gamma-api.polymarket.com/markets"
CLOB_HIST = "https://clob.polymarket.com/prices-history"
HEADERS = {"User-Agent": "pm-boring-backtest/1.0"}

END_MIN = "2025-10-29T00:00:00Z"
END_MAX = "2026-10-29T00:00:00Z"
VOLUME_MIN = 50_000
MAX_HIST_FETCH = 1200  # 가격 히스토리 조회 상한 (거래량 내림차순 우선)
STAKE = 100.0
BAND_LO, BAND_HI = 0.70, 0.90

SHORT_TERM_RE = re.compile(
    r"(bitcoin|ethereum|solana|xrp|dogecoin|cardano)-up-or-down|up-or-down-|updown")

POLITICS_KW = ["election", "president", "senate", "congress", "governor", "mayor",
               "prime minister", "parliament", "republican", "democrat", "trump",
               "biden", "harris", "vance", "impeach", "supreme court", "referendum",
               "ceasefire", "invade", "invasion", "annex", "netanyahu", "zelensky",
               "putin", "nato", "war in", "cabinet", "minister", "legislat",
               "ballot", "nominee", "prime-minister", "chancellor", "dictator",
               "regime", "military", "strike on", "nuclear"]
CRYPTO_KW = ["bitcoin", "ethereum", "btc", " eth ", "solana", "xrp", "dogecoin",
             "crypto", "satoshi"]
ECON_KW = ["fed ", "fomc", "interest rate", "inflation", "recession", "gdp", "cpi",
           "unemployment", "jobs report", "rate cut", "rate hike", "tariff"]


def parse_ts(s):
    if not s:
        return None
    s = s.strip().replace(" ", "T", 1) if "T" not in s else s
    s = s.replace("Z", "+00:00")
    if re.search(r"[+-]\d{2}$", s):
        s += ":00"
    try:
        return datetime.fromisoformat(s).timestamp()
    except ValueError:
        return None


def get_json(url, params=None, tries=4):
    for i in range(tries):
        try:
            r = requests.get(url, params=params, headers=HEADERS, timeout=30)
            if r.status_code == 200:
                return r.json()
            if r.status_code in (429, 500, 502, 503):
                time.sleep(2 * (i + 1))
                continue
            return None
        except requests.RequestException:
            time.sleep(2 * (i + 1))
    return None


def classify(question):
    q = " " + (question or "").lower() + " "
    if any(k in q for k in CRYPTO_KW):
        return "crypto"
    if any(k in q for k in ECON_KW):
        return "economy"
    if any(k in q for k in POLITICS_KW):
        return "politics"
    return "other"


def collect_markets():
    out = []
    offset = 0
    while offset <= 20000:
        page = get_json(GAMMA, {
            "closed": "true", "limit": 100, "offset": offset,
            "volume_min": VOLUME_MIN, "end_date_min": END_MIN, "end_date_max": END_MAX,
        })
        if not page:
            break
        out.extend(page)
        if len(page) < 100:
            break
        offset += 100
        time.sleep(0.1)
    return out


def eligible(m):
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
    if yes_won == no_won:  # 50/50 또는 미확정
        return None
    vol = m.get("volumeNum")
    if vol is None:
        try:
            vol = float(m.get("volume") or 0)
        except (TypeError, ValueError):
            vol = 0
    if vol < VOLUME_MIN:
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
        "fee_rate": fee,  # None 가능
        "neg_risk": bool(m.get("negRisk")),
        "start_ts": start_ts,
        "end_ts": end_ts,
        "close_ts": close_ts,
        "category": classify(m.get("question")),
    }


def first_entry(hist, end_ts, windowed):
    """hist: [(t, yes_price)] -> (t, side, price) or None"""
    for t, p in hist:
        if not (0.0 < p < 1.0):
            continue
        if windowed:
            remain = end_ts - t
            if not (24 * 3600 <= remain <= 45 * 86400):
                continue
        if BAND_LO <= p <= BAND_HI:
            return t, "yes", p
        np_ = 1.0 - p
        if BAND_LO <= np_ <= BAND_HI:
            return t, "no", np_
    return None


def pnl_for(tr, stake=STAKE):
    p = tr["entry_price"]
    rate = tr["fee_rate"] or 0.0
    shares = stake / p
    fee = stake * rate * (1.0 - p)
    won = (tr["side"] == "yes") == tr["yes_won"]
    gross = (shares - stake) if won else -stake
    return won, fee, gross - fee


def fetch_range(tok, s, e, depth=0):
    """[s, e] 가격 조회. 구간이 길면 CLOB가 400을 주므로 실패 시 반으로 쪼갠다.
    끝까지 실패하면 None."""
    s, e = int(s), int(e)
    if e - s < 3600:
        return {}
    h = get_json(CLOB_HIST, {"market": tok, "startTs": s, "endTs": e,
                             "fidelity": 60})
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


def fetch_hist(c):
    """(main_trade, sens_trade, had_history) 반환.

    main 규칙은 만기 전 24h~45일 구간만 보므로 꼬리 46일 구간은 항상 조회한다.
    sensitivity(전 구간 첫 진입)는 마켓 수명 170일 이하일 때만 전 구간을 조회한다.
    """
    tail_start = max(c["start_ts"], c["end_ts"] - 46 * 86400)
    tail = fetch_range(c["yes_token"], tail_start, c["close_ts"])
    if not tail:
        return None, None, False
    base = {k: c[k] for k in ("slug", "question", "event_slug", "yes_won",
                              "volume", "fee_rate", "neg_risk", "category",
                              "end_ts", "close_ts")}

    def make(hist, windowed):
        e_ = first_entry(hist, c["end_ts"], windowed=windowed)
        if not e_:
            return None
        t, side, price = e_
        tr = dict(base, entry_ts=t, side=side, entry_price=price)
        won, fee, net = pnl_for(tr)
        tr.update(won=won, fee=fee, net_pnl=net)
        return tr

    tail_hist = sorted(tail.items())
    main_tr = make(tail_hist, True)

    sens_tr = None
    life = c["close_ts"] - c["start_ts"]
    if tail_start <= c["start_ts"]:
        sens_tr = make(tail_hist, False)
    elif life <= 170 * 86400:
        head = fetch_range(c["yes_token"], c["start_ts"], tail_start)
        if head is not None:
            merged = dict(head)
            merged.update(tail)
            sens_tr = make(sorted(merged.items()), False)
    # life > 170d인 장기 마켓은 sensitivity 생략 (보고서에 명시)
    return main_tr, sens_tr, True


def main():
    raw = collect_markets()
    print(f"gamma markets fetched: {len(raw)}", flush=True)
    cands = [e for m in raw if (e := eligible(m))]
    print(f"eligible universe: {len(cands)}", flush=True)
    cands.sort(key=lambda x: -x["volume"])
    fetch_list = cands[:MAX_HIST_FETCH]
    print(f"history fetch targets: {len(fetch_list)}", flush=True)

    trades_main, trades_sens = [], []
    no_hist = 0
    no_entry_main = 0
    done = 0
    with ThreadPoolExecutor(max_workers=12) as ex:
        futs = {ex.submit(fetch_hist, c): c for c in fetch_list}
        for fut in as_completed(futs):
            done += 1
            try:
                tm, ts_, had = fut.result()
            except Exception:  # noqa: BLE001
                tm, ts_, had = None, None, False
            if not had:
                no_hist += 1
            else:
                if tm:
                    trades_main.append(tm)
                else:
                    no_entry_main += 1
                if ts_:
                    trades_sens.append(ts_)
            if done % 100 == 0:
                print(f"  {done}/{len(fetch_list)} processed, "
                      f"main trades={len(trades_main)}", flush=True)

    meta = {
        "gamma_fetched": len(raw),
        "eligible": len(cands),
        "fetch_targets": len(fetch_list),
        "no_history": no_hist,
        "with_history": len(fetch_list) - no_hist,
        "no_entry_main": no_entry_main,
        "universe_note": (
            f"Gamma closed=true, endDate {END_MIN}..{END_MAX}, volume>=${VOLUME_MIN}, "
            "binary Yes/No, UMA resolved, sportsMarketType & up-or-down/updown excluded"),
    }
    with open(os.path.join(BASE, "bt_results.json"), "w") as f:
        json.dump({"meta": meta, "trades_main": trades_main,
                   "trades_sens": trades_sens}, f, ensure_ascii=False, indent=1)
    print(json.dumps(meta, ensure_ascii=False))
    print(f"main trades: {len(trades_main)}, sensitivity trades: {len(trades_sens)}")


if __name__ == "__main__":
    raise SystemExit(main())
