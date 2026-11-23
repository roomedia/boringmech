#!/usr/bin/env python3
"""boringmech 기온 No 페이드 페이퍼 스캐너 (관측 전용, 주문 없음).

앱 규칙 정본을 그대로 복제한다:
- TempNo.java / engine.js 기온 섹션: 도시 40개, 정확한 정수 판정
  ^-?\\d+°[CF]$, 밴드 [0.80, 0.998], 진입 창 당일 06:00 UTC ~ 이벤트
  endDate, No 가격 = Gamma outcomePrices[1].
- CandidateVerifier / verifyCandidate: Gamma 재조회 후 CLOB 오더북으로
  failsafe 판정 (스프레드 0.02, 깊이 max($10, bet), 지정가 캡
  min(roundTick(bestAsk+0.015), 0.998), 신호가 +0.05, 스냅샷 허용 0.03).

실행: 창(UTC 06:00~12:00) 안이면 1패스 스캔해 JSONL로 남기고,
창 밖이면 즉시 종료한다. 무인 실행은 훅이 15분 간격으로 이 스크립트를
부르는 형태다.

선정 단계에서 가격 사유 (밴드 밖)로 탈락한 시장은 scan_drop 레코드로
개별 기록도 남긴다 (패스당 상한 MAX_BAND_DROP_RECORDS, 초과 시
scan_drop_truncated 레코드로 생략 개수를 명시). 집계 카운트
(scan_drops)는 기존 그대로다.

사용법:
  scanner.py                 실제 시계로 1패스 (창 밖이면 즉시 종료)
  scanner.py --now 2026-11-22T07:00:00Z   시뮬레이션 시각 (드라이 패스)

종료 코드: 0 정상/창 밖, 3 공급자 차단(429/401/403), 1 그 외 오류.
"""
import argparse
import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

GAMMA_EVENTS_URL = "https://gamma-api.polymarket.com/events"
GAMMA_MARKETS_URL = "https://gamma-api.polymarket.com/markets"
CLOB_HOST = "https://clob.polymarket.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      "AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0.0.0 Safari/537.36")

# ── TempNo 상수 (정본 복제) ──
BAND_LO = 0.80
BAND_HI = 0.998
ENTRY_HOUR_UTC = 6
SLUG_PREFIX = "highest-temperature-in-"
MONTHS = ["january", "february", "march", "april", "may", "june", "july",
          "august", "september", "october", "november", "december"]
CITIES = [
    "amsterdam", "ankara", "austin", "beijing", "buenos-aires", "busan",
    "cape-town", "chengdu", "chongqing", "dallas", "guangzhou", "helsinki",
    "hong-kong", "houston", "istanbul", "jeddah", "kuala-lumpur", "london",
    "los-angeles", "madrid", "miami", "milan", "munich", "nyc", "paris",
    "qingdao", "san-francisco", "sao-paulo", "seattle", "seoul", "shanghai",
    "shenzhen", "singapore", "taipei", "tel-aviv", "tokyo", "toronto",
    "warsaw", "wellington", "wuhan",
]
EXACT_TITLE_RE = re.compile(r"^-?\d+°[CF]$")

# ── CandidateVerifier 상수 (정본 복제) ──
SPREAD_MAX = 0.02
ASK_DEPTH_MIN_USD = 10.0
SLIPPAGE_CAP = 0.015
SNAPSHOT_MATCH_TOL = 0.03
ASK_ABOVE_SIGNAL = 0.05

DEFAULT_BET = 5.0  # 앱 최소 사이징 (깊이 임계는 max($10, bet)라 $10으로 같다)
FETCH_TIMEOUT = 75  # 이 VM 출구 프록시가 느려 요청당 40초 걸린 실측이 있다
# 선정 단계 가격 탈락 개별 기록의 패스당 상한 — 실측 최대 242건/패스
# (2026-11-22)라 현 규모에서는 전수 기록되고, 비정상 폭증만 막는다.
# 초과분은 잘라낸 개수를 별도 레코드로 남긴다.
MAX_BAND_DROP_RECORDS = 500


class ProviderBlocked(Exception):
    def __init__(self, status, url):
        super().__init__(f"provider blocked: HTTP {status} {url}")
        self.status = status


