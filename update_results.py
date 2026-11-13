#!/usr/bin/env python3
"""RESULTS.md 자동 갱신 — boringmech 포워드 페이퍼 실측을 DB에서 재생성한다.

데이터: $PM_TRADER_DATA_DIR/boringmech/paper.db (환경변수 없으면 운용 기본 경로).
지표 정의 (2026-11-10 1차 평가서와 동일하게 재현 검증됨):
  - 정산 성적: positions.is_resolved=1 기준. 실현 PnL·원가는 positions 값.
  - 평균 진입가: 정산 포지션의 매수 trades.avg_price 단순평균
    (positions.avg_entry_price는 일부 깨진 값이 있어 쓰지 않는다).
  - 캘리브레이션 괴리: 실제 승률 − 평균 진입가 (pp).
  - 평균 매수 수수료: 전체 매수 trades의 fee/amount_usd 건별 평균 (%).
  - 진입가 구간: 포지션별 매수 금액가중 평균가로 분류. 밴드(0.60–0.90) 밖은 별도 행.
스냅샷 총자산: 현금 + 미정산 포지션을 Gamma outcomePrices로 전량 재가격한 합.
  pm-trader balance는 가격 조회 실패 포지션을 무음으로 $0 처리하는 결함이 있어
  (2026-11-10 대조 문서) 쓰지 않는다. Gamma로 가격을 못 구한 포지션이 하나라도
  있으면 파일을 쓰지 않고 종료한다 — 틀린 스냅샷을 남기느니 갱신을 건너뛴다.
갱신 조건: 정산 건수·승패·실현 PnL·미정산 건수·대조군 헤드라인 중 하나라도
  바뀔 때만 파일을 다시 쓴다 (가격 틱만으로는 원장을 흔들지 않는다).
"""
import json
import os
import re
import sqlite3
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

REPO_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DATA_DIR = os.path.expanduser(
    "~/workspace/goals/goal/hidden_files/pm-trader")
GAMMA = "https://gamma-api.polymarket.com/markets"
KST = timezone(timedelta(hours=9))


def load_db(data_dir, account):
    path = os.path.join(data_dir, account, "paper.db")
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    return db


