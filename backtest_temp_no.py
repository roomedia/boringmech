"""Daily exact-temperature No-fade backtest. Spec: backtest-temp-no-SPEC.md.

Collects highest-temperature events (city x date) from Gamma, snapshots the
Yes price at 06:00 UTC from CLOB prices-history, buys No in price bands,
settles from outcomePrices. Output: bt_temp_no_results.json
"""
import json
import os
import re
import sys
from collections import defaultdict
from datetime import date, timedelta

import backtest_touch as bt

CACHE_DIR = os.path.expanduser("~/workspace/data-cache/bt-temp-no-20261119")
os.makedirs(CACHE_DIR, exist_ok=True)
bt.CACHE_DIR = CACHE_DIR

GAMMA_EVENTS = "https://gamma-api.polymarket.com/events"
GAMMA_SEARCH = "https://gamma-api.polymarket.com/public-search"
START = date(2026, 10, 6)
END = date(2026, 11, 18)
VOL_FLOOR = 100.0
MAX_PRICE_CALLS = 6500
STAKE = 100.0
BANDS = [(0.93, 0.998), (0.90, 0.998), (0.95, 0.999)]
MARKETS_CKPT = "bt_temp_no_markets.json"
PRICES_CKPT = "bt_temp_no_prices.jsonl"
FINGERPRINT = f"{START}|{END}|{VOL_FLOOR}|snap0600v1"
EXACT_RE = re.compile(r"-(\d+)([cf])$")
CITY_RE = re.compile(r"^highest-temperature-in-(.+)-on-[a-z]+-\d+-\d+$")
MONTHS = ["january", "february", "march", "april", "may", "june", "july",
          "august", "september", "october", "november", "december"]


def harvest_cities():
    d = bt.get_json(GAMMA_SEARCH, {"q": "highest temperature in",
                                   "limit_per_type": 100})
    cities = set()
    for ev in (d or {}).get("events", []):
        m = CITY_RE.match(ev.get("slug") or "")
        if m:
            cities.add(m.group(1))
    return sorted(cities)


def event_slug(city, d):
    return (f"highest-temperature-in-{city}-on-{MONTHS[d.month - 1]}"
            f"-{d.day}-{d.year}")


def collect_markets(cities, days):
    markets, events_found = [], 0
    for city in cities:
        for d in days:
            r = bt.get_json(GAMMA_EVENTS, {"slug": event_slug(city, d)})
            if not r:
                continue
            events_found += 1
            for m in r[0].get("markets", []):
                try:
                    outcomes = json.loads(m.get("outcomes") or "[]")
                    prices = [float(x) for x in
                              json.loads(m.get("outcomePrices") or "[]")]
                    toks = json.loads(m.get("clobTokenIds") or "[]")
                except (ValueError, TypeError):
                    continue
                if outcomes != ["Yes", "No"] or len(prices) != 2 \
                        or len(toks) != 2:
                    continue
                yes_won = prices[0] > 0.99
                if yes_won == (prices[1] > 0.99):
                    continue
                slug = m.get("slug") or ""
                exact = bool(EXACT_RE.search(slug))
                tail = slug.endswith("orbelow") or slug.endswith("orhigher")
                if not (exact or tail):
                    continue
                markets.append({
                    "slug": slug, "city": city, "date": d.isoformat(),
                    "exact": exact, "yes_token": toks[0],
                    "yes_won": yes_won,
                    "volume": float(m.get("volumeNum") or 0),
                    "fee_rate": (m.get("feeSchedule") or {}).get("rate") or 0.0,
                })
    return markets, events_found


def snapshot_yes_price(mk, d):
    import calendar
    day0 = calendar.timegm((d.year, d.month, d.day, 0, 0, 0))
    hist = bt.fetch_range(mk["yes_token"], day0 + 5 * 3600, day0 + 10 * 3600)
    if not hist:
        return None
    snap = day0 + 6 * 3600
    for t in sorted(hist):
        if t >= snap:
            return hist[t]
    return None


def pnl(no_price, no_won, rate):
    fee = STAKE * rate * no_price * (1 - no_price)
    gross = STAKE / no_price - STAKE if no_won else -STAKE
    return gross - fee


def agg(trades):
    n = len(trades)
    if not n:
        return {"n": 0}
    wins = [t for t in trades if t["no_won"]]
    gw = sum(t["net"] for t in wins)
    gl = -sum(t["net"] for t in trades if not t["no_won"])
    return {"n": n, "win_rate": len(wins) / n,
            "avg_no_price": sum(t["no_price"] for t in trades) / n,
            "net_sum": sum(t["net"] for t in trades),
            "per_trade": sum(t["net"] for t in trades) / n,
            "pf": (gw / gl) if gl > 0 else None}