def fetch_json(url):
    """curl 서브프로세스로 읽는다 — 이 VM 출구 프록시에서 urllib은
    CONNECT 지연으로 반복 타임아웃되고 curl은 성공하는 실측 때문이다
    (2026-11-22 대조: curl 200/40초, urllib 30초 타임아웃 연속).
    429/401/403은 즉시 ProviderBlocked (재시도 없음). 시간 초과·
    연결 오류·5xx만 1회 재시도한다."""
    import subprocess
    last = None
    for attempt in range(2):
        p = subprocess.run(
            ["curl", "-s", "--max-time", str(FETCH_TIMEOUT),
             "-A", UA, "-w", "\n%{http_code}", url],
            capture_output=True, text=True, timeout=FETCH_TIMEOUT + 15)
        if p.returncode == 0:
            body, _, code = p.stdout.rpartition("\n")
            code = int(code or 0)
            if code in (401, 403, 429):
                raise ProviderBlocked(code, url)
            if code >= 500:
                last = RuntimeError(f"HTTP {code} {url}")
            elif code >= 400:
                raise RuntimeError(f"HTTP {code} {url}")
            else:
                return json.loads(body)
        else:
            last = RuntimeError(f"curl exit {p.returncode} {url}")
        if attempt == 0:
            time.sleep(2)
    raise last


def parse_iso_ms(s):
    """ISO 문자열 → epoch ms. 실패하면 None (JS Date.parse NaN 대응)."""
    if not isinstance(s, str) or not s:
        return None
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.timestamp() * 1000.0


def event_slug_for(city, now_ms):
    d = datetime.fromtimestamp(now_ms / 1000.0, tz=timezone.utc)
    return f"{SLUG_PREFIX}{city}-on-{MONTHS[d.month - 1]}-{d.day}-{d.year}"


def window_open(now_ms, end_ms):
    """진입 창 — 이벤트 당일 06:00 UTC ≤ now < endDate (TempNo.windowOpen)."""
    if end_ms is None or not math.isfinite(end_ms):
        return False
    d = datetime.fromtimestamp(end_ms / 1000.0, tz=timezone.utc)
    start = datetime(d.year, d.month, d.day, ENTRY_HOUR_UTC,
                     tzinfo=timezone.utc)
    return start.timestamp() * 1000.0 <= now_ms < end_ms


def is_exact_temp_title(title):
    return isinstance(title, str) and bool(EXACT_TITLE_RE.match(title.strip()))


def fee_rate_for(m):
    """resolveTempFeeRate — feeType 매핑 1순위, 없으면 feeSchedule rate."""
    ft = m.get("feeType")
    if ft:
        f = str(ft).lower()
        if f.startswith("crypto"):
            return 0.07
        if f.startswith(("politics", "finance", "tech")):
            return 0.04
        if f.startswith("geopolitics"):
            return 0.0
        return 0.05
    rate = (m.get("feeSchedule") or {}).get("rate")
    try:
        rate = float(rate)
    except (TypeError, ValueError):
        return None
    return rate if math.isfinite(rate) and rate >= 0 else None


def parse_prices(raw):
    """outcomePrices — Gamma는 JSON 문자열, 이미 배열인 경우도 수용."""
    if isinstance(raw, list):
        vals = raw
    elif isinstance(raw, str):
        try:
            vals = json.loads(raw or "[]")
        except json.JSONDecodeError:
            return None
    else:
        return None
    out = []
    for v in vals:
        try:
            out.append(float(v))
        except (TypeError, ValueError):
            out.append(float("nan"))
    return out


