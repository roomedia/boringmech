# 정산 손익 차이 분석 — 앱 실현 -$11.10 vs API -$12.13

- 일자: 2026-11-24
- 대상: 청산 완료 8건 (data-api v2 closed positions 기준), 지갑 0x6e91a46ff73f36d4f3a00ab929e16b62eb014fbb
- 결론: **앱 내부 기록 계산이 실제로 틀렸다 (결함)**. 정정 방식은 동호 지시대로 내부 계산 수리가 아니라 **표시를 data-api v2 소스로 교체**로 마감했다. 출시는 하지 않았다.

## 1. 진실값 재구성

- data-api v2 activity 전수 (수수료 필드 없음, usdc_size가 수수료 포함 실제 이동액)를 8개 condition별로 합산한 현금흐름 = **-$12.1368**.
- API closed positions의 realized_pnl 합계 **-$12.13**와 건별 0.0032 이내로 일치한다. **API realized_pnl이 진실값**이다.
- 앱 표시값 -$11.10은 제보 id 40·41 payload의 snap.settledRealizedPnl과 일치한다 (제보의 bought는 slug 목록뿐이라 손익 출처는 snap 필드).

## 2. 건별 대조표

| 건 | 현금흐름 진실값 | API realized_pnl | 앱 기록 실현 | 차이 (앱 − 진실) | 비고 |
|---|---|---|---|---|---|
| BTC dip 67.5k Yes | +0.70 | +0.7016 | +0.7016 | 0 | API 행 직독, 정확 |
| BTC dip 65k Yes | +2.14 | +2.1378 | +2.1378 | 0 | API 행 직독, 정확 |
| BTC dip 62.5k No | −4.96 | −4.9612 | −4.4334 | +0.5278 | 손절 기록 우선 적용, 총액 계산 |
| Gen.G Worlds Yes | −5.09 | −5.0924 | −5.0924 | 0 | API 행 직독, 정확 |
| Bilibili Worlds No | −5.10 | −5.0952 | −5.0952 | 0 | API 행 직독, 정확 |
| BTC reach 72.5k No | +3.71 | +3.7108 | +4.0600 | +0.3492 | 수동 매도 기록 우선 적용, 총액 계산 |
| BTC dip 60k No | −3.54 | −3.5368 | −3.3737 | +0.1631 | 손절 기록 우선 적용, 총액 계산 (부분 매도 후 잔량 리딤 0) |
| 헬싱키 기온 0°C No | +0.005 | +0.0048 | 0.00 | −0.0048 | 앱에서 사실상 본전 처리 |
| **합계** | **−12.1368** | **−12.13** | **−11.10** | **+1.0383** | |

## 3. 차이의 규칙

- 차이 +$1.0383 전량이 **내부 기록이 CLOSED 행보다 먼저 들어가 dedupe 우선권을 갖는 구조**에서 나왔다. 기록 3건 (손절 2건 + 수동 매도 1건)의 realized_pnl은 전부 **수수료 미반영 총액 계산** (기록 실현 = 회수 총액 − 진입 총액)이라 테이커 수수료만큼 부풀려졌다.
- 62.5k 건은 2회 매수 후 1회 매도, 60k 건은 부분 매도 후 잔량 리딤 0인 건인데, 두 건 다 부분 매도 처리가 아니라 기록값 자체가 총액 계산인 것이 원인이다.
- 기록이 없는 4건은 CLOSED 행의 realized_pnl을 그대로 써서 정확했다. 즉 행 직독 경로는 맞고, 기록 우선 경로만 틀렸다.

## 4. 판정

- 앱 내부 기록 계산이 진실값과 다르게 계산한 **결함**으로 확정한다. API 쪽 표기 차이·수수료 처리 방식 차이가 아니다 (현금흐름 재구성이 API 값과 일치).
- 단, 정정 방향은 동호 지시 (2026-11-24)로 변경됐다: 내부 계산을 표시용으로 수리하지 않고, **표시 소스 자체를 API로 교체**한다. 내부 기록은 매매 판단 용도 (기매수 추적, stopLossSlugs 재매수 차단)로만 남는다.

## 5. data-api 버전 실태

- 앱은 이미 `DATA_API_V2 = "https://data-api.polymarket.com/v2"`를 쓰고 있었다 (positions·value·activity 전부 v2). v1 사용처는 없다. 신규 표시도 v2 snake_case 필드 기준이다.

