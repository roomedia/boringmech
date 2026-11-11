# boringmech-micro-live — 실전 마이크로 라이브 명세 (정본)

페이퍼 전략 boringmech와 **같은 후보 필터**를 실시간으로 실행하는 소액 실전 운용.
목적은 수익이 아니라 **실체결·슬리피지(Drag) 실측**이다. 라벨: `boringmech-micro-live`.

## 계정

- signer EOA: 환경변수 `PM_EOA_ADDRESS`로 주입 (주소는 레포에 기록하지 않음)
- deposit wallet (funder): 환경변수 `PM_DEPOSIT_WALLET`로 주입, signature_type=3 (POLY_1271)
- 개인키 `~/.config/polymarket/wallet.key`, API 자격증명 `~/.config/polymarket/creds.json` — 값은 코드·로그·문서 어디에도 쓰지 않고 경로로만 읽는다.
- 시작 자본: $57.87 (입금 확인분). 추가 입금 없음이 전제다.
- SDK: `py-clob-client-v2` (`~/workspace/.venv`). 프록시 주의 (2026-11-11 실측): httpx가 `no_proxy`의 IPv6 표기를 파싱하다 죽으므로 executor는 import 전에 `no_proxy`/`NO_PROXY`만 제거한다. 프록시 자체는 유지해야 한다 — 전부 제거하면 VM 직접 출구가 TLS를 가로채 SSL 오류가 난다. Gamma/Data API는 원래 프록시를 명시적으로 지정해 호출한다.

## 고정 규칙 (사전 확정 — 변경은 사용자 결정으로만)

| 항목 | 값 |
|---|---|
| 건당 베팅 | $5 고정 |
| 가스 예비비 | **$2.87 동결** (state.json `reserve_usd`). 매수에 절대 쓰지 않는다 |
| 거래 가용 현금 | CLOB 현금 − 2.87 |
| 슬롯 | 가용 현금 ÷ 5 내림 (시작: (57.87−2.87)÷5 = 11) |
| 평가금 | 현금 + 보유 포지션 평가액 (Data API v2 `/value`는 현금 제외 포지션 평가액만 반환함을 실측 확인 — 평가금 = 현금 + 그 값). **예비 포함 총액 기준** |
| 중단 (halt) | 평가금 < $35 → 신규 매수 중단 상태로 기록 |
| 평가 게이트 | 평가금 $100 도달 또는 정산 20건 도달 시 평가 이벤트 플래그 (`eval_flag`) |
| 매도 회전 | 없음. 만기 보유만 |
| 회계 정밀도 | 센트(소수 2자리) 단위. 원시 CLOB 잔고 57.866710 같은 서브센트 먼지는 반올림해 계산한다 — 그래서 가용 55.00 → 11슬롯이 유지된다. 실제 지갑에 남는 예비 실측값은 $2.8667로 명목 $2.87과 0.33¢ 차이가 있다 (브리지 변환 먼지, 2026-11-11 실측) |
| 이벤트 상관 캡 | 없음 (확정). 단 이벤트별 보유 개수는 로그에 기록한다 |

## 후보 공급·선정

- 후보 풀은 `scan_boring.py`와 동일 필터: 유동성 상위 1,000 → 활성·미종료 → 크립토 단타(up-or-down) 제외 → 만기 내일~+45일 → 거래량 ≥ 10,000 → 스포츠 경기 마켓 제외(Gamma sportsMarketType) → 우세 쪽 판정.
- 단, 스캐너의 seen/재표면화 게이트(마지막 판단가에서 5¢ 이상 움직여야 재표면화)는 **라이브에 적용하지 않는다** — 그 게이트는 페이퍼 판단 주체의 재판단 방지용이고, 라이브 충원은 현재 풀 전체 재평가가 확정 규칙이다. seen 게이트를 실제로 태운 실측(2026-11-11 12:31)에서는 후보 0건이 나와 슬롯이 상시 비는 구조였음이 확인됐다.
- 라이브 밴드는 **현재 매도호가 0.70–0.90** (페이퍼 0.60–0.90과 다름 — 확정 규칙).
- 충원: 슬롯이 비어 있으면 현재 후보 전수를 재평가하고, 재검증 통과분 중 **무작위 1개**를 매수한다 (사이클당 최대 1건이 기본, 시드는 `--seed-first-only` 단계 실행).
- 재검증 (실행 직전 CLOB 오더북 기준, 하나라도 실패하면 탈락·로그):
  1. 베스트 ask가 0.70–0.90
  2. 스프레드(ask − bid) ≤ $0.02 (bid 없으면 탈락)
  3. 베스트 ask 잔량의 달러 가치(수량 × 가격) ≥ $10
  4. 라이브에서 한 번이라도 산 적 있는 slug 제외 (`ever_bought`)
  5. 현재 보유 중 slug 제외
  6. 최소 주문 수량(min_order_size) 충족

## 주문 형태·기록

- 지정가 **FOK**, 가격 = min(베스트 ask + 0.015, 0.90), 시장 tick에 맞춰 반올림. $5 어치 수량(소수 2자리 내림).
- **Drag** = 체결 평균가 − 선정 시점 스캐너 스냅샷가. 체결 건마다 fills에 기록하고 평균을 대시보드에 올린다.
- 미체결(FOK 실패)·스킵(슬롯 없음·halt·blocked)·재검증 탈락(사유 포함)도 전부 run-log.md에 남긴다.
- fail-safe: 주문 응답이 403(지역 차단)·서명 거부·maker 관련 거부를 포함하면 즉시 `blocked=true`로 영구 플래그하고 이후 실행에서 주문을 시도하지 않는다. 해제는 수동으로만.

