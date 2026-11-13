#!/usr/bin/env python3
"""boringmech-micro-live 실행기 (SPEC.md가 정본).

모드:
  --status   현금·평가금·보유·슬롯·EOA POL 잔고 보고 (읽기 전용, 상태 파일 미변경)
  --dry-run  후보 선정·오더북 재검증·주문 파라미터 구성까지만. 서명·전송 안 함
  --cycle    정산 감지 + (게이트 통과 시) 충원 매수. 훅이 부르는 모드
  --redeem   정산된 포지션 redeem(회수) 실행. --dry-run과 함께 쓰면 제출 없이
             의도된 호출 내역만 출력한다. 실행은 실주문 게이트를 그대로 탄다
  --live     실주문 허용 플래그. 단독으로는 부족하고 아래 게이트를 전부 통과해야 한다

실주문 게이트 (3중, 하나라도 빠지면 주문 경로 거부):
  1) --live 플래그
  2) 환경변수 BORINGMECH_LIVE_CONFIRM=YES
  3) 전략 폴더에 LIVE_ENABLED 파일 존재
  --seed-first-only: 사이클당 매수를 1건으로 강제 (시드 단계 실행용)

자격증명 값은 어떤 출력·로그에도 쓰지 않는다. 경로로만 읽는다.
"""
import json
import math
import os
import random
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal

# ── 프록시 처리 ─────────────────────────────────────────────────────────────
# 실측(2026-11-11): httpx는 no_proxy의 IPv6 표기([::1] 등)를 URL 패턴으로
# 파싱하다 import 단계에서 죽는다. 반면 프록시를 전부 제거하면 이 VM의 직접
# 출구가 TLS를 가로채 SSL 오류가 난다. 정답은 no_proxy만 제거하고
# http(s)_proxy·all_proxy는 유지해 egress 프록시를 경유하는 것.
_ORIG_ENV = dict(os.environ)
for _k in ("no_proxy", "NO_PROXY"):
    os.environ.pop(_k, None)

_BASE = os.path.dirname(os.path.abspath(__file__))
PM_DIR = os.path.dirname(_BASE)
STATE_PATH = os.path.join(_BASE, "state.json")
LOG_PATH = os.path.join(_BASE, "run-log.md")
LIVE_ENABLED_PATH = os.path.join(_BASE, "LIVE_ENABLED")
KEY_PATH = os.environ.get("PM_WALLET_KEY_PATH",
                          os.path.expanduser("~/.config/polymarket/wallet.key"))
CREDS_PATH = os.environ.get("PM_CREDS_PATH",
                            os.path.expanduser("~/.config/polymarket/creds.json"))
BUILDER_PATH = os.environ.get("PM_BUILDER_PATH",
                              os.path.expanduser("~/.config/polymarket/builder.json"))

EOA = os.environ.get("PM_EOA_ADDRESS", "")  # signer EOA — 주소는 레포에 기록하지 않고 환경변수로 주입
DW = os.environ.get("PM_DEPOSIT_WALLET", "")  # deposit wallet (funder) — 환경변수로 주입
CLOB_HOST = "https://clob.polymarket.com"
CHAIN_ID = 137
SIG_TYPE = 3

BET_USD = 5.0
DEFAULT_RESERVE_USD = 2.87
BAND_LO, BAND_HI = 0.70, 0.90
SPREAD_MAX = 0.02
ASK_DEPTH_MIN_USD = 10.0
SLIPPAGE_CAP = 0.015
SNAPSHOT_MATCH_TOL = 0.03
HALT_EQUITY = 35.0
EVAL_EQUITY = 100.0
EVAL_SETTLED = 20
VERIFY_CAP = 60  # 사이클당 오더북 재검증 상한
LOW_WATER_USD = 0.50
POL_USD_FALLBACK = 0.09  # 가격 조회 실패 시 근사값 (SPEC에 명시된 폴백)

KST = timezone(timedelta(hours=9))
GAMMA_MARKETS_URL = "https://gamma-api.polymarket.com/markets"
DATA_API_V2 = "https://data-api.polymarket.com/v2"
POLYGON_RPCS = [
    "https://polygon-bor-rpc.publicnode.com",
    "https://1rpc.io/matic",
    "https://polygon.drpc.org",
    "https://polygon-mainnet.public.blastapi.io",
]

FAILSAFE_KEYWORDS = ["403", "forbidden", "geoblock", "geo-block", "signature",
                     "maker address not allowed", "invalid signature",
                     "not allowed"]

# ── redeem 상수 ─────────────────────────────────────────────────────────────
# ConditionalTokens (Polygon). polymarket-client environments PRODUCTION과 동일.
CTF_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"
RELAYER_URL = "https://relayer-v2.polymarket.com"  # SDK 기본값과 동일 (표시용)
ZERO_BYTES32 = "0x" + "00" * 32
REDEEM_TOL_ABS = 0.05  # 잔고 델타 검증 허용 오차 (절대, USD)
REDEEM_TOL_REL = 0.02  # 잔고 델타 검증 허용 오차 (기대 회수액 비례)


def now_kst():
    return datetime.now(KST)


def iso_now():
    return now_kst().isoformat(timespec="seconds")


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


def log_md(text):
    with open(LOG_PATH, "a") as f:
        f.write(text)


def _proxy_opener():
    proxies = {}
    for scheme in ("http", "https"):
        v = _ORIG_ENV.get(f"{scheme}_proxy") or _ORIG_ENV.get(f"{scheme.upper()}_PROXY")
        if v:
            proxies[scheme] = v
    return urllib.request.build_opener(urllib.request.ProxyHandler(proxies))


