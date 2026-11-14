#!/usr/bin/env python3
"""boringmech 재진입 정책 백테스트 — 영구 금지(A) vs 손절 후 24h 경과 시 허용(B).

질문: 한 번 산 종목은 bought 목록에 영구히 남아 재진입이 불가한 현행 규칙을,
손절 후 24시간이 지나면 재진입 가능하게 바꾸는 편이 나은가.

앱 실규칙 (src/engine.js 대조):
- 진입: 우세한 쪽(yes/no 중 고가 측) 가격이 0.60~0.90 밴드, 만기 24h~45일 남은 구간
- 거래량 $10k 이상, 스포츠·단타(up-or-down/updown) 제외, 바이너리 Yes/No
- 손절: 보유 측 현재가 <= 진입가 - 0.25 면 전량 매도
- 정산: 만기까지 보유 시 승자 1.0 / 패자 0

시뮬레이션 (마켓별 독립, 건당 $100, 동시 보유·자본 제약 없음 — 판정은 건당 지표):
- CLOB 가격 히스토리(Yes 토큰, fidelity=60) 시간봉으로 상태기계 구동
- flat: 진입 창 안에서 우세 측이 밴드에 들어온 첫 봉에 그 가격으로 매수
- 보유: 매 봉 손절 판정 -> 체결 시 그 봉 가격으로 매도. 히스토리 끝 = 정산
- 손절 후 A: 그 마켓 종료. B: 손절 시각 +24h 이후 첫 밴드 진입 봉에 재진입
  (재진입 차수를 기록: 1=신규, 2, 3, ...)
- 수수료: 기존 백테스트와 동일 식 — 매수 fee = stake*rate*(1-p),
  매도 fee = 회수액*rate*(1-p_exit)... 가 아니라 매도도 진입과 같은
  테이커 식 fee = (매도 주식수 * p_exit) * rate * (1-p_exit) 로 근사

한계 (판정에 명시):
- 시간봉 가격 체결 가정, 스프레드·슬리피지 미반영 (라이브는 bestAsk+스프레드 필터)
- 종료·정산 완료 마켓만으로 구성한 유니버스라 생존 편향 존재 (A/B 상대 비교용)
- 손절 판정이 시간봉 단위라 라이브(1분 스캔)보다 거칠다

판정 기준 (실행 전 고정):
- 주 판정: B의 재진입 거래(차수>=2) 건당 순손익 평균 > 0 -> 허용 우위
- 보조: 재진입 승률, 차수별 평균, 재진입 거래 수, 신규 진입 건당 평균과의 차이
"""
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from backtest_boring import collect_markets, eligible, fetch_range

BASE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.expanduser("~/workspace/data-cache/boringmech-reentry")
os.makedirs(CACHE, exist_ok=True)

STAKE = 100.0
BAND_LO, BAND_HI = 0.60, 0.90
STOP_DROP = 0.25
REENTRY_WAIT = 24 * 3600
MAX_FETCH = 1200


def fee_for(amount, price, rate):
    if not rate:
        return 0.0
    return amount * rate * (1.0 - price)