def scan_candidates(events, now_ms, stats, drop_records=None):
    """scanTempCandidates 복제 — held/bought는 페이퍼라 빈 집합.

    가격 사유 탈락 (temp_band_out)은 카운트만으로는 역방향 유입
    (스냅샷은 밴드 밖, ask는 밴드 안)을 분리할 수 없어, drop_records가
    주어지면 slug·스냅샷 가격 쌍 (Gamma outcomePrices [Yes, No])을
    개별 기록으로도 남긴다 (Q17 분석 후속). 선정 단계에서는 오더북을
    조회하지 않으므로 ask는 없고, 후단 verdict와 slug로 조인해 본다.
    """
    def drop(reason):
        stats[reason] = stats.get(reason, 0) + 1

    out = []
    for ev in events:
        if not ev or not str(ev.get("slug") or "").startswith(SLUG_PREFIX):
            continue
        end_ms = parse_iso_ms(ev.get("endDate"))
        if end_ms is None:
            drop("temp_end_bad")
            continue
        if not window_open(now_ms, end_ms):
            drop("temp_window")
            continue
        for m in ev.get("markets") or []:
            if not m.get("active") or m.get("closed"):
                drop("temp_inactive")
                continue
            if m.get("acceptingOrders") is False or m.get("archived") is True:
                drop("temp_not_accepting")
                continue
            if not is_exact_temp_title(m.get("groupItemTitle")):
                drop("temp_not_exact")
                continue
            prices = parse_prices(m.get("outcomePrices"))
            if (prices is None or len(prices) < 2
                    or not math.isfinite(prices[0])
                    or not math.isfinite(prices[1])):
                drop("temp_price_bad")
                continue
            price = prices[1]  # No 가격
            if price < BAND_LO or price > BAND_HI:
                drop("temp_band_out")
                if drop_records is not None:
                    drop_records.append({
                        "slug": m.get("slug") or "",
                        "title": m.get("groupItemTitle"),
                        "reason": "temp_band_out",
                        "side": "below_lo" if price < BAND_LO else "above_hi",
                        "priceYes": prices[0],
                        "priceNo": price,
                        "bandLo": BAND_LO,
                        "bandHi": BAND_HI,
                        "endDate": m.get("endDate") or ev.get("endDate") or "",
                    })
                continue
            out.append({
                "slug": m.get("slug") or "",
                "question": m.get("question") or "",
                "title": m.get("groupItemTitle"),
                "side": "no",
                "price": price,
                "endDate": m.get("endDate") or ev.get("endDate") or "",
                "strategy": "temp_no",
                "bandLo": BAND_LO,
                "bandHi": BAND_HI,
                "feeRate": fee_rate_for(m),
            })
    out.sort(key=lambda c: (c["endDate"], -c["price"]))
    return out


def round_tick(price, tick):
    q = math.floor(price / tick + 0.5)
    return round(q * tick, 6)


def book_side(book, key):
    out = []
    raw = book.get(key)
    if raw is None:
        return out
    if not isinstance(raw, list):
        raise ValueError(f"{key}.map is not a function")
    for a in raw:
        try:
            p, s = float(a.get("price")), float(a.get("size"))
        except (TypeError, ValueError, AttributeError):
            continue
        if math.isfinite(p) and math.isfinite(s):
            out.append((p, s))
    return out


