# boringmech

**Polymarket에서 우세 쪽 가격이 0.60–0.90 밴드에 들면 판단 없이 기계적으로 $100씩 사서 만기까지 보유하는 전략.** 판단 단계를 일부러 뺀 대조군 실험에서, 판단 기반 전략이 기각한 매수들이 오히려 돈을 벌었다.

- 전략 명세: [STRATEGY.md](STRATEGY.md)
- 실측 결과: [RESULTS.md](RESULTS.md)
- 1차 평가 상세: [docs/boringmech-eval-20261110.md](docs/boringmech-eval-20261110.md)

## 핵심 발견 (포워드 페이퍼 실측, 2026-10-30 개시)

- 가상 $10,000 → **$10,488.04** (2026-11-11 스냅샷)
- 정산 35건 중 **29승**, 정산분 **ROI +14.1%**, 평균 진입가 71.8¢ vs 실제 승률 82.9% (캘리브레이션 괴리 +11.0pp)
- 진입가 구간별: 0.60–0.70만 손실 (−8.9%), 0.70–0.80 +20.4%, 0.80–0.90 +25.1%
- 판단 기반 자매 전략이 "근거 부족"으로 기각한 32건이 +$400.91을 벌었다 — **검증 단계가 1차 평가에서 돈을 아끼지 못했다**

⚠️ 먼저 정산되는 마켓 위주 표본이라 낙관 편향 가능. **최종 평가는 정산 50건 도달 또는 2026-11-23.**

## 빠른 시작 (페이퍼 모드)

요구사항: Python 3.11+, `pm-trader` CLI (polymarket-paper-trader 0.4.13으로 운용), 백테스트용 `requests`.

```bash
git clone https://github.com/roomedia/boringmech.git
cd boringmech

# 1) 계좌 초기화 (가상 $10,000)
./pm-trader.sh --account boringmech init --balance 10000

# 2) 후보 스캔 (상태 파일을 건드리지 않는 드라이런)
python3 scan_boring.py --dry-run
# → {"candidates": [{slug, question, side, price, fee_rate, volume, end_date, ...}], ...}

# 3) 후보 매수 — 밴드 안이면 기계적으로, 건당 $100
./pm-trader.sh --account boringmech buy <slug> <yes|no> 100

# 4) 잔고·포지션 확인, 만기 도래분 정산
./pm-trader.sh --account boringmech balance
./pm-trader.sh --account boringmech portfolio
./pm-trader.sh --account boringmech resolve --all
```

래퍼 `pm-trader.sh`는 `pm-trader` 바이너리와 데이터 디렉토리를 환경변수로 받는다 (`PM_TRADER_BIN`, `PM_TRADER_DATA_DIR` — 기본값은 PATH와 레포 루트). 스캐너는 Gamma API를 직접 호출하므로 별도 인증이 필요 없다.

운용 사이클은 "스캔 → 밴드 확인 → 매수 → (다음 사이클에) 정산"의 반복이다. 생산 환경에서는 15분 주기로 돌렸다.

## 백테스트

```bash
pip install requests
python3 backtest_boring.py        # 기계적 코어 재현 → bt_results.json
python3 backtest_band_sweep.py    # 밴드 스윕 → bt_sweep_results.json (bt_stats.py 공용)
```

백테스트 판정은 정직하게 적어 둔다: 어느 밴드 셀도 통계적으로 유의한 엣지는 없었다 (최고 p=0.058). 포워드 페이퍼의 양의 성적과 백테스트의 불유의가 공존하는 상태이며, 최종 평가는 포워드 정산 표본으로 한다.

## 베뉴 상태 — 엣지는 Polymarket 가격 구조에 한정

- **Polymarket 실전: 종결 (2026-11-11).** 소액 실전(`live/`) 첫 주문이 CLOB에서 403 "Trading restricted in your region"으로 거부됐다. 운용 환경의 출구 IP가 제한 지역이고, 한국도 close-only 제한 지역이라 정당한 실전 경로가 없다. 우회(VPN·해외 리전 등)는 약관 위반이라 하지 않는다. `live/` 코드는 차단 상태 그대로 보존만 한다 — 해제 파일(`LIVE_ENABLED`)은 레포에 없고, 만들어도 게이트의 나머지 조건 없이는 동작하지 않는다.
- **Kalshi 이식: 백테스트 FAIL.** 같은 규칙을 Kalshi에 이식한 백테스트(경로 의존 진입, 90일)는 스포츠·비스포츠 모두 게이트(net EV > 0, PF > 1.2) 미달이었고, 표본을 키운 재실행에서도 유지됐다. Kalshi에서는 우세 쪽이 실제 확률보다 비싸게 매겨지는 경향이 있어, **이 전략의 성적은 Polymarket의 가격 형성 구조에 붙어 있는 것으로 보인다.** (Kalshi 관찰 페이퍼는 별도 트랙으로 운용 중이며 이 레포 범위 밖이다.)

## 파일 구성

| 경로 | 내용 |
|---|---|
| `STRATEGY.md` | 고정 규칙·후보 필터·밴드 근거·이력 정정 |
| `RESULTS.md` | 포워드 실측 + 백테스트 요약 |
| `scan_boring.py` | 후보 스캐너 (풀 1,000, 판단·매수 안 함) |
| `backtest_boring.py` / `backtest_band_sweep.py` / `bt_stats.py` | 백테스트·밴드 스윕·공용 통계 |
| `bt_results.json` / `bt_sweep_results.json` | 백테스트 원시 결과 |
| `pm-trader.sh` | 페이퍼 실행 래퍼 |
| `live/` | 실전 마이크로 라이브 명세·실행기 (종결·차단 상태 보존, 지갑 주소·자격증명은 환경변수 주입으로 제거) |
| `docs/` | 백테스트 보고서 2건 + 1차 평가서 |

## 주의

- 페이퍼 트레이딩 결과다. 실제 돈의 수익을 보장하지 않으며, 슬리피지·유동성 제약은 페이퍼에서 재현되지 않는다.
- 계좌 DB(`*.db`), 상태 파일, 시크릿은 `.gitignore`로 전부 제외한다.