def simulate(hist, end_ts, close_ts, rate, cooldown, yes_won):
    """hist: [(t, yes_price)] 정렬됨. cooldown=None이면 영구 금지(A),
    숫자(초)면 손절 후 그 시간 경과 뒤 재진입 허용(B).
    반환: 거래 목록 [{entry_no, side, entry_price, exit_kind, net_pnl, won}]"""
    trades = []
    pos = None  # {side, entry_price, shares, entry_no}
    entry_no = 0
    blocked_until = 0  # 이 시각 전에는 신규/재진입 금지

    def held_price(p_yes, side):
        return p_yes if side == "yes" else 1.0 - p_yes

    def close_position(exit_price, kind, won=None):
        nonlocal pos
        gross = pos["shares"] * exit_price - STAKE
        sell_fee = fee_for(pos["shares"] * exit_price, exit_price, rate)
        net = gross - pos["buy_fee"] - (sell_fee if kind == "stop" else 0.0)
        trades.append({
            "entry_no": pos["entry_no"], "side": pos["side"],
            "entry_price": pos["entry_price"], "exit_kind": kind,
            "exit_price": exit_price, "net_pnl": round(net, 4),
            "won": won,
        })
        pos = None

    for t, p in hist:
        if not (0.0 < p < 1.0):
            continue
        if pos is not None:
            hp = held_price(p, pos["side"])
            if hp <= pos["entry_price"] - STOP_DROP:
                close_position(hp, "stop", won=False)
                blocked_until = (t + cooldown) if cooldown is not None else float("inf")
            continue
        # flat
        if t < blocked_until:
            continue
        remain = end_ts - t
        if not (24 * 3600 <= remain <= 45 * 86400):
            continue
        side = "yes" if p >= 0.5 else "no"
        price = p if side == "yes" else 1.0 - p
        if not (BAND_LO <= price <= BAND_HI):
            continue
        entry_no += 1
        pos = {
            "side": side, "entry_price": price,
            "shares": STAKE / price, "entry_no": entry_no,
            "buy_fee": fee_for(STAKE, price, rate),
        }
    if pos is not None:
        # 히스토리 종료 = 정산 (close_ts 이후 데이터 없음)
        won = (pos["side"] == "yes") == yes_won
        close_position(1.0 if won else 0.0, "settle", won=won)
    return trades



def run_market(c):
    tail_start = max(c["start_ts"], c["end_ts"] - 46 * 86400)
    cache_path = os.path.join(CACHE, c["yes_token"] + ".json")
    if os.path.exists(cache_path):
        tail = {int(k): v for k, v in json.load(open(cache_path)).items()}
    else:
        tail = fetch_range(c["yes_token"], tail_start, c["close_ts"])
        if tail:
            json.dump(tail, open(cache_path, "w"))
    if not tail:
        return c["slug"], None
    hist = sorted(tail.items())
    a = simulate(hist, c["end_ts"], c["close_ts"], c["fee_rate"], None, c["yes_won"])
    b = simulate(hist, c["end_ts"], c["close_ts"], c["fee_rate"], REENTRY_WAIT, c["yes_won"])
    return c["slug"], {"a": a, "b": b, "category": c["category"]}


def summarize(trades):
    n = len(trades)
    if n == 0:
        return {"n": 0}
    wins = sum(1 for t in trades if t["won"])
    return {
        "n": n,
        "win_rate": round(wins / n, 4),
        "avg_net": round(sum(t["net_pnl"] for t in trades) / n, 2),
        "total_net": round(sum(t["net_pnl"] for t in trades), 2),
    }


def main():
    raw = collect_markets()
    cands = [e for m in raw if (e := eligible(m))]
    cands.sort(key=lambda x: -x["volume"])
    targets = cands[:MAX_FETCH]
    print(f"universe={len(cands)} targets={len(targets)}", flush=True)

    results = {}
    done = 0
    with ThreadPoolExecutor(max_workers=16) as ex:
        futs = {ex.submit(run_market, c): c for c in targets}
        for fut in as_completed(futs):
            done += 1
            try:
                slug, r = fut.result()
            except Exception:  # noqa: BLE001
                r = None
            if r:
                results[slug] = r
            if done % 200 == 0:
                print(f"  {done}/{len(targets)} markets={len(results)}", flush=True)

    all_a = [t for r in results.values() for t in r["a"]]
    all_b = [t for r in results.values() for t in r["b"]]
    first_b = [t for t in all_b if t["entry_no"] == 1]
    reentry = [t for t in all_b if t["entry_no"] >= 2]
    by_no = {}
    for t in reentry:
        key = str(min(t["entry_no"], 4))
        by_no.setdefault(key, []).append(t)

    report = {
        "markets": len(results),
        "A_permanent_ban": summarize(all_a),
        "B_all": summarize(all_b),
        "B_first_entries": summarize(first_b),
        "B_reentries": summarize(reentry),
        "B_reentry_by_no": {k: summarize(v) for k, v in sorted(by_no.items())},
        "stops_A": sum(1 for t in all_a if t["exit_kind"] == "stop"),
        "stops_B": sum(1 for t in all_b if t["exit_kind"] == "stop"),
    }
    out = os.path.join(BASE, "bt_reentry_results.json")
    with open(out, "w") as f:
        json.dump({"report": report, "per_market": results}, f, ensure_ascii=False)
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    raise SystemExit(main())