def verify_candidate(cand, bet, now_ms):
    """verifyCandidate(temp 분기) 복제 — 네트워크 예외는 verify_error.

    오더북 단계에 닿은 verdict는 전부 book 스냅샷(bestAsk·bestBid·
    spread·지정가 캡·캡 가격 누적 깊이 USD)을 실어 돌려준다 — 나중에
    스냅샷 가격 체결과 실제 오더북 체결가를 대조하기 위한 기록이다.
    재조회 outcomePrices가 확정된 뒤의 verdict는 선정 쪽 재조회 가격
    (requeryPrice)도 함께 실어준다 — snapshot_mismatch의 방향·크기를
    판정 시점 값으로 직접 보기 위한 기록이다 (Q8 조사 후속).
    """
    book_snap = {}
    requery = {}

    def reject(reason):
        r = {"ok": False, "reason": reason}
        r.update(requery)
        r.update(book_snap)
        return r

    try:
        page = fetch_json(GAMMA_MARKETS_URL + "?" +
                          urllib.parse.urlencode({"slug": cand["slug"]}))
        m = page[0] if isinstance(page, list) and page else None
        if not m:
            return reject("gamma_market_missing")
        if m.get("sportsMarketType") or str(m.get("feeType") or "").startswith("sports"):
            return reject("sports")
        if m.get("acceptingOrders") is False or m.get("archived") is True:
            return reject("market_not_accepting")
        pos_ids = m.get("positionIds")
        if m.get("version") == "v2" and isinstance(pos_ids, list) and len(pos_ids) >= 2:
            tokens = pos_ids
        else:
            try:
                tokens = json.loads(m.get("clobTokenIds") or "[]")
            except (json.JSONDecodeError, TypeError):
                return reject("gamma_parse_error")
        prices = parse_prices(m.get("outcomePrices"))
        if prices is None:
            return reject("gamma_parse_error")
        idx = 0 if cand["side"] == "yes" else 1
        if len(tokens) <= idx or len(prices) <= idx:
            return reject("gamma_shape_error")
        if not math.isfinite(prices[0]) or not math.isfinite(prices[1]):
            return reject("gamma_shape_error")
        requery["requeryPrice"] = prices[idx]
        if not m.get("active") or m.get("closed"):
            return reject("market_inactive")
        end_ms = parse_iso_ms(m.get("endDate"))
        if end_ms is None or not window_open(now_ms, end_ms):
            return reject("end_out_of_range")
        if abs(prices[idx] - cand["price"]) > SNAPSHOT_MATCH_TOL:
            return reject("snapshot_mismatch")
        if prices[idx] < prices[1 - idx]:
            return reject("side_flipped")
        token_id = str(tokens[idx])
        book = fetch_json(CLOB_HOST + "/book?" +
                          urllib.parse.urlencode({"token_id": token_id}))
        asks = sorted(book_side(book, "asks"))
        bids = sorted(book_side(book, "bids"), reverse=True)
        if not asks:
            return reject("no_asks")
        best_ask = asks[0][0]
        best_bid = bids[0][0] if bids else None
        book_snap.update({
            "bestAsk": best_ask,
            "bestBid": best_bid,
            "spread": (round(best_ask - best_bid, 6)
                       if best_bid is not None else None),
            "bestAskSize": asks[0][1],
            "limitPrice": None,
            "depthAtLimitUsd": None,
            "depthAtLimitShares": None,
        })

        def fill_limit(tick_value):
            lp = min(round_tick(best_ask + SLIPPAGE_CAP, tick_value),
                     cand["bandHi"])
            book_snap.update({
                "limitPrice": lp,
                "depthAtLimitUsd": round(
                    sum(s * p for p, s in asks if p <= lp + 1e-9), 2),
                "depthAtLimitShares": round(
                    sum(s for p, s in asks if p <= lp + 1e-9), 2),
            })
            return lp

        # 틱이 북에 있으면 판정 순서와 무관하게 지정가를 먼저 채울 수
        # 있고, 없으면 앱과 같은 지점(깊이 통과 뒤)에서 조회한다 —
        # 틱 조회 실패가 탈락 사유를 바꾸지 않게 하기 위해서다.
        tick_raw = book.get("tick_size")
        tick = float(tick_raw) if tick_raw else None
        if tick is not None:
            fill_limit(tick)
        if not bids:
            return reject("no_bids")
        if best_ask < cand["bandLo"] or best_ask > cand["bandHi"]:
            return reject("ask_out_of_band")
        if best_ask > cand["price"] + ASK_ABOVE_SIGNAL:
            return reject("ask_above_signal")
        if best_ask - best_bid > SPREAD_MAX + 1e-9:
            return reject("spread_too_wide")
        if asks[0][1] * best_ask < max(ASK_DEPTH_MIN_USD, bet):
            return reject("ask_depth_low")
        if tick is None:
            tick = float(fetch_json(CLOB_HOST + "/tick-size?" +
                                    urllib.parse.urlencode({"token_id": token_id})))
            fill_limit(tick)
        limit_price = book_snap["limitPrice"]
        est_shares = bet / limit_price
        try:
            min_size = float(book.get("min_order_size") or 0)
        except (TypeError, ValueError):
            min_size = 0.0
        if est_shares < min_size:
            return reject("below_min_order_size")
        return {"ok": True, "tokenId": token_id, "limitPrice": limit_price,
                "estShares": est_shares, "tickSize": str(tick),
                "negRisk": bool(book.get("neg_risk")), **requery, **book_snap}
    except ProviderBlocked:
        raise
    except Exception as e:  # 앱은 검증 예외를 verify_error로 센다
        return {"ok": False, "reason": "verify_error", "error": str(e)[:160]}


def in_any_window(now_ms):
    """스캐너 실행 게이트 — UTC 06:00~12:00 (이벤트 endDate는 당일 12:00 UTC)."""
    d = datetime.fromtimestamp(now_ms / 1000.0, tz=timezone.utc)
    return ENTRY_HOUR_UTC <= d.hour < 12


def spread_stats(pairs):
    """(후보가, 스프레드) 쌍 → 분포 요약. 95~99.8¢ 구간을 따로 낸다."""
    import statistics

    def dist(vals):
        if not vals:
            return None
        return {"n": len(vals), "median": round(statistics.median(vals), 4),
                "min": round(min(vals), 4), "max": round(max(vals), 4)}

    all_vals = [s for _, s in pairs]
    hi_vals = [s for p, s in pairs if 0.95 <= p <= BAND_HI]
    return {"all": dist(all_vals), "band_95_998": dist(hi_vals)}