def http_get_json(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": "boringmech-live/1.0"})
    with _proxy_opener().open(req, timeout=timeout) as resp:
        return json.load(resp)


# ── 계정 스냅샷 ─────────────────────────────────────────────────────────────

def make_client():
    from py_clob_client_v2 import ClobClient
    from py_clob_client_v2.clob_types import ApiCreds

    key = open(KEY_PATH).read().strip()
    c = json.load(open(CREDS_PATH))
    creds = ApiCreds(api_key=c["api_key"], api_secret=c["api_secret"],
                     api_passphrase=c["api_passphrase"])
    return ClobClient(CLOB_HOST, chain_id=CHAIN_ID, key=key, creds=creds,
                      signature_type=SIG_TYPE, funder=DW)


def fetch_positions():
    """Data API v2 positions 전 페이지. snake_case 스키마."""
    out = []
    cursor = None
    while True:
        params = {"user": DW, "limit": 100}
        if cursor:
            params["cursor"] = cursor
        url = f"{DATA_API_V2}/positions?" + urllib.parse.urlencode(params)
        page = http_get_json(url)
        out.extend(page.get("data") or [])
        cursor = (page.get("pagination") or {}).get("next_cursor")
        if not cursor:
            return out


def fetch_positions_value():
    url = f"{DATA_API_V2}/value?" + urllib.parse.urlencode({"user": DW})
    data = http_get_json(url)
    return float((data.get("data") or {}).get("value") or 0.0)


def eoa_pol_balance():
    """EOA POL 잔고. 공개 RPC를 순서대로 시도하고 전부 실패하면 None.

    반환: {"pol": float|None, "pol_usd": float|None, "low_water": bool|None}
    """
    payload = {"jsonrpc": "2.0", "id": 1, "method": "eth_getBalance",
               "params": [EOA, "latest"]}
    pol = None
    for rpc in POLYGON_RPCS:
        try:
            req = urllib.request.Request(
                rpc, data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json"})
            with _proxy_opener().open(req, timeout=12) as resp:
                res = json.load(resp)
            pol = int(res["result"], 16) / 1e18
            break
        except Exception:  # noqa: BLE001 - 다음 RPC로
            continue
    if pol is None:
        return {"pol": None, "pol_usd": None, "low_water": None}
    price = POL_USD_FALLBACK
    try:
        q = urllib.parse.urlencode(
            {"ids": "polygon-ecosystem-token", "vs_currencies": "usd"})
        data = http_get_json(
            f"https://api.coingecko.com/api/v3/simple/price?{q}", timeout=12)
        price = float(data["polygon-ecosystem-token"]["usd"])
    except Exception:  # noqa: BLE001 - 폴백 상수 사용
        pass
    usd = pol * price
    return {"pol": pol, "pol_usd": round(usd, 4), "low_water": usd < LOW_WATER_USD}


def snapshot(client, state):
    from py_clob_client_v2.clob_types import AssetType, BalanceAllowanceParams

    bal = client.get_balance_allowance(
        BalanceAllowanceParams(asset_type=AssetType.COLLATERAL))
    # 회계는 센트 정밀도로 한다 (SPEC 회계 노트). 원시 잔고는 57.866710처럼
    # 서브센트 먼지를 포함하므로, 슬롯 계산 전에 반올림하지 않으면
    # 54.9967 → 10슬롯으로 잘못 잘린다.
    cash = round(int(bal.get("balance") or 0) / 1e6, 2)
    positions = fetch_positions()
    positions_value = round(fetch_positions_value(), 2)
    equity = round(cash + positions_value, 2)
    reserve = float(state.get("reserve_usd", DEFAULT_RESERVE_USD))
    available = round(max(0.0, cash - reserve), 2)
    slots = int((available + 1e-9) // BET_USD)
    open_positions = [p for p in positions if p.get("status") == "OPEN"]
    redeemable = [p for p in positions
                  if p.get("status") in ("REDEEMABLE", "REDEEMABLE_LOST")]
    halted = equity < HALT_EQUITY
    eval_flag = state.get("eval_flag")
    if eval_flag is None:
        if equity >= EVAL_EQUITY:
            eval_flag = "equity_100"
        elif int(state.get("settled_count", 0)) >= EVAL_SETTLED:
            eval_flag = "settled_20"
    drags = [f["drag"] for f in state.get("fills", []) if f.get("drag") is not None]
    return {
        "cash": round(cash, 2),
        "reserve_usd": reserve,
        "available_cash": round(available, 2),
        "positions_count": len(open_positions),
        "positions_value": round(positions_value, 2),
        "equity": round(equity, 2),
        "slots": slots,
        "halted": halted,
        "blocked": bool(state.get("blocked")),
        "eval_flag": eval_flag,
        "settled_count": int(state.get("settled_count", 0)),
        "wins": int(state.get("wins", 0)),
        "drag_avg": round(sum(drags) / len(drags), 4) if drags else None,
        "positions": positions,
        "redeemable": redeemable,
    }


# ── 후보·재검증 ─────────────────────────────────────────────────────────────

def fetch_live_candidates():
    """scan_boring의 마켓 필터를 그대로 적용하되 seen/재표면화 게이트는 빼고
    현재 풀 전수를 반환한다 (라이브는 전체 재평가가 확정 규칙 — SPEC 참조).

    필터: 유동성 상위 1,000 → 활성·미종료 → 크립토 단타 제외 → 만기 내일~+45일
    → 거래량 ≥ 10,000 → 우세 쪽 → 페이퍼 밴드 0.60–0.90 (풀 형성용;
    라이브 밴드 0.70–0.90은 verify_candidate가 스냅샷·ask 양쪽에 적용)
    → 스포츠 경기 마켓 제외.
    """
    from datetime import date, timedelta

    sys.path.insert(0, PM_DIR)
    import scan_boring as scan

    markets = scan.fetch_markets()
    today = date.today()
    end_max = today + timedelta(days=45)
    out = []
    for m in markets:
        if not m.get("active") or m.get("closed"):
            continue
        slug = m.get("slug", "")
        if scan.SHORT_TERM_RE.search(slug):
            continue
        end = (m.get("end_date") or "")[:10]
        if not (today.isoformat() < end <= end_max.isoformat()):
            continue
        if (m.get("volume") or 0) < 10000:
            continue
        prices = m.get("outcome_prices") or []
        if len(prices) < 2:
            continue
        if prices[0] >= prices[1]:
            side, price = "yes", prices[0]
        else:
            side, price = "no", prices[1]
        if price < 0.60 or price > 0.90:
            continue
        if scan.is_sports_market(slug):
            continue
        fee = (m.get("fee_schedule") or {}).get("rate")
        out.append({
            "slug": slug,
            "question": (m.get("question") or "")[:200],
            "side": side,
            "price": price,
            "fee_rate": fee,
            "volume": round(m.get("volume") or 0),
            "end_date": end,
            "resurfaced": False,
            "last_judged_price": None,
        })
    return out


def gamma_market(slug):
    url = GAMMA_MARKETS_URL + "?" + urllib.parse.urlencode({"slug": slug})
    data = http_get_json(url, timeout=20)
    return data[0] if isinstance(data, list) and data else {}


def round_tick(price, tick):
    t = Decimal(str(tick))
    q = (Decimal(str(price)) / t).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return float(q * t)


def verify_candidate(client, cand, held_slugs, ever_bought):
    """반환: (통과 여부, 사유, 상세 dict). 상세에는 주문 구성 재료가 들어 있다."""
    slug = cand["slug"]
    if slug in ever_bought:
        return False, "ever_bought", {}
    if slug in held_slugs:
        return False, "already_held", {}
    snap_price = float(cand["price"])
    if not (BAND_LO <= snap_price <= BAND_HI):
        return False, f"snapshot_out_of_live_band({snap_price})", {}
    m = gamma_market(slug)
    if not m:
        return False, "gamma_market_missing", {}
    try:
        tokens = json.loads(m.get("clobTokenIds") or "[]")
        outcomes = json.loads(m.get("outcomes") or "[]")
        prices = [float(p) for p in json.loads(m.get("outcomePrices") or "[]")]
    except (TypeError, ValueError):
        return False, "gamma_parse_error", {}
    idx = 0 if cand["side"] == "yes" else 1
    if len(tokens) <= idx or len(prices) <= idx:
        return False, "gamma_shape_error", {}
    if abs(prices[idx] - snap_price) > SNAPSHOT_MATCH_TOL:
        return False, f"snapshot_mismatch(gamma {prices[idx]} vs scan {snap_price})", {}
    token_id = tokens[idx]
    book = client.get_order_book(token_id)  # v2는 dict를 반환한다
    raw_asks = book.get("asks") or []
    raw_bids = book.get("bids") or []
    asks = sorted(raw_asks, key=lambda a: float(a["price"]))
    bids = sorted(raw_bids, key=lambda b: float(b["price"]), reverse=True)
    if not asks:
        return False, "no_asks", {}
    if not bids:
        return False, "no_bids", {}
    best_ask = float(asks[0]["price"])
    best_ask_size = float(asks[0]["size"])
    best_bid = float(bids[0]["price"])
    if not (BAND_LO <= best_ask <= BAND_HI):
        return False, f"ask_out_of_band({best_ask})", {}
    spread = best_ask - best_bid
    if spread > SPREAD_MAX + 1e-9:
        return False, f"spread_too_wide({spread:.3f})", {}
    depth_usd = best_ask_size * best_ask
    if depth_usd < ASK_DEPTH_MIN_USD:
        return False, f"ask_depth_low(${depth_usd:.2f})", {}
    tick = float(book.get("tick_size") or client.get_tick_size(token_id))
    limit_price = min(round_tick(best_ask + SLIPPAGE_CAP, tick), BAND_HI)
    shares = math.floor(BET_USD / limit_price * 100) / 100
    min_size = float(book.get("min_order_size") or 0)
    if shares < min_size:
        return False, f"below_min_order_size({shares} < {min_size})", {}
    events = m.get("events") or [{}]
    detail = {
        "slug": slug,
        "question": cand.get("question"),
        "side": cand["side"],
        "outcome": outcomes[idx] if len(outcomes) > idx else cand["side"],
        "token_id": token_id,
        "condition_id": m.get("conditionId"),
        "event_id": (events[0] or {}).get("id"),
        "event_slug": (events[0] or {}).get("slug"),
        "snapshot_price": snap_price,
        "best_ask": best_ask,
        "best_bid": best_bid,
        "spread": round(spread, 4),
        "ask_size_shares": best_ask_size,
        "ask_depth_usd": round(depth_usd, 2),
        "tick_size": tick,
        "neg_risk": bool(book.get("neg_risk")),
        "limit_price": limit_price,
        "shares": shares,
        "usd": BET_USD,
        "max_expected_drag": round(limit_price - snap_price, 4),
        "end_date": cand.get("end_date"),
        "volume": cand.get("volume"),
    }
    return True, "ok", detail


# ── 정산 감지 ───────────────────────────────────────────────────────────────

def detect_settlements(state, snap):
    """REDEEMABLE/REDEEMABLE_LOST 신규분을 settled·redeemable_pending에 반영.

    state를 직접 변경하고 변경 건수를 반환한다 (호출자가 저장).
    """
    seen = {s["condition_id"] + ":" + s["token_id"] for s in state.get("settled", [])}
    new_items = []
    for p in snap["redeemable"]:
        key = (p.get("condition_id") or "") + ":" + (p.get("token_id") or "")
        if key in seen:
            continue
        won = p.get("status") == "REDEEMABLE"
        state.setdefault("settled", []).append({
            "condition_id": p.get("condition_id"),
            "token_id": p.get("token_id"),
            "slug": p.get("slug"),
            "outcome": p.get("outcome"),
            "won": won,
            "payout_value": p.get("current_value"),
            "detected_at": iso_now(),
        })
        state["settled_count"] = int(state.get("settled_count", 0)) + 1
        if won:
            state["wins"] = int(state.get("wins", 0)) + 1
        state.setdefault("redeemable_pending", []).append({
            "condition_id": p.get("condition_id"),
            "slug": p.get("slug"),
            "outcome": p.get("outcome"),
            "status": p.get("status"),
            "current_value": p.get("current_value"),
        })
        new_items.append({"slug": p.get("slug"), "won": won,
                          "value": p.get("current_value")})
    return new_items


# ── 주문 (실주문 경로는 게이트 통과 시에만 도달) ─────────────────────────────

def live_gate_open(args):
    if not args.live:
        return False, "--live 플래그 없음"
    if os.environ.get("BORINGMECH_LIVE_CONFIRM") != "YES":
        return False, "환경변수 BORINGMECH_LIVE_CONFIRM=YES 없음"
    if not os.path.exists(LIVE_ENABLED_PATH):
        return False, "LIVE_ENABLED 파일 없음"
    return True, "ok"


def is_failsafe_trigger(text):
    t = (text or "").lower()
    return any(k in t for k in FAILSAFE_KEYWORDS)


def place_order(client, detail):
    """지정가 FOK 매수. 반환: (결과 dict, fail-safe 발동 여부)."""
    from py_clob_client_v2.clob_types import (OrderArgsV2, OrderType,
                                              PartialCreateOrderOptions)
    from py_clob_client_v2.order_utils.model.side import Side

    order_args = OrderArgsV2(
        token_id=detail["token_id"], price=detail["limit_price"],
        size=detail["shares"], side=Side.BUY)
    options = PartialCreateOrderOptions(tick_size=str(detail["tick_size"]),
                                        neg_risk=detail["neg_risk"])
    try:
        resp = client.create_and_post_order(order_args, options,
                                            order_type=OrderType.FOK)
    except Exception as e:  # noqa: BLE001 - 응답 문자열로 fail-safe 판정
        msg = str(e)
        return {"ok": False, "error": msg[:300]}, is_failsafe_trigger(msg)
    resp = resp or {}
    err = resp.get("errorMsg") or ""
    if err:
        return {"ok": False, "error": err[:300],
                "raw_status": resp.get("status")}, is_failsafe_trigger(err)
    making = resp.get("makingAmount")
    taking = resp.get("takingAmount")
    fill_price = None
    shares = detail["shares"]
    if making is not None and taking not in (None, 0, "0"):
        fill_price = float(making) / float(taking)
        shares = float(taking)
    return {"ok": True, "order_id": resp.get("orderID"),
            "status": resp.get("status"), "fill_price": fill_price,
            "shares": shares,
            "tx_hashes": resp.get("transactionsHashes")}, False


# ── redeem (정산 회수) ──────────────────────────────────────────────────────
# 1차 경로: 통합 SDK(polymarket-client) SecureClient.redeem_positions —
# EOA가 서명만 하고 deposit wallet 배치를 relayer(RELAYER_URL)에 gasless
# 제출한다. SDK가 마켓 컨텍스트를 해석해 실제 콜 타깃(V2 마켓은
# CtfCollateralAdapter, 구형은 CTF 본체)을 정하므로, 아래 build_redeem_call은
# dry-run 표시·로깅용 표준형이다 (콜 모양을 SDK 빌더와 바이트 단위로 대조해
# test_redeem.py가 검증한다).
# 폴백(EOA가 DW 배치를 직접 execute): SDK dispatch의 무키 동작이 미검증이고
# EOA POL 잔고도 0이라 구현하지 않는다 — [미확인] (SPEC redeem 절).

class RedeemUnavailable(Exception):
    """redeem 전송 계층을 쓸 수 없는 구성 오류 (Builder 키 없음 등)."""


def build_redeem_call(condition_id, collateral, target=CTF_ADDRESS):
    """redeemPositions(collateral, 0x0, conditionId, [1,2]) 콜 구성 (순수 함수).

    반환: {to, function, collateral, parent_collection_id, condition_id,
    index_sets, data}. condition_id가 32바이트 hex가 아니면 ValueError.
    """
    from eth_abi import encode as abi_encode
    from eth_utils import function_signature_to_4byte_selector

    cid = (condition_id or "").lower()
    raw = cid[2:] if cid.startswith("0x") else cid
    if len(raw) != 64:
        raise ValueError(f"condition_id 형식 오류: {condition_id!r}")
    cid_bytes = bytes.fromhex(raw)
    selector = function_signature_to_4byte_selector(
        "redeemPositions(address,bytes32,bytes32,uint256[])")
    data = selector + abi_encode(
        ["address", "bytes32", "bytes32", "uint256[]"],
        [collateral, b"\x00" * 32, cid_bytes, [1, 2]])
    return {"to": target, "function": "redeemPositions",
            "collateral": collateral, "parent_collection_id": ZERO_BYTES32,
            "condition_id": "0x" + raw, "index_sets": [1, 2],
            "data": "0x" + data.hex()}


def sdk_collateral_token():
    """통합 SDK 환경 설정의 담보 토큰(pUSD) 주소. 실패하면 None."""
    try:
        from polymarket.environments import PRODUCTION

        return str(PRODUCTION._config.collateral_token)
    except Exception:  # noqa: BLE001
        return None


def plan_redeems(state):
    """redeemable_pending → 실행 계획 (순수 함수).

    condition 단위로 묶는다. redeemPositions는 한 condition의 양쪽
    indexSet을 함께 정산하므로:
    - 승리 항목(REDEEMABLE이고 current_value > 0)이 있는 condition만 호출
      대상이다. 같은 condition의 패배 항목도 그 호출로 함께 소멸한다.
    - 패배만 있는 condition은 호출하지 않고 worthless로 분류해 종결한다.
    반환: {"targets": [...], "worthless": [...], "errors": [...]}
    """
    groups, order, errors = {}, [], []
    for e in state.get("redeemable_pending", []):
        cid = (e.get("condition_id") or "").lower()
        raw = cid[2:] if cid.startswith("0x") else cid
        if len(raw) != 64:
            errors.append({"entry": e, "reason": "condition_id 형식 오류"})
            continue
        key = "0x" + raw
        if key not in groups:
            groups[key] = {"condition_id": key, "wins": [], "losses": []}
            order.append(key)
        won = (e.get("status") == "REDEEMABLE"
               and (e.get("current_value") or 0) > 0)
        groups[key]["wins" if won else "losses"].append(e)
    targets, worthless = [], []
    for key in order:
        g = groups[key]
        if g["wins"]:
            expected = round(sum(float(e.get("current_value") or 0)
                                 for e in g["wins"]), 2)
            targets.append({**g, "expected_usd": expected})
        else:
            worthless.extend(g["losses"])
    return {"targets": targets, "worthless": worthless, "errors": errors}


def verify_recovery(before_cash, after_cash, expected_usd):
    """redeem 전후 담보 잔고 델타로 회수액 검증 (순수 함수)."""
    delta = round(after_cash - before_cash, 2)
    tol = max(REDEEM_TOL_ABS, REDEEM_TOL_REL * (expected_usd or 0))
    status = ("verified" if abs(delta - (expected_usd or 0)) <= tol
              else "mismatch")
    return {"delta_usd": delta, "expected_usd": expected_usd,
            "tolerance": round(tol, 2), "status": status}


class SdkRelayerRedeemTransport:
    """relayer gasless 전송 (1차 경로). 제출 단위 = condition 1건.

    submit(condition_id) → {"tx_hash", "transaction_id"}. 실패는 예외로
    올린다 (호출자가 건별로 스킵 처리한다). 자격증명 값은 읽되 출력하지
    않는다.
    """

    name = "relayer-sdk"

    def __init__(self):
        self._client = None

    def prepare(self):
        if self._client is not None:
            return
        if not DW:
            raise RedeemUnavailable("PM_DEPOSIT_WALLET 환경변수 없음")
        if not os.path.exists(BUILDER_PATH):
            raise RedeemUnavailable(
                f"Builder 키 파일 없음 ({BUILDER_PATH}) — "
                "키 발급이 먼저다 (SPEC redeem 절)")
        from polymarket import ApiKeyCreds, SecureClient
        from polymarket.auth import BuilderApiKey

        key = open(KEY_PATH).read().strip()
        c = json.load(open(CREDS_PATH))
        b = json.load(open(BUILDER_PATH))
        creds = ApiKeyCreds(key=c["api_key"], secret=c["api_secret"],
                            passphrase=c["api_passphrase"])
        builder = BuilderApiKey(key=b["key"], secret=b["secret"],
                                passphrase=b["passphrase"])
        # create()는 지갑 준비 상태(배포·승인)를 확인할 수 있다 — SDK 정본 경로.
        create_kwargs = {"private_key": key, "wallet": DW,
                         "credentials": creds, "api_key": builder}
        self._client = SecureClient.create(**create_kwargs)

    def submit(self, condition_id):
        self.prepare()
        handle = self._client.redeem_positions(condition_id=condition_id)
        outcome = handle.wait()  # 종단 성공이면 반환, 실패·타임아웃이면 예외
        return {"tx_hash": str(outcome.transaction_hash),
                "transaction_id": outcome.transaction_id}


def cash_balance(client):
    """CLOB 담보(pUSD) 잔고 — snapshot()의 현금 계산과 같은 규칙(센트 반올림)."""
    from py_clob_client_v2.clob_types import AssetType, BalanceAllowanceParams

    bal = client.get_balance_allowance(
        BalanceAllowanceParams(asset_type=AssetType.COLLATERAL))
    return round(int(bal.get("balance") or 0) / 1e6, 2)


def _close_pending_condition(state, condition_id, record):
    """condition의 pending 항목을 제거하고 history·settled에 반영한다."""
    state["redeemable_pending"] = [
        e for e in state.get("redeemable_pending", [])
        if (e.get("condition_id") or "").lower() != condition_id]
    state.setdefault("redeem_history", []).append(record)
    for s in state.get("settled", []):
        if (s.get("condition_id") or "").lower() == condition_id:
            s["redeemed"] = True
            s["redeem_tx"] = record.get("tx_hash")


def execute_redeem_plan(state, plan, transport, cash_fn):
    """계획 실행. 건별 실패는 pending에 남기고 계속한다 (영구 래치 없음 —
    2026-11-14 확정 규칙). state를 직접 변경하고 건별 결과 목록을 반환한다.
    """
    results = []
    for t in plan["targets"]:
        cid = t["condition_id"]
        slugs = [e.get("slug") for e in t["wins"] + t["losses"]]
        try:
            before = cash_fn()
            res = transport.submit(cid)
        except Exception as e:  # noqa: BLE001 - 건별 스킵
            results.append({"condition_id": cid, "slugs": slugs, "ok": False,
                            "error": str(e)[:300]})
            continue
        # 제출은 끝났다. 이후 잔고 측정이 실패해도 재제출하면 중복 redeem이
        # 되므로, submitted_unverified로 종결하고 tx만 기록한다.
        try:
            ver = verify_recovery(before, cash_fn(), t["expected_usd"])
            recovered, vstatus = ver["delta_usd"], ver["status"]
        except Exception as e:  # noqa: BLE001
            recovered, vstatus = None, "submitted_unverified"
            ver = {"error": str(e)[:200]}
        record = {"ts": iso_now(), "kind": "redeem", "condition_id": cid,
                  "slugs": slugs, "expected_usd": t["expected_usd"],
                  "recovered_usd": recovered, "tx_hash": res.get("tx_hash"),
                  "transaction_id": res.get("transaction_id"),
                  "transport": getattr(transport, "name", "unknown"),
                  "verify_status": vstatus, "verify_detail": ver}
        _close_pending_condition(state, cid, record)
        results.append({"condition_id": cid, "slugs": slugs, "ok": True,
                        "recovered_usd": recovered,
                        "tx_hash": res.get("tx_hash"),
                        "verify_status": vstatus})
    for e in plan["worthless"]:
        cid = (e.get("condition_id") or "").lower()
        record = {"ts": iso_now(), "kind": "worthless_close",
                  "condition_id": cid, "slugs": [e.get("slug")],
                  "expected_usd": 0.0, "recovered_usd": 0.0, "tx_hash": None,
                  "transaction_id": None, "transport": None,
                  "verify_status": "not_applicable",
                  "note": "패배 포지션(가치 0) — 호출 없이 종결"}
        _close_pending_condition(state, cid, record)
        results.append({"condition_id": cid, "slugs": [e.get("slug")],
                        "ok": True, "recovered_usd": 0.0, "tx_hash": None,
                        "verify_status": "worthless_closed"})
    return results


# ── 모드 ────────────────────────────────────────────────────────────────────

def select_and_verify(client, state, snap):
    """후보 전수 재평가. 반환: (통과 상세 목록, 탈락 목록, 후보 원본 수)."""
    candidates = fetch_live_candidates()
    held_slugs = {p.get("slug") for p in snap["positions"]
                  if p.get("status") == "OPEN"}
    ever_bought = set(state.get("ever_bought") or [])
    passing, rejected = [], []
    for cand in candidates[:VERIFY_CAP]:
        ok, reason, detail = verify_candidate(client, cand, held_slugs,
                                              ever_bought)
        if ok:
            passing.append(detail)
        else:
            rejected.append({"slug": cand["slug"], "reason": reason})
    if len(candidates) > VERIFY_CAP:
        rejected.append({"slug": f"({len(candidates) - VERIFY_CAP}건)",
                         "reason": "verify_cap_truncated"})
    return passing, rejected, len(candidates)


def fmt_snapshot_line(snap, pol):
    pol_txt = "읽기 실패"
    if pol["pol"] is not None:
        pol_txt = f"{pol['pol']:.4f} POL (${pol['pol_usd']})"
        if pol["low_water"]:
            pol_txt += " ⚠ low-water ($0.50 미만)"
    return (f"현금 ${snap['cash']} (예비 ${snap['reserve_usd']} 동결, "
            f"가용 ${snap['available_cash']}) · 포지션 {snap['positions_count']}건 "
            f"${snap['positions_value']} · 평가금 ${snap['equity']} · "
            f"슬롯 {snap['slots']} · halted={snap['halted']} · "
            f"blocked={snap['blocked']} · 정산 {snap['settled_count']}건"
            f"·승 {snap['wins']} · drag_avg={snap['drag_avg']} · "
            f"EOA POL {pol_txt}")


def mode_status(client, state):
    snap = snapshot(client, state)
    pol = eoa_pol_balance()
    out = {k: snap[k] for k in ("cash", "reserve_usd", "available_cash",
                                "positions_count", "positions_value", "equity",
                                "slots", "halted", "blocked", "eval_flag",
                                "settled_count", "wins", "drag_avg")}
    out["eoa_pol"] = pol
    out["redeemable_pending"] = [
        {"slug": p.get("slug"), "status": p.get("status"),
         "value": p.get("current_value")} for p in snap["redeemable"]]
    print(json.dumps({"mode": "status", **out}, ensure_ascii=False))
    log_md(f"\n### {iso_now()} status\n- {fmt_snapshot_line(snap, pol)}\n")
    return 0


def mode_dry_run(client, state):
    snap = snapshot(client, state)
    pol = eoa_pol_balance()
    result = {"mode": "dry-run", "cash": snap["cash"],
              "available_cash": snap["available_cash"], "equity": snap["equity"],
              "slots": snap["slots"], "halted": snap["halted"],
              "blocked": snap["blocked"]}
    lines = [f"\n### {iso_now()} dry-run",
             f"- {fmt_snapshot_line(snap, pol)}"]
    pick = None
    if snap["halted"] or snap["blocked"] or snap["slots"] < 1:
        reason = ("blocked" if snap["blocked"] else
                  "halted" if snap["halted"] else "no_slots")
        result["pick"] = None
        result["skip_reason"] = reason
        lines.append(f"- 선정 없음: {reason}")
    else:
        passing, rejected, total = select_and_verify(client, state, snap)
        result["candidates_total"] = total
        result["passing_count"] = len(passing)
        result["rejected"] = rejected
        lines.append(f"- 후보 {total}건 → 재검증 통과 {len(passing)}건, "
                     f"탈락 {len(rejected)}건")
        for r in rejected:
            lines.append(f"  - 탈락 {r['slug']}: {r['reason']}")
        for d in passing:
            lines.append(f"  - 통과 {d['slug']} ({d['outcome']}) "
                         f"ask {d['best_ask']} / spread {d['spread']} / "
                         f"잔량 ${d['ask_depth_usd']} / event {d['event_slug']}")
        if passing:
            pick = random.SystemRandom().choice(passing)
            result["pick"] = {
                "slug": pick["slug"], "outcome": pick["outcome"],
                "snapshot_price": pick["snapshot_price"],
                "best_ask": pick["best_ask"], "spread": pick["spread"],
                "ask_depth_usd": pick["ask_depth_usd"],
                "order": {"token_id": pick["token_id"], "side": "BUY",
                          "type": "FOK", "price": pick["limit_price"],
                          "size": pick["shares"], "usd": pick["usd"],
                          "tick_size": pick["tick_size"],
                          "neg_risk": pick["neg_risk"]},
                "max_expected_drag": pick["max_expected_drag"],
            }
            lines.append(f"- 무작위 선정: {pick['slug']} ({pick['outcome']}) "
                         f"스냅샷 {pick['snapshot_price']} → 지정가 "
                         f"{pick['limit_price']} × {pick['shares']}주 FOK "
                         f"(최대 예상 drag {pick['max_expected_drag']})")
            lines.append("- 주문은 구성만 했다 (서명·전송 없음).")
        else:
            result["pick"] = None
            lines.append("- 통과분 없음 — 선정 없음.")
    log_md("\n".join(lines) + "\n")
    print(json.dumps(result, ensure_ascii=False))
    return 0


def mode_cycle(client, state, args):
    snap = snapshot(client, state)
    events = []
    new_settled = detect_settlements(state, snap)
    if new_settled:
        events.append("settlement")
        snap = snapshot(client, state)  # 정산 반영 후 재계산
    state["halted"] = snap["halted"]
    if snap["eval_flag"]:
        state["eval_flag"] = snap["eval_flag"]
        events.append("eval_gate")
    gate_ok, gate_reason = live_gate_open(args)
    bought = None
    if snap["blocked"]:
        events.append("blocked")
    elif snap["halted"]:
        events.append("halted")
    elif snap["slots"] < 1:
        events.append("no_slots")
    elif not gate_ok:
        events.append("observe_only")
    else:
        max_buys = 1 if args.seed_first_only else args.max_buys
        for _ in range(max_buys):
            passing, rejected, _total = select_and_verify(client, state, snap)
            if not passing:
                events.append("no_passing_candidates")
                break
            pick = random.SystemRandom().choice(passing)
            res, failsafe = place_order(client, pick)
            if failsafe:
                # 2026-11-14 확정 규칙: 영구 차단 래치를 두지 않는다.
                # 403 등 failsafe 응답은 이번 실행만 중단하고 사유를 보고하며,
                # 다음 실행에서 플랫폼의 현재 응답을 다시 받는다.
                reason = (res.get("error") or "")[:300]
                events.append("failsafe_stop")
                log_md(f"\n### {iso_now()} FAIL-SAFE (이번 실행 중단, 영구 차단 아님)\n"
                       f"- {pick['slug']}: {reason}\n")
                break
            if not res.get("ok"):
                events.append("order_unfilled")
                log_md(f"\n### {iso_now()} 주문 미체결\n"
                       f"- {pick['slug']} 지정가 {pick['limit_price']} "
                       f"FOK 실패: {res.get('error')}\n")
                break
            fill_price = res.get("fill_price") or pick["limit_price"]
            drag = round(fill_price - pick["snapshot_price"], 4)
            state.setdefault("fills", []).append({
                "ts": iso_now(), "slug": pick["slug"],
                "event_id": pick["event_id"], "event_slug": pick["event_slug"],
                "side": pick["side"], "outcome": pick["outcome"],
                "token_id": pick["token_id"],
                "condition_id": pick["condition_id"],
                "snapshot_price": pick["snapshot_price"],
                "limit_price": pick["limit_price"], "fill_price": fill_price,
                "shares": res.get("shares"), "usd": pick["usd"], "drag": drag,
                "order_id": res.get("order_id")})
            state.setdefault("ever_bought", []).append(pick["slug"])
            bought = {"slug": pick["slug"], "fill_price": fill_price,
                      "drag": drag}
            events.append("fill")
            log_md(f"\n### {iso_now()} 체결\n"
                   f"- {pick['slug']} ({pick['outcome']}) 체결가 {fill_price} "
                   f"(스냅샷 {pick['snapshot_price']}, drag {drag}), "
                   f"주문 {res.get('order_id')}\n")
            snap = snapshot(client, state)
            if snap["slots"] < 1:
                break
    state["updated_at"] = iso_now()
    save_json(STATE_PATH, state)
    snap_out = {k: snap[k] for k in ("cash", "positions_count",
                                     "positions_value", "equity", "halted",
                                     "blocked", "settled_count", "wins",
                                     "drag_avg")}
    print(json.dumps({"mode": "cycle", "events": events,
                      "gate": gate_reason, "bought": bought,
                      "new_settled": new_settled, "snapshot": snap_out},
                     ensure_ascii=False))
    return 0


def mode_redeem(client, state, args, transport=None, cash_fn=None):
    """--redeem 모드. --dry-run이면 계획·의도된 호출만 출력한다 (제출·저장 없음).

    실행 모드는 실주문 3중 게이트(live_gate_open)를 그대로 탄다. 정산 감지를
    먼저 갱신하되, dry-run·게이트 미통과·전송 준비 실패 시에는 state 파일을
    쓰지 않는다 (감지는 다음 실행에서 다시 일어난다).
    """
    detect_note = None
    try:
        snap = snapshot(client, state)
        detect_settlements(state, snap)
    except Exception as e:  # noqa: BLE001 - 조회 실패 시 기존 pending으로 진행
        detect_note = f"정산 감지 갱신 실패(기존 pending 기준 진행): {str(e)[:150]}"
    plan = plan_redeems(state)
    base = {"targets": len(plan["targets"]),
            "worthless": len(plan["worthless"]),
            "plan_errors": plan["errors"]}
    if detect_note:
        base["detect_note"] = detect_note
    lines = [f"\n### {iso_now()} redeem{' (dry-run)' if args.dry_run else ''}"]

    if args.dry_run:
        collateral = sdk_collateral_token()
        calls = []
        for t in plan["targets"]:
            call = None
            if collateral:
                try:
                    call = build_redeem_call(t["condition_id"], collateral)
                except ValueError:
                    call = None
            calls.append({
                "condition_id": t["condition_id"],
                "slugs": [e.get("slug") for e in t["wins"] + t["losses"]],
                "expected_usd": t["expected_usd"], "call": call})
            lines.append(f"- 대상 {t['condition_id'][:18]}… 기대 회수 "
                         f"${t['expected_usd']} (승 {len(t['wins'])}건·"
                         f"패 {len(t['losses'])}건)")
        for e in plan["worthless"]:
            lines.append(f"- 가치 0 종결(호출 없음): {e.get('slug')}")
        gate_ok, gate_reason = live_gate_open(args)
        lines.append(f"- 게이트: {'통과' if gate_ok else '미통과 — ' + gate_reason}"
                     " (dry-run이라 제출하지 않음)")
        log_md("\n".join(lines) + "\n")
        print(json.dumps({"mode": "redeem-dry-run", **base, "calls": calls,
                          "worthless_slugs": [e.get("slug")
                                               for e in plan["worthless"]],
                          "gate": gate_reason}, ensure_ascii=False))
        return 0

    gate_ok, gate_reason = live_gate_open(args)
    if not gate_ok:
        print(json.dumps({"mode": "redeem",
                          "error": f"게이트 미통과: {gate_reason}", **base},
                         ensure_ascii=False))
        return 1
    if transport is None:
        transport = SdkRelayerRedeemTransport()
    prepare = getattr(transport, "prepare", None)
    if callable(prepare):
        try:
            prepare()
        except Exception as e:  # noqa: BLE001 - 구성 오류는 제출 0건으로 중단
            log_md(f"\n### {iso_now()} redeem 중단 (전송 계층 준비 실패)\n"
                   f"- {str(e)[:300]}\n")
            print(json.dumps({"mode": "redeem", "error": str(e)[:300], **base},
                             ensure_ascii=False))
            return 1
    if cash_fn is None:
        cash_fn = lambda: cash_balance(client)  # noqa: E731
    results = execute_redeem_plan(state, plan, transport, cash_fn)
    for r in results:
        if r["ok"]:
            lines.append(f"- {r['condition_id'][:18]}… 회수 "
                         f"${r['recovered_usd']} tx {r['tx_hash']} "
                         f"검증 {r['verify_status']}")
        else:
            lines.append(f"- {r['condition_id'][:18]}… 실패 (pending 유지): "
                         f"{r.get('error')}")
    log_md("\n".join(lines) + "\n")
    state["updated_at"] = iso_now()
    save_json(STATE_PATH, state)
    print(json.dumps({"mode": "redeem", **base, "results": results,
                      "processed": sum(1 for r in results if r["ok"])},
                     ensure_ascii=False))
    return 0


def main():
    import argparse

    ap = argparse.ArgumentParser(description="boringmech-micro-live executor")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--status", action="store_true")
    mode.add_argument("--cycle", action="store_true")
    mode.add_argument("--redeem", action="store_true")
    ap.add_argument("--dry-run", action="store_true",
                    help="제출 없이 계획만: 단독이면 매수 dry-run, "
                         "--redeem과 함께면 redeem 계획")
    ap.add_argument("--live", action="store_true",
                    help="실주문 게이트 1/3 (env·파일 게이트도 필요)")
    ap.add_argument("--seed-first-only", action="store_true",
                    help="사이클당 매수 1건 강제 (시드 단계)")
    ap.add_argument("--max-buys", type=int, default=1)
    args = ap.parse_args()
    if not (args.status or args.cycle or args.redeem or args.dry_run):
        ap.error("모드를 지정하라: --status | --dry-run | --cycle | --redeem")
    if args.dry_run and (args.status or args.cycle):
        ap.error("--dry-run은 단독 또는 --redeem과만 함께 쓴다")

    state = load_json(STATE_PATH, {})
    if not state:
        print(json.dumps({"error": "state.json 없음 — 초기화 필요"}))
        return 1
    client = make_client()
    if args.redeem:
        return mode_redeem(client, state, args)
    if args.dry_run:
        return mode_dry_run(client, state)
    if args.status:
        return mode_status(client, state)
    return mode_cycle(client, state, args)


if __name__ == "__main__":
    raise SystemExit(main())
