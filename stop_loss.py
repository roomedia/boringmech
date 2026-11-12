#!/usr/bin/env python3
"""boringmech 손절 체크·실행 모듈 (2026-11-12 규칙 변경으로 도입).

규칙: 보유 포지션의 현재가가 진입가 - 0.25 (절대 25¢) 이하로 떨어지면 전량 매도.
원규칙은 "손절 없음·만기 보유"였고, 이 모듈이 그 변경분이다 — STRATEGY.md의
"손절 규칙 (2026-11-12 규칙 변경)" 절 참조.

가격 소스: Gamma outcomePrices. 포지션의 outcome명을 Gamma outcomes 목록과
대소문자 무시로 매칭해 인덱스를 정한다 (Yes/No 마켓에서는 인덱스 0=Yes, 1=No).
pm-trader price 명령은 일부 마켓에서 구조적으로 실패하므로 쓰지 않는다.

절대 가드:
- 가격 조회 실패·빈 응답·outcome 매칭 실패 시 그 포지션은 스킵한다.
  조회 실패를 0으로 취급하지 않는다 (가격 결측을 $0으로 무음 처리하던
  결함과 같은 사고 방지).
- 현재가 >= 0.97 또는 <= 0.03이면 사실상 결정된 마켓으로 보고 손절 대상에서
  제외한다 (정산 로직이 처리).

모드:
- 기본: 조건을 충족한 포지션을 pm-trader.sh sell로 전량 매도하고 결과
  (매도가·회수액·손익)를 JSON에 포함해 출력한다. 매도 직전에도 같은
  가드·조건으로 재검증한다 (평가와 실행 사이의 가격 변동 대응).
- --dry-run: 평가만 하고 JSON을 출력한다. 매도하지 않는다.
- --account NAME: 대상 페이퍼 계좌 (기본 boringmech).

계좌 DB는 pm-trader.sh 래퍼 규약을 따른다: 데이터 디렉토리
(PM_TRADER_DATA_DIR, 기본은 이 스크립트 위치) 아래 <계좌>/paper.db를
읽기 전용으로 연다.

출력 JSON: {"checked_at", "mode", "account", "positions": [...], "triggered_count"}
positions 항목: slug, outcome, shares, entry, stop_line, current,
status(holding|triggered|sold|sold_partial|sell_failed|
excluded_decided|skipped_no_price|skipped_no_outcome)
"""
import json
import os
import sqlite3
import subprocess
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = os.path.dirname(os.path.abspath(__file__))
WRAPPER = os.path.join(BASE, "pm-trader.sh")
GAMMA_MARKETS_URL = "https://gamma-api.polymarket.com/markets"

DROP = 0.25          # 진입가 대비 절대 하락 폭 (25¢)
DECIDED_HIGH = 0.97  # 이 이상이면 사실상 결정된 마켓
DECIDED_LOW = 0.03   # 이 이하면 사실상 결정된 마켓

DRY_RUN = "--dry-run" in sys.argv


def parse_account():
    if "--account" in sys.argv:
        i = sys.argv.index("--account")
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return "boringmech"


ACCOUNT = parse_account()
DATA_DIR = os.environ.get("PM_TRADER_DATA_DIR") or BASE
DB_PATH = os.path.join(DATA_DIR, ACCOUNT, "paper.db")


def load_open_positions():
    """paper.db에서 미정산·잔량 있는 포지션을 읽는다 (읽기 전용)."""
    uri = f"file:{DB_PATH}?mode=ro"
    db = sqlite3.connect(uri, uri=True)
    db.row_factory = sqlite3.Row
    try:
        rows = db.execute(
            "SELECT market_slug, outcome, shares, avg_entry_price, total_cost "
            "FROM positions WHERE is_resolved = 0 AND shares > 0"
        ).fetchall()
        return [dict(r) for r in rows]
    finally:
        db.close()


