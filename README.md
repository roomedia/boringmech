# boringmech

**A deliberately brainless Polymarket strategy: whenever the favorite's price sits in the 0.60–0.90 band, buy $100 of it. No analysis, no discretion — hold to expiry.**

boringmech started as the *control group* for a research-driven sibling strategy (`boring`) that checked the news before every trade. The control group won. The trades the research desk rejected for "insufficient evidence" made money anyway — in the first evaluation, 32 rejected trades went 26–6 for **+$400.91**. The verification step did not pay for itself.

The measured record below is paper trading. A live executor is included under `live/`, disabled by default and gated on the operator's own region eligibility and credentials — see *Venue status*. / 아래 실측 기록은 페이퍼 트레이딩이다. 실주문 실행기(`live/`)는 기본 비활성 상태로 포함되며, 운용자 본인의 지역 자격·자격증명 게이트 하에 있다.

- Strategy spec: [STRATEGY.md](STRATEGY.md)
- Measured results (auto-updated from the live paper ledger): [RESULTS.md](RESULTS.md)
- First evaluation (2026-11-10): [docs/boringmech-eval-20261110.md](docs/boringmech-eval-20261110.md)

## The record so far (forward paper, since 2026-10-30)

<!-- AUTO:stats_en -->
Paper $10,000 → **$10,474.56** (snapshot 2026-11-14 16:09 KST, auto-updated) · 42 settled, **34 wins**, settled **ROI +13.3%** · avg entry 71.3¢ vs actual win rate 81.0% (calibration gap +9.7pp)
<!-- /AUTO:stats_en -->

- By entry band (settled): the 0.60–0.70 band was the only loser in the first evaluation (−8.9%); 0.70–0.80 returned +20.4% and 0.80–0.90 +25.1%. Live numbers in [RESULTS.md](RESULTS.md).
- The edge, if it is one, is a calibration gap: favorites in this band have won more often than their prices imply. The backtest could **not** confirm it (best cell p=0.058) — the forward paper is the arbiter, and its sample is still small.
- ⚠️ Settled markets are a biased sample: quick-resolving markets favor the favorite. Open positions may tell a different story. **Final evaluation: at 50 settled trades or 2026-11-23.**

## How it works

1. Scan active Polymarket markets (volume ≥ 10,000, expiry 1–45 days out, no sports/short-term slugs — see `STRATEGY.md`).
2. If either side is priced 0.60–0.90, buy $100 of that side. Both sides of correlated events are bought independently — no exposure limits.
3. Hold to expiry. The repo also ships `stop_loss.py` (exit if price falls 0.25 below entry; rule added 2026-11-12) — the running paper evaluation keeps the original hold-to-expiry rule until its final evaluation, and the stoploss is tracked separately.

## Quick start (paper mode)

Requirements: Python 3.11+, the `pm-trader` CLI (operated with polymarket-paper-trader 0.4.13), `requests` for backtests.

```bash
git clone https://github.com/roomedia/boringmech.git
cd boringmech

# 1) Init an account (paper $10,000)
./pm-trader.sh --account boringmech init --balance 10000

# 2) Scan candidates (dry-run — does not touch state)
python3 scan_boring.py --dry-run
# → {"candidates": [{slug, question, side, price, fee_rate, volume, end_date, ...}], ...}

# 3) Buy mechanically — $100 whenever the price is in band
./pm-trader.sh --account boringmech buy <slug> <yes|no> 100

# 4) Check balance/portfolio, settle expired markets
./pm-trader.sh --account boringmech balance
./pm-trader.sh --account boringmech portfolio
./pm-trader.sh --account boringmech resolve --all

# 5) Refresh RESULTS.md from the paper DB
PM_TRADER_DATA_DIR=<pm-trader data dir> python3 update_results.py
```

The `pm-trader.sh` wrapper takes the `pm-trader` binary and data directory from the environment (`PM_TRADER_BIN`, `PM_TRADER_DATA_DIR` — defaults: PATH and the repo root). The scanner calls the Gamma API directly; no authentication is needed. In production this cycle ran every 15 minutes.

## Backtest

```bash
pip install requests
python3 backtest_boring.py        # mechanical core replay → bt_results.json
python3 backtest_band_sweep.py    # band sweep → bt_sweep_results.json (uses bt_stats.py)
```

Stated honestly: no band cell showed a statistically significant edge (best p=0.058). A positive forward paper record and an insignificant backtest coexist here; the verdict belongs to the forward sample.

## Venue status — the edge appears tied to Polymarket's price structure