def run_pass(now_ms, bet, out_dir, simulated):
    t0 = time.time()
    stamp = datetime.fromtimestamp(now_ms / 1000.0, tz=timezone.utc)
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    log_path = Path(out_dir) / f"scan-{stamp:%Y%m%d}.jsonl"
    log_f = open(log_path, "a", encoding="utf-8")

    def emit(rec):
        rec["pass_ts"] = datetime.now(timezone.utc).isoformat()
        rec["simulated"] = simulated
        log_f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        log_f.flush()

    try:
        slugs = [event_slug_for(c, now_ms) for c in CITIES]
        events, fetch_failures = [], 0

        def fetch_event(slug):
            # 도시별 실패는 그 도시만 건너뛴다 (앱 fetchTempEvents와 동일).
            # 공급자 차단(429/401/403)만 패스 전체를 중단한다.
            try:
                page = fetch_json(GAMMA_EVENTS_URL + "?" +
                                  urllib.parse.urlencode({"slug": slug}))
            except ProviderBlocked:
                raise
            except Exception:
                return None
            return page[0] if isinstance(page, list) and page else None

        with ThreadPoolExecutor(max_workers=8) as ex:
            for ev in ex.map(fetch_event, slugs):
                if ev is None:
                    fetch_failures += 1
                else:
                    events.append(ev)

        stats = {}
        drop_records = []
        candidates = scan_candidates(events, now_ms, stats, drop_records)
        kept_drops = drop_records[:MAX_BAND_DROP_RECORDS]
        omitted_drops = len(drop_records) - len(kept_drops)
        emit({"type": "pass_start", "now": stamp.isoformat(),
              "events_fetched": len(events),
              "event_fetch_failures": fetch_failures,
              "scan_drops": stats, "candidates": len(candidates),
              "band_drop_records": len(kept_drops),
              "band_drop_records_omitted": omitted_drops})
        for d in kept_drops:
            emit({"type": "scan_drop", **d})
        if omitted_drops:
            emit({"type": "scan_drop_truncated", "reason": "temp_band_out",
                  "omitted": omitted_drops,
                  "cap": MAX_BAND_DROP_RECORDS})
        for c in candidates:
            emit({"type": "candidate", **c})

        reject_counts = {}
        passed = 0
        spread_pairs = []
        if candidates:
            from concurrent.futures import as_completed
            with ThreadPoolExecutor(max_workers=10) as ex:
                futs = {ex.submit(verify_candidate, c, bet, now_ms): c
                        for c in candidates}
                for fut in as_completed(futs):
                    c = futs[fut]
                    v = fut.result()
                    rec = {"type": "verdict", "slug": c["slug"],
                           "price": c["price"], "ok": v["ok"]}
                    for k in ("requeryPrice", "bestAsk", "bestBid", "spread",
                              "limitPrice", "depthAtLimitUsd",
                              "depthAtLimitShares", "bestAskSize", "estShares",
                              "negRisk"):
                        if k in v:
                            rec[k] = v[k]
                    if v["ok"]:
                        passed += 1
                    else:
                        rec["reason"] = v["reason"]
                        if "error" in v:
                            rec["error"] = v["error"]
                        reject_counts[v["reason"]] = \
                            reject_counts.get(v["reason"], 0) + 1
                    if v.get("spread") is not None:
                        spread_pairs.append((c["price"], v["spread"]))
                    emit(rec)
        summary = {"type": "summary", "candidates": len(candidates),
                   "passed": passed, "failed": len(candidates) - passed,
                   "pass_rate": (passed / len(candidates)) if candidates else None,
                   "reject_counts": reject_counts,
                   "spreads": spread_stats(spread_pairs),
                   "duration_s": round(time.time() - t0, 2)}
        emit(summary)
    finally:
        log_f.close()
    print(json.dumps(summary, ensure_ascii=False))
    return summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--now", help="시뮬레이션 시각 ISO8601 (예: 2026-11-22T07:00:00Z)")
    ap.add_argument("--bet", type=float, default=DEFAULT_BET)
    ap.add_argument("--out", default=str(Path(__file__).parent / "logs"))
    ap.add_argument("--force", action="store_true",
                    help="창 밖이어도 실행 (게이트 우회, 드라이 점검용)")
    args = ap.parse_args()

    simulated = args.now is not None
    if simulated:
        ms = parse_iso_ms(args.now)
        if ms is None:
            print(f"bad --now: {args.now}", file=sys.stderr)
            return 1
        now_ms = ms
    else:
        now_ms = time.time() * 1000.0

    if not args.force and not in_any_window(now_ms):
        now_iso = datetime.fromtimestamp(now_ms / 1000.0, tz=timezone.utc).isoformat()
        print(f"outside window ({now_iso} UTC) — exit")
        return 0
    try:
        run_pass(now_ms, args.bet, args.out, simulated)
    except ProviderBlocked as e:
        print(f"PROVIDER BLOCKED HTTP {e.status} — pass aborted, no retry",
              file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