def gamma_prices(condition_ids):
    """condition_id -> {outcome_lower: price}. 못 구한 마켓은 결과에서 빠진다."""
    out = {}
    for i in range(0, len(condition_ids), 50):
        qs = "&".join(f"condition_ids={c}" for c in condition_ids[i:i + 50])
        req = urllib.request.Request(
            f"{GAMMA}?{qs}&limit=100",
            headers={"User-Agent": "boringmech-results/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            for m in json.load(resp):
                prices = json.loads(m["outcomePrices"])
                out[m["conditionId"]] = {
                    o.lower(): float(p)
                    for o, p in zip(json.loads(m["outcomes"]), prices)}
    return out


def fmt_money(x):
    return f"${x:,.2f}"


def fmt_signed(x):
    return f"{'+' if x >= 0 else '−'}${abs(x):,.2f}"


def settled_stats(db):
    rows = db.execute(
        "SELECT * FROM positions WHERE is_resolved=1").fetchall()
    n = len(rows)
    wins = sum(1 for r in rows if r["realized_pnl"] > 0)
    pnl = sum(r["realized_pnl"] for r in rows)
    cost = sum(r["total_cost"] for r in rows)
    entry_prices, bands = [], {}
    for r in rows:
        trs = db.execute(
            "SELECT avg_price, amount_usd FROM trades "
            "WHERE market_condition_id=? AND outcome=? AND side='buy'",
            (r["market_condition_id"], r["outcome"])).fetchall()
        wavg = (sum(t["avg_price"] * t["amount_usd"] for t in trs)
                / sum(t["amount_usd"] for t in trs))
        entry_prices.append(
            sum(t["avg_price"] for t in trs) / len(trs))
        if 0.60 <= wavg < 0.70:
            key = "0.60–0.70"
        elif 0.70 <= wavg < 0.80:
            key = "0.70–0.80"
        elif 0.80 <= wavg <= 0.90:
            key = "0.80–0.90"
        else:
            key = "밴드 외"
        b = bands.setdefault(key, [0, 0.0, 0.0])
        b[0] += 1
        b[1] += r["total_cost"]
        b[2] += r["realized_pnl"]
    all_buys = db.execute(
        "SELECT fee, amount_usd FROM trades WHERE side='buy'").fetchall()
    fee_pct = (sum(t["fee"] / t["amount_usd"] for t in all_buys)
               / len(all_buys) * 100)
    avg_entry = sum(entry_prices) / len(entry_prices) * 100
    win_rate = wins / n * 100
    return {
        "n": n, "wins": wins, "losses": n - wins, "win_rate": win_rate,
        "pnl": pnl, "cost": cost, "roi": pnl / cost * 100,
        "avg_entry": avg_entry, "gap": win_rate - avg_entry,
        "fee_pct": fee_pct, "bands": bands,
    }


def snapshot(db):
    cash = db.execute("SELECT cash FROM account").fetchone()["cash"]
    open_rows = db.execute(
        "SELECT * FROM positions WHERE is_resolved=0 AND shares>0").fetchall()
    prices = gamma_prices([r["market_condition_id"] for r in open_rows])
    value, missing = 0.0, []
    for r in open_rows:
        p = prices.get(r["market_condition_id"], {}).get(
            r["outcome"].lower())
        if p is None:
            missing.append(r["market_slug"])
        else:
            value += r["shares"] * p
    if missing:
        raise RuntimeError(f"Gamma 가격 누락 {len(missing)}건: {missing[:3]}")
    total = cash + value
    return {"cash": cash, "open_n": len(open_rows), "value": value,
            "total": total, "pnl": total - 10000.0}


def boring_headline(data_dir):
    try:
        db = load_db(data_dir, "boring")
    except sqlite3.Error:
        return None
    s = db.execute(
        "SELECT COUNT(*) n, SUM(realized_pnl>0) w, SUM(realized_pnl) pnl "
        "FROM positions WHERE is_resolved=1").fetchone()
    o = db.execute(
        "SELECT COUNT(*) n FROM positions "
        "WHERE is_resolved=0 AND shares>0").fetchone()
    return (f"- boring 현재 (DB 기준): 정산 {s['n']}건 {s['w']}승, "
            f"실현 {fmt_signed(s['pnl'])}, 미정산 {o['n']}건 보유 중.")


def render_forward(st, snap, now_kst):
    lines = [
        "## 포워드 페이퍼 (본 결과)",
        "",
        "- 운용: 2026-10-30 개시, 가상 자본 $10,000, Polymarket 페이퍼 (실제 돈 아님)",
        f"- 스냅샷: {now_kst} KST 기준 **총자산 {fmt_money(snap['total'])} "
        f"({fmt_signed(snap['pnl'])})**, 미정산 {snap['open_n']}건 보유 중",
        "",
        "> 스냅샷 평가액은 pm-trader balance의 무음 $0 폴백 결함을 피해 "
        "Gamma outcomePrices로 전 포지션을 재가격한 값이다.",
        "",
        f"### 정산 성적 (자동 갱신: {now_kst} KST)",
        "",
        "| 지표 | 값 |",
        "|---|---|",
        f"| 정산 건수 | {st['n']}건 |",
        f"| 승 / 패 | {st['wins']}승 / {st['losses']}패 "
        f"(승률 {st['win_rate']:.1f}%) |",
        f"| 실현 PnL | {fmt_signed(st['pnl'])} |",
        f"| 정산 원가 | {fmt_money(st['cost'])} |",
        f"| **ROI** | **{st['roi']:+.1f}%** |",
        f"| 평균 진입가 | {st['avg_entry']:.1f}¢ (실제 승률 "
        f"{st['win_rate']:.1f}%와 캘리브레이션 괴리 {st['gap']:+.1f}pp) |",
        f"| 평균 매수 수수료 | {st['fee_pct']:.2f}% |",
        "",
        "### 진입가 구간별 (정산분)",
        "",
        "| 진입가 구간 | 건수 | 성적 |",
        "|---|---|---|",
    ]
    for key in ["0.60–0.70", "0.70–0.80", "0.80–0.90", "밴드 외"]:
        if key not in st["bands"]:
            continue
        cnt, cost, pnl = st["bands"][key]
        label = key if key != "밴드 외" else "밴드 외 (0.60 미만)"
        note = " (유일한 손실 구간)" if pnl < 0 and key != "밴드 외" else ""
        roi = f"{pnl / cost * 100:+.1f}%".replace("-", "−")
        lines.append(
            f"| {label} | {cnt}건 | ROI **{roi}** "
            f"({fmt_signed(pnl)}){note} |")
    return "\n".join(lines)


def replace_block(text, tag, new_body):
    pat = re.compile(
        rf"<!-- AUTO:{tag} -->.*?<!-- /AUTO:{tag} -->", re.DOTALL)
    if not pat.search(text):
        raise RuntimeError(f"RESULTS.md에 AUTO:{tag} 마커가 없다")
    return pat.sub(
        f"<!-- AUTO:{tag} -->\n{new_body}\n<!-- /AUTO:{tag} -->", text)


def material_signature(text):
    """가격 틱을 뺀 실질 지표만 뽑아 비교용 서명을 만든다."""
    keep = []
    for line in text.splitlines():
        if (line.startswith("| 정산 건수") or line.startswith("| 승 / 패")
                or line.startswith("| 실현 PnL")):
            keep.append(line)
        elif line.startswith("- 스냅샷"):
            m = re.search(r"미정산 (\d+)건", line)
            keep.append(f"open={m.group(1) if m else '?'}")
        elif line.startswith("- boring 현재"):
            keep.append(line)
    return "\n".join(keep)


def main():
    data_dir = os.environ.get("PM_TRADER_DATA_DIR", DEFAULT_DATA_DIR)
    db = load_db(data_dir, "boringmech")
    st = settled_stats(db)
    snap = snapshot(db)
    now_kst = datetime.now(KST).strftime("%Y-%m-%d %H:%M")

    results_path = os.path.join(REPO_DIR, "RESULTS.md")
    with open(results_path, encoding="utf-8") as f:
        old = f.read()
    new = replace_block(old, "forward", render_forward(st, snap, now_kst))
    headline = boring_headline(data_dir)
    if headline:
        new = replace_block(new, "boring", headline)
    def render_readme(ts):
        ko = (
            f"가상 $10,000 → **{fmt_money(snap['total'])}** "
            f"({ts} KST 스냅샷, 자동 갱신) · 정산 {st['n']}건 중 "
            f"**{st['wins']}승**, 정산분 **ROI {st['roi']:+.1f}%**, "
            f"평균 진입가 {st['avg_entry']:.1f}¢ vs 실제 승률 "
            f"{st['win_rate']:.1f}% (캘리브레이션 괴리 {st['gap']:+.1f}pp)")
        en = (
            f"Paper $10,000 → **{fmt_money(snap['total'])}** "
            f"(snapshot {ts} KST, auto-updated) · {st['n']} settled, "
            f"**{st['wins']} wins**, settled **ROI {st['roi']:+.1f}%** · "
            f"avg entry {st['avg_entry']:.1f}¢ vs actual win rate "
            f"{st['win_rate']:.1f}% (calibration gap {st['gap']:+.1f}pp)")
        return ko, en

    readme_path = os.path.join(REPO_DIR, "README.md")
    material_changed = material_signature(new) != material_signature(old)
    readme_updated = False
    if os.path.exists(readme_path):
        with open(readme_path, encoding="utf-8") as f:
            r_old = f.read()
        # RESULTS가 안 바뀌는 회차에는 README 통계도 RESULTS에 박힌
        # 스냅샷 시각·총자산을 그대로 써서 두 파일의 기준을 일치시킨다.
        if material_changed:
            ts, total = now_kst, snap["total"]
        else:
            m = re.search(r"스냅샷: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) KST 기준 "
                          r"\*\*총자산 \$([\d,]+\.\d{2})", old)
            ts = m.group(1) if m else now_kst
            total = float(m.group(2).replace(",", "")) if m else snap["total"]
        ko, en = render_readme(ts)
        ko = ko.replace(fmt_money(snap["total"]), fmt_money(total), 1)
        en = en.replace(fmt_money(snap["total"]), fmt_money(total), 1)
        r_new = r_old
        if "<!-- AUTO:stats -->" in r_new:
            r_new = replace_block(r_new, "stats", ko)
        if "<!-- AUTO:stats_en -->" in r_new:
            r_new = replace_block(r_new, "stats_en", en)
        if r_new != r_old:
            with open(readme_path, "w", encoding="utf-8") as f:
                f.write(r_new)
            readme_updated = True
    if not material_changed:
        print(json.dumps({"updated": False, "readme_updated": readme_updated,
                          "settled": st["n"]}))
        return 0
    with open(results_path, "w", encoding="utf-8") as f:
        f.write(new)
    print(json.dumps({
        "updated": True, "readme_updated": readme_updated,
        "settled": st["n"], "wins": st["wins"],
        "roi_pct": round(st["roi"], 1), "total": round(snap["total"], 2),
        "open": snap["open_n"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