## 6. 정정 내용 (코드 + 테스트까지, 출시 없음)

### 표시 경로 교체
- `summarizeBook` 서명을 (allRows, closedRows, stopLossRecords)로 변경 (app/src/engine.js + 네이티브 PositionBook.java 패리티 유지).
  - 정산 건수·승패·실현손익은 CLOSED 행의 realized_pnl 직독만으로 만든다. 승패는 realized_pnl 부호, 0이면 건수만. dedupe는 positionKey, 먼저 온 행 우선.
  - realized_pnl이 숫자가 아닌 행은 settledRealizedExcluded로 세고 합산에서 제외한다. 합산 가능 행이 0건인데 정산 건이 있으면 실현은 null (0으로 지어내지 않음).
  - 손절 건수는 CLOSED 행 토큰 ∩ 손절 기록 토큰의 교집합으로만 센다. 기록의 금액·승패는 표시로 가져오지 않는다.
  - 수동 매도 기록, 시도 토큰, REDEEMABLE/LOST 행은 표시 경로에서 완전히 제거했다. judgeClosedRow·realizedOf는 양쪽에서 삭제했다.
  - 보유 등락 (positionsPnlPct)은 행의 total_pnl 합산으로 바꾸고, total_pnl 없는 행만 현재가치 − 수량×평균가로 폴백한다 (재료도 전부 API 필드). 분모는 손익을 뺀 원가 합.
- 조회 분리 + 폴백: snapshot() (JS)과 NativeCycle (네이티브) 모두 REDEEMABLE/LOST 조회 (상환 로직용)와 CLOSED 조회 (표시용)를 별도 try로 분리했다. CLOSED 조회 실패 사이클에서는 **마지막으로 성공한 API 표시값을 유지**하고 (state.lastSettledApi / 네이티브 hasLastSettledApi 필드군), 성공 이력이 없으면 null로 둬 UI가 '—'로 표기한다. 내부 계산값으로 대체하지 않는다.
- 정산 알림 기준선 (settledNoticeFor)은 무변경 — 새 표시값으로 자동 재기준화된다 (구 기준선 {8건, −11.10} → 신규 {8건, −12.13}, 건수 증가가 없어 오알림 없음).
- ShadowSnapshot (섀도우 대조)도 CLOSED만 조회해 새 서명으로 호출하게 단순화했다.

### 가드 테스트
- JS book.test 23b절 신설: 실측 8건 픽스처 — 기록에 틀린 실현값 (−4.43·−3.38)을 넣어도 표시가 [8건, 4승, 4패, −12.13, 손절 2건]이 되는지, CLOSED가 null이면 기록이 있어도 정산 표시가 전부 null인지 고정.
- 기존 settled 관련 테스트 (1·1b·2~9·17·23·37·41·감사 절)는 새 의미로 재작성, judgeClosedRow 테스트는 삭제.
- 네이티브 골든2: fixtures 13케이스를 새 입력 형태 (allRows·closedRows·stopLossRecords)로 재구성하고 실측 8건 케이스 (live-settled-20261124)를 추가, expected.json 재생성. Golden2Test 호출부도 새 서명으로 교체.
- NativeCycleTest: 수동 매도 집계 테스트를 표시 독립 가드로 교체하고, 정산 표시 폴백 테스트 (성공 → 실패 사이클 값 유지, 이력 없으면 null) 신설.

### 부수 발견
- native-golden2/gen_expected.mjs가 매매 설정화 (DEFAULT_TRADE_SETTINGS 도입) 이후 재생성이 깨져 있었다 — sizing 기본 인자가 참조하는 상수 추출이 없어 ReferenceError. 이번에 DEFAULT_TRADE_SETTINGS 블록과 참조 상수 추출을 보강해 재생성을 복구했다.

## 7. 검증 결과

- JS 전 슈트: book 465, temp 183, bundle 10, html-guard 9, sellquote 30, report 44 — 전부 통과.
- JVM: 골든1 48/48, 골든2 185 checks, 골든3 178 checks, 골든4 215 checks, NativeCycleTest 318, TempNo 150, submit guard 21, clobwrite 33/33, watchdog 35 + StageDeadline 19, RestartGuard 7, ExternalWatchdog 15 — 전부 통과.
- 번들 재생성 (app/engine.js) 후 npm test 재통과. 전체 APK 빌드 (v0.3.44, 버전 범프 없음) + DEX 게이트 + 축소 스모크 통과. 출시는 하지 않았다.
