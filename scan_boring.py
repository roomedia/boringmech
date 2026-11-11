#!/usr/bin/env python3
"""Polymarket boring 전략 후보 탐지 스크립트 (기계적 필터만 수행, 판단·매수는 안 함).

출력: {"candidates": [...], "error_alert": bool}
- candidates: 신규 후보 + 재표면화 후보
  (slug, question, side, price, fee, volume, end_date, resurfaced, last_judged_price)
  ※ 후보 쪽(side)은 두 결과 중 가격이 높은 쪽(우세 쪽). 밴드는 0.60–0.90이다.
    (2026-11-04 사용자 정정: 2026-10-30에 "제한을 없애라"는 지시는 보유 건수 캡만
    가리킨 것이었고, 밴드 하한 0.60 제거는 그 지시의 오독이었음 — 밴드 60–90 복원.)
  ※ seen.json은 {slug: {"price": 마지막 판단가, "side": 쪽}} 기록형이다.
    한 번 판단한 마켓도 마지막 판단가에서 0.05(5¢) 이상 움직이면 재표면화한다
    (2026-10-30 사용자 지적: 한 번 체크한 마켓을 영구 제외하면 가격이 좋아져도
    다시는 체결 기회가 없었다). 판단가가 없는 구 기록(price null)은 1회 재표면화.
  ※ 이 스크립트는 seen을 갱신하지 않음. 판단 주체가 매수·기각 확정 후 seen.json에 기록
- error_alert: 스캔 연속 3회 실패 시 true (크론이 이 채팅에 1회 보고용)
- --dry-run: seen/state 파일을 건드리지 않고 후보만 출력

필터 (boring-strategy.md 기준):
- 활성 마켓, 만기 = 내일 ~ 45일 후 (당일 종료 마켓은 가격 신선도 위험으로 제외)
- 거래량 10,000 이상
- 크립토 단타 마켓 제외 (slug에 up-or-down 포함)
- 스포츠·이스포츠 경기 마켓 제외 (Gamma sportsMarketType이 있으면 제외, 2026-10-26 사용자 결정)
  ※ Gamma 조회 실패 시에는 후보를 유지하고 판단 단계에서 걸러지게 한다 (fail-open)
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import date, timedelta

BASE = os.path.dirname(os.path.abspath(__file__))
WRAPPER = os.path.join(BASE, "pm-trader.sh")
SEEN_PATH = os.path.join(BASE, "seen.json")
STATE_PATH = os.path.join(BASE, "scan_state.json")

DRY_RUN = "--dry-run" in sys.argv

RESURFACE_MOVE = 0.05  # 마지막 판단가에서 이만큼(5¢) 이상 움직이면 재표면화

SHORT_TERM_RE = re.compile(r"(bitcoin|ethereum|solana|xrp|dogecoin|cardano)-up-or-down|up-or-down-")


def load_json(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
    os.replace(tmp, path)


def load_seen():
    """seen.json을 {slug: {"price": float|None, "side": str|None}}로 정규화해 반환.

    구 형식(슬러그 리스트)이면 판단가 미기록(None)으로 취급한다.
    """
    raw = load_json(SEEN_PATH, {})
    if isinstance(raw, list):
        return {slug: {"price": None, "side": None} for slug in raw}
    if isinstance(raw, dict):
        return raw
    return {}


def is_sports_market(slug):
    """스포츠·이스포츠 경기 마켓이면 True. Gamma sportsMarketType이 있으면 경기 마켓이다.

    조회 실패 시 False를 반환한다 (fail-open: 후보를 유지하고 판단 단계에서 걸러지게).
    """
    try:
        url = ("https://gamma-api.polymarket.com/markets?slug="
               + urllib.parse.quote(slug))
        req = urllib.request.Request(url, headers={"User-Agent": "pm-trader-scan/1.0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.load(resp)
        market = data[0] if isinstance(data, list) and data else {}
        return bool(market.get("sportsMarketType"))
    except Exception:  # noqa: BLE001 - 조회 실패는 필터 미적용으로 처리
        return False


GAMMA_MARKETS_URL = "https://gamma-api.polymarket.com/markets"


def _normalize_market(m):
    """Gamma 원형(camelCase) → 필터가 쓰는 래퍼 정규형(snake_case)으로 변환."""
    try:
        prices = [float(p) for p in json.loads(m.get("outcomePrices") or "[]")]
    except (TypeError, ValueError):
        prices = []
    try:
        volume = float(m.get("volume") or 0)
    except (TypeError, ValueError):
        volume = 0
    return {
        "active": bool(m.get("active")),
        "closed": bool(m.get("closed")),
        "slug": m.get("slug", ""),
        "question": m.get("question", ""),
        "end_date": m.get("endDate", ""),
        "volume": volume,
        "outcome_prices": prices,
        "fee_schedule": m.get("feeSchedule"),
    }


def fetch_markets(total=1000, page_size=100):
    """Gamma에서 유동성 상위 total개를 직접 받아 정규형으로 반환한다.

    2026-11-08: pm-trader 래퍼의 내부 read timeout이 Gamma의 느린 대량 응답을
    못 버텨 스캔이 연속 실패했다. 매수·정산은 래퍼를 그대로 쓰고, 목록 조회만
    긴 timeout으로 직접 호출한다 (스포츠 조회가 이미 쓰는 경로와 동일).
    페이지당 1회 재시도, 그래도 실패하면 예외를 올려 기존 오류 상태 로직이 받는다.
    """
    markets = []
    offset = 0
    while len(markets) < total:
        params = urllib.parse.urlencode({
            "active": "true", "closed": "false",
            "order": "liquidity", "ascending": "false",
            "limit": page_size, "offset": offset,
        })
        url = f"{GAMMA_MARKETS_URL}?{params}"
        last_err = None
        for _attempt in range(2):
            try:
                req = urllib.request.Request(
                    url, headers={"User-Agent": "pm-trader-scan/1.0"})
                with urllib.request.urlopen(req, timeout=100) as resp:
                    page = json.load(resp)
                break
            except Exception as e:  # noqa: BLE001 - 1회 재시도 후 상위로
                last_err = e
                time.sleep(3)
        else:
            raise last_err
        if not isinstance(page, list) or not page:
            break
        markets.extend(_normalize_market(m) for m in page)
        if len(page) < page_size:
            break
        offset += page_size
    return markets[:total]


def main():
    seen = load_seen()
    state = load_json(STATE_PATH, {"consecutive_errors": 0})

    try:
        markets = fetch_markets()
    except Exception as e:  # noqa: BLE001 - 스캔 실패는 상태로만 기록
        state["consecutive_errors"] = state.get("consecutive_errors", 0) + 1
        alert = state["consecutive_errors"] >= 3
        if not DRY_RUN:
            save_json(STATE_PATH, state)
        print(json.dumps({"candidates": [], "error_alert": alert,
                          "error": str(e)[:200]}, ensure_ascii=False))
        return 0

    if not DRY_RUN:
        state["consecutive_errors"] = 0
        save_json(STATE_PATH, state)

    # 주의: seen.json 갱신은 스크립트가 하지 않는다. 후보를 판단한 주체(훅 워커)가
    # 매수·기각 여부를 확정한 뒤 판단가와 함께 seen.json에 기록한다. 그래야 run이
    # 중간에 죽어도 미판단 후보가 다음 run에 다시 뜬다.

    today = date.today()
    end_max = today + timedelta(days=45)
    candidates = []
    for m in markets:
        if not m.get("active") or m.get("closed"):
            continue
        slug = m.get("slug", "")
        if SHORT_TERM_RE.search(slug):
            continue
        end = (m.get("end_date") or "")[:10]
        if not (today.isoformat() < end <= end_max.isoformat()):
            continue
        if (m.get("volume") or 0) < 10000:
            continue
        prices = m.get("outcome_prices") or []
        if len(prices) < 2:
            continue
        # 후보 쪽 = 우세 쪽(가격이 높은 결과). 밴드: 0.60–0.90 (2026-11-04 복원).
        if prices[0] >= prices[1]:
            side, price = "yes", prices[0]
        else:
            side, price = "no", prices[1]
        if price < 0.60 or price > 0.90:
            continue
        info = seen.get(slug)
        resurfaced = False
        last_price = None
        if info is not None:
            last_price = info.get("price")
            if last_price is not None and abs(price - last_price) < RESURFACE_MOVE:
                continue  # 마지막 판단 이후 가격 변동이 작으면 다시 판단하지 않음
            resurfaced = True
        if is_sports_market(slug):
            continue
        fee = (m.get("fee_schedule") or {}).get("rate")
        candidates.append({
            "slug": slug,
            "question": (m.get("question") or "")[:200],
            "side": side,
            "price": price,
            "fee_rate": fee,
            "volume": round(m.get("volume") or 0),
            "end_date": end,
            "resurfaced": resurfaced,
            "last_judged_price": last_price,
        })

    print(json.dumps({"candidates": candidates, "error_alert": False},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