def fetch_gamma_market(slug):
    """Gamma에서 slug 마켓 1건을 받아 (outcomes, outcomePrices)를 반환.

    실패·빈 응답·파싱 실패 시 None — 호출자가 스킵 처리한다.
    """
    try:
        url = GAMMA_MARKETS_URL + "?slug=" + urllib.parse.quote(slug)
        req = urllib.request.Request(url, headers={"User-Agent": "pm-trader-scan/1.0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.load(resp)
        if not isinstance(data, list) or not data:
            return None
        m = data[0]
        outcomes = json.loads(m.get("outcomes") or "[]")
        prices = [float(p) for p in json.loads(m.get("outcomePrices") or "[]")]
        if not outcomes or len(outcomes) != len(prices):
            return None
        return outcomes, prices
    except Exception:  # noqa: BLE001 - 조회 실패는 스킵 사유로만 쓴다
        return None


def evaluate(pos):
    """포지션 1건을 평가해 결과 dict를 반환한다. 매도는 하지 않는다."""
    slug = pos["market_slug"]
    entry = float(pos["avg_entry_price"])
    result = {
        "slug": slug,
        "outcome": pos["outcome"],
        "shares": float(pos["shares"]),
        "total_cost": float(pos["total_cost"]),
        "entry": round(entry, 4),
        "stop_line": round(entry - DROP, 4),
        "current": None,
        "status": None,
    }
    gamma = fetch_gamma_market(slug)
    if gamma is None:
        result["status"] = "skipped_no_price"
        return result
    outcomes, prices = gamma
    idx = None
    for i, name in enumerate(outcomes):
        if str(name).lower() == str(pos["outcome"]).lower():
            idx = i
            break
    if idx is None:
        result["status"] = "skipped_no_outcome"
        return result
    current = prices[idx]
    result["current"] = current
    result["gamma_outcome"] = outcomes[idx]
    if current >= DECIDED_HIGH or current <= DECIDED_LOW:
        result["status"] = "excluded_decided"
    elif current <= entry - DROP:
        result["status"] = "triggered"
    else:
        result["status"] = "holding"
    return result


def execute_sell(ev):
    """발동한 포지션을 전량 매도하고 결과 필드를 ev에 채운다.

    1차는 기본 FOK. 유동성 부족으로 거부되면 FAK로 1회 재시도한다
    (부분 체결분이라도 회수하고, 잔량은 다음 사이클에 다시 평가된다).
    """
    shares_arg = str(ev["shares"])  # DB float의 정확한 왕복 표기 (보유량 초과 방지)
    outcome_arg = ev.get("gamma_outcome") or ev["outcome"]
    attempts = []
    for extra in ([], ["--type", "fak"]):
        cmd = [WRAPPER, "--account", ACCOUNT, "sell",
               ev["slug"], outcome_arg, shares_arg, *extra]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        except Exception as e:  # noqa: BLE001
            attempts.append({"cmd": " ".join(cmd[-3:]), "error": str(e)[:200]})
            continue
        out = (proc.stdout or "").strip()
        err = (proc.stderr or "").strip()
        parsed = None
        try:
            parsed = json.loads(out)
        except ValueError:
            pass
        if proc.returncode == 0 and parsed is not None:
            trade = ((parsed.get("data") or {}).get("trade") or {})
            gross = trade.get("amount_usd")
            fee = trade.get("fee") or 0.0
            sold = trade.get("shares")
            avg = trade.get("avg_price")
            if gross is not None and sold:
                net = gross - fee
                cost_basis = ev["total_cost"] * (sold / ev["shares"])
                ev["sell"] = {
                    "order_type": "fak" if extra else "fok",
                    "sold_shares": sold,
                    "avg_price": avg,
                    "gross": round(gross, 2),
                    "fee": round(fee, 2),
                    "proceeds": round(net, 2),
                    "pnl": round(net - cost_basis, 2),
                    "fully_sold": sold >= ev["shares"] - 1e-9,
                }
                ev["status"] = "sold" if ev["sell"]["fully_sold"] else "sold_partial"
                return ev
            ev["sell"] = {"error": "unparsable sell result", "raw": out[:300]}
            ev["status"] = "sell_failed"
            return ev
        attempts.append({
            "cmd": " ".join(cmd[-3:]), "returncode": proc.returncode,
            "stdout": out[:200], "stderr": err[:200],
        })
        # FOK 유동성 거부가 아니면 재시도하지 않는다.
        blob = (out + " " + err).lower()
        if extra or "liquidity" not in blob and "fok" not in blob:
            break
    ev["sell"] = {"error": "sell rejected", "attempts": attempts}
    ev["status"] = "sell_failed"
    return ev


def main():
    try:
        positions = load_open_positions()
    except sqlite3.Error as e:
        print(json.dumps({
            "checked_at": datetime.now(timezone.utc).isoformat(),
            "mode": "dry-run" if DRY_RUN else "execute",
            "account": ACCOUNT,
            "error": f"positions load failed: {e}",
            "positions": [],
            "triggered_count": 0,
        }, ensure_ascii=False))
        return 1
    results = [evaluate(p) for p in positions]
    if not DRY_RUN:
        for ev in results:
            if ev["status"] == "triggered":
                execute_sell(ev)
    out = {
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "mode": "dry-run" if DRY_RUN else "execute",
        "account": ACCOUNT,
        "positions": results,
        "triggered_count": sum(
            1 for r in results if r["status"] in ("triggered", "sold", "sold_partial")),
    }
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