def main():
    days = []
    d = START
    while d <= END:
        days.append(d)
        d += timedelta(days=1)
    # Checkpoint: parsed markets are cached on disk so a death during the
    # long price phase does not redo the event collection.
    cities = markets = None
    if os.path.exists(MARKETS_CKPT):
        d0 = json.load(open(MARKETS_CKPT))
        if d0.get("fingerprint") == FINGERPRINT:
            cities, markets = d0["cities"], d0["markets"]
            events_found = d0["events_found"]
    if markets is None:
        cities = harvest_cities()
        markets, events_found = collect_markets(cities, days)
        json.dump({"fingerprint": FINGERPRINT, "cities": cities,
                   "markets": markets, "events_found": events_found},
                  open(MARKETS_CKPT, "w"))
    exact = [m for m in markets if m["exact"] and m["volume"] >= VOL_FLOOR]
    tails = [m for m in markets if not m["exact"] and m["volume"] >= VOL_FLOOR]
    # price-call budget: thin days evenly if needed
    est = len(exact) + len(tails)
    thin = 1
    while est // thin > MAX_PRICE_CALLS:
        thin += 1
    if thin > 1:
        exact = [m for i, m in enumerate(exact)
                 if int(m["date"].split("-")[2]) % thin == 0]
        tails = [m for i, m in enumerate(tails)
                 if int(m["date"].split("-")[2]) % thin == 0]

    trades = {f"{lo}-{hi}": [] for lo, hi in BANDS}
    tail_trades = []
    priced = 0
    # Incremental price checkpoint: every snapshot is appended as it is
    # fetched, so a mid-run death resumes from the last priced market.
    snap_cache = {}
    fp_ok = False
    if os.path.exists(PRICES_CKPT):
        with open(PRICES_CKPT) as f:
            lines = f.readlines()
        if lines:
            try:
                head = json.loads(lines[0])
            except ValueError:
                head = {}
            if head.get("_meta") and head.get("fingerprint") == FINGERPRINT:
                fp_ok = True
                for line in lines[1:]:
                    try:
                        r = json.loads(line)
                        snap_cache[r["slug"]] = r["p"]
                    except (ValueError, KeyError):
                        continue
    ckf = open(PRICES_CKPT, "a" if fp_ok else "w")
    if not fp_ok:
        ckf.write(json.dumps({"_meta": True,
                              "fingerprint": FINGERPRINT}) + "\n")
        ckf.flush()
    for m in exact + tails:
        if m["slug"] in snap_cache:
            p = snap_cache[m["slug"]]
        else:
            y, mo, dd = (int(x) for x in m["date"].split("-"))
            p = snapshot_yes_price(m, date(y, mo, dd))
            ckf.write(json.dumps({"slug": m["slug"], "p": p}) + "\n")
            ckf.flush()
        if p is None or not (0.0 < p < 1.0):
            continue
        priced += 1
        no_price = 1.0 - p
        no_won = not m["yes_won"]
        for lo, hi in BANDS:
            if lo <= no_price <= hi:
                tr = {"slug": m["slug"], "city": m["city"], "date": m["date"],
                      "no_price": no_price, "no_won": no_won,
                      "net": pnl(no_price, no_won, m["fee_rate"])}
                if m["exact"]:
                    trades[f"{lo}-{hi}"].append(tr)
                elif (lo, hi) == BANDS[0]:
                    tail_trades.append(tr)

    primary = trades[f"{BANDS[0][0]}-{BANDS[0][1]}"]
    by_day = defaultdict(float)
    by_cluster = defaultdict(float)
    for t in primary:
        by_day[t["date"]] += t["net"]
        by_cluster[(t["city"], t["date"])] += t["net"]
    losing_days = sum(1 for v in by_day.values() if v < 0)
    a = agg(primary)
    coverage = priced / (len(exact) + len(tails)) if (exact or tails) else 0
    if coverage < 0.40:
        verdict = "판정 불가 (커버리지)"
    elif a["n"] >= 100 and a["per_trade"] > 0 and (a["pf"] or 0) > 1.2:
        verdict = "PASS"
    else:
        verdict = "FAIL"
    meta = {
        "cities": cities, "n_cities": len(cities), "days": len(days),
        "events_found": events_found, "markets_total": len(markets),
        "exact_over_floor": len(exact), "tails_over_floor": len(tails),
        "thinned_by": thin, "priced": priced, "coverage": coverage,
        "http_calls": bt.STATS["http_calls"],
        "cache_hits": bt.STATS["cache_hits"], "provider_blocked": None,
        "verdict_primary_band": verdict,
        "universe_note": "Gamma events by slug, 2026-10-06..2026-11-18, "
                         "exact-integer markets (+tail buckets separate), "
                         "volume>=$100 coverage filter (disclosed in SPEC), "
                         "snapshot = first CLOB price at/after 06:00 UTC "
                         "within 3h, stake $100, fee rate from feeSchedule",
    }
    out = {"meta": meta,
           "bands": {k: agg(v) for k, v in trades.items()},
           "tail_buckets_primary_band": agg(tail_trades),
           "day_level": {"days_traded": len(by_day),
                         "losing_days": losing_days,
                         "worst_day": min(by_day.values()) if by_day else None,
                         "best_day": max(by_day.values()) if by_day else None},
           "cluster_level": {
               "clusters": len(by_cluster),
               "losing_clusters": sum(1 for v in by_cluster.values() if v < 0),
               "worst_cluster": min(by_cluster.values()) if by_cluster else None},
           "trades_primary": primary}
    with open("bt_temp_no_results.json", "w") as f:
        json.dump(out, f, ensure_ascii=False)
    ckf.close()
    for p_ in (MARKETS_CKPT, PRICES_CKPT):
        if os.path.exists(p_):
            os.remove(p_)
    print(json.dumps(meta, ensure_ascii=False))
    print("bands:", json.dumps(out["bands"], ensure_ascii=False))
    print("verdict:", verdict)


if __name__ == "__main__":
    try:
        main()
    except bt.ProviderBlocked as e:
        print(json.dumps({"provider_blocked": str(e)}), file=sys.stderr)
        sys.exit(2)