- **Operator's live trading: closed (2026-11-11).** A micro-live first order was rejected by the CLOB with 403 "Trading restricted in your region". The original operator's environment is in a restricted region, and Korea is close-only as well — there is no legitimate live path from there, and circumvention (VPN, foreign regions) would violate the venue's terms, so it is not done. The `live/` executor stays in the repo for operators whose own jurisdiction permits it — for US residents, the US-regulated venue is the reference path. It ships disabled, takes all credentials from the environment, and eligibility is each operator's responsibility under the venue's terms.
- **Kalshi port: backtest FAIL.** The same rules backtested on Kalshi (path-dependent entries, 90 days) missed the gate (net EV > 0, PF > 1.2) in both sports and non-sports, and again on a larger rerun. On Kalshi, favorites tend to be priced *above* their true probability — the opposite tilt. This strategy's record appears to live in Polymarket's pricing structure, not in the rules themselves. (A separate Kalshi observation paper track runs elsewhere and is out of this repo's scope.)

## Files

| Path | Contents |
|---|---|
| `STRATEGY.md` | Fixed rules, candidate filters, band rationale, correction history |
| `RESULTS.md` | Forward paper record + backtest summary (auto-updated) |
| `update_results.py` | Regenerates RESULTS.md / README stats from the paper DB |
| `scan_boring.py` | Candidate scanner (pool of 1,000; never judges or buys) |
| `stop_loss.py` | Stop-loss check/execute (exit at entry −0.25; `--dry-run` to evaluate) |
| `backtest_boring.py` / `backtest_band_sweep.py` / `bt_stats.py` | Backtests, band sweep, shared stats |
| `bt_results.json` / `bt_sweep_results.json` | Raw backtest output |
| `pm-trader.sh` | Paper execution wrapper |
| `live/` | Live executor + spec (disabled by default; region/credential gated) |
| `docs/` | Two backtest reports, the first evaluation, and the US live-venue design (`live-venues.md`) |

---

## 한국어

**판단을 일부러 뺀 Polymarket 기계식 전략: 우세 쪽 가격이 0.60–0.90 밴드에 들면 $100씩 사서 만기까지 보유한다.**

boringmech는 매수 전에 뉴스를 검증하는 판단 기반 자매 전략(`boring`)의 **대조군**으로 만들었다. 그런데 대조군이 이겼다. 검증 단계가 "근거 부족"으로 기각한 매수들이 오히려 돈을 벌었다 — 1차 평가에서 기각된 32건이 26승 6패, **+$400.91**. 검증 단계는 1차 평가에서 자기 값을 못 했다.

실제 돈이 아닌 페이퍼 트레이딩 기록이다. 실주문 실행기(`live/`)는 기본 비활성 상태로 포함되며, 운용자 본인의 지역 자격·자격증명 게이트 하에 있다.

- 전략 명세: [STRATEGY.md](STRATEGY.md)
- 실측 결과 (운용 DB에서 자동 갱신): [RESULTS.md](RESULTS.md)
- 1차 평가 상세: [docs/boringmech-eval-20261110.md](docs/boringmech-eval-20261110.md)

### 핵심 발견 (포워드 페이퍼 실측, 2026-10-30 개시)

<!-- AUTO:stats -->
가상 $10,000 → **$10,474.56** (2026-11-14 16:09 KST 스냅샷, 자동 갱신) · 정산 42건 중 **34승**, 정산분 **ROI +13.3%**, 평균 진입가 71.3¢ vs 실제 승률 81.0% (캘리브레이션 괴리 +9.7pp)
<!-- /AUTO:stats -->

- 진입가 구간별 (1차 평가): 0.60–0.70만 손실 (−8.9%), 0.70–0.80 +20.4%, 0.80–0.90 +25.1%. 최신 수치는 [RESULTS.md](RESULTS.md).
- ⚠️ 먼저 정산되는 마켓 위주 표본이라 낙관 편향 가능. **최종 평가는 정산 50건 도달 또는 2026-11-23.**

### 운용 사이클

"스캔 → 밴드 확인 → 매수 → (다음 사이클에) 정산"의 반복이다. 레포에는 손절 체크(`stop_loss.py`, 진입가 −25¢ 시 전량 매도 — 2026-11-12 규칙 변경)도 들어 있으나, 가동 중 페이퍼 평가는 최종 평가까지 원규칙(만기 보유)을 유지하고 손절은 별도로 추적한다. 생산 환경에서는 15분 주기로 돌렸다.

### 베뉴 상태

- **운용자 실전: 종결 (2026-11-11).** 소액 실전 첫 주문이 CLOB에서 403 "Trading restricted in your region"으로 거부됐다. 운용 환경의 출구 IP가 제한 지역이고 한국도 close-only라 그 환경에선 정당한 실전 경로가 없으며, 우회(VPN·해외 리전 등)는 약관 위반이라 하지 않는다. `live/` 실행기는 지역 자격이 있는 운용자(미국 거주자는 미국 규제 베뉴가 기준 경로)를 위해 게이트 상태로 레포에 남아 있고, 자격 판단은 운용자 본인 책임이다.
- **Kalshi 이식: 백테스트 FAIL.** 같은 규칙을 Kalshi에 이식한 백테스트는 스포츠·비스포츠 모두 게이트(net EV > 0, PF > 1.2) 미달이었다. **이 전략의 성적은 Polymarket의 가격 형성 구조에 붙어 있는 것으로 보인다.**

## 주의

- 페이퍼 트레이딩 결과다. 실제 돈의 수익을 보장하지 않으며, 슬리피지·유동성 제약은 페이퍼에서 재현되지 않는다.
- 계좌 DB(`*.db`), 상태 파일, 시크릿은 `.gitignore`로 전부 제외한다.