## 상태 스키마 (state.json)

| 필드 | 의미 |
|---|---|
| version, label | 1, "boringmech-micro-live" |
| capital_usd | 시작 자본 57.87 |
| reserve_usd | 가스 예비비 2.87 (동결) |
| ever_bought | 한 번이라도 체결된 slug 배열 |
| fills | 체결 기록 {ts, slug, event_id, event_slug, side, outcome, token_id, condition_id, snapshot_price, limit_price, fill_price, shares, usd, drag, order_id} |
| settled | 정산 기록 {condition_id, token_id, slug, won, payout_value, detected_at} |
| settled_count, wins | 정산 누적·승 수 |
| halted | 마지막 계산된 평가금 중단 상태 |
| blocked, block_reason | fail-safe 영구 차단 |
| eval_flag | null | "equity_100" | "settled_20" |
| redeemable_pending | redeem 대기 목록 {condition_id, slug, outcome, status, current_value} |
| created_at, updated_at | ISO 8601 (KST) |

## 정산 감지·redeem

- Data API v2 `/positions`의 status가 `REDEEMABLE`(승)·`REDEEMABLE_LOST`(패)로 바뀐 포지션을 정산으로 감지해 settled에 기록하고, redeemable_pending에 목록화한다. **현재 단계는 감지·목록화까지** — redeem 실행 코드는 짜되 실행하지 않는다.
- 주의: Data API v1은 폐기됐다 (2026-11-11 실측). v2 경로는 `https://data-api.polymarket.com/v2/value|positions`이며 응답 envelope는 `{"data": ...}`다.

### redeem 경로 조사 결과 (2026-11-11)

- **py-clob-client-v2에는 redeem·relayer 기능이 없다** (패키지 전수 grep으로 확인). 주문 전용이다.
- **통합 SDK `polymarket-client` 0.18.0에 경로가 있다**: `SecureClient.redeem_positions()`가 내부에서 `ctf_redeem_positions_call` — 즉 ConditionalTokens `redeemPositions(collateral=pUSD, parentCollectionId=0x0, conditionId, indexSets=[1,2])` — 를 만들어 deposit wallet 배치로 서명하고 `https://relayer-v2.polymarket.com`에 **gasless** 제출한다. EOA는 서명만 하고 가스는 relayer가 낸다.
  - 선행 조건: relayer 인증 키 — Builder API key(`POLY_BUILDER_*` HMAC) 또는 Relayer API key가 필요하다. Builder 키는 polymarket.com 설정 또는 CLOB 클라이언트의 `create_builder_api_key`로 발급 가능 (미실행 — 키 발급은 본체 단계).
- 폴백 경로: 같은 DW 배치를 EOA가 직접 `execute()`로 제출하면 가스(POL)를 직접 낸다. **EOA POL 잔고는 0** (2026-11-11 Polygonscan 확인)이라 현재는 이 경로가 막혀 있다.

### 가스 예비 운용 규칙 (사용자 확정)

이익이 날 때마다 쪼개서 옮기지 않는다.

1. 예비 $2.87은 **동결 유지** — 평소에는 건드리지 않는다.
2. POL 변환·EOA 이동은 **첫 redeem이 필요해지는 시점에 한 번만** 수행한다.
3. 이후에는 EOA POL 잔고가 **$0.50 상당 밑으로 떨어질 때만 $2 단위**로 추가 이동한다.
4. executor `--status`는 EOA POL 잔고(읽을 수 있으면)와 low-water 경고를 출력한다.
5. **relayer 경로가 성립하면 가스는 0이라 이 규칙 전체가 dormant**가 된다 — 그 경우 예비비는 동결된 채로 남고, 변환·이동은 영원히 실행되지 않을 수 있다 (그것으로 정상).
6. 예비 pUSD → POL 변환 방법 (조사): DW 배치를 gasless로 제출할 수 있으면 그 배치 안에서 DEX 스왑(pUSD→USDC→POL 후 EOA 전송)까지 한 번에 처리하는 것이 이론상 가능하다 (미구축·미검증 — 추측). 다만 $2.87 규모에서는 변환 수수료·슬리피지 비중이 크고, 자체 제출 폴백이 실제로 필요해지면 사용자가 거래소에서 POL을 EOA로 직접 보내는 쪽이 단순·확실하다. **결론: 변환 경로는 만들지 않고, 필요 시점에 직접 POL 입금으로 대체하는 것을 기본안으로 둔다.**

## 대시보드

- 키 `boringmechlive` (포맷 정본: `dashboard-state-format.md`). 실전 소액이라 모의 합산(41,000)에는 넣지 않고 별도 카드로 렌더링한다.
- drag_avg는 fills의 drag 평균 (체결 없으면 null).

## 운영

- 훅 초안 `~/hooks/scripts/boringmech-live.sh.draft` (15분 주기). 등록·활성화는 본체 단계에서 한다.
- 실주문 게이트 3중: `--live` 플래그 + 환경변수 `BORINGMECH_LIVE_CONFIRM=YES` + 전략 폴더의 `LIVE_ENABLED` 파일. 셋 다 없으면 주문 경로는 거부된다.
- 첫 실주문은 시드 1건만 단독 실행(`--seed-first-only`)해 성공을 확인한 뒤 나머지를 채운다. 첫 주문 실행 자체는 본체가 별도로 한다.
