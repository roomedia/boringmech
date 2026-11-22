# STATUS: 기온 앱 기능 + 매매 수치 설정화

- 상태: awaiting
- 시작: 2026-11-22
- 범위: 구현·테스트·빌드 검증·커밋·푸시까지. 출시(릴리스 태그)는 하지 않는다.
- 과제:
  1. 기온 전용 손절 0.15 (메인 -0.25 유지, temp_no 태그 건만 기온 폭)
  2. 기온 슬롯 수 지정 설정 (tempNoReserveSlots, 기본 1, 0이면 예약 없음)
  3. 범위 확장 (동호 지시): 매매 판단 하드코딩 수치를 전부 bm_settings 설정으로.
     기본값은 기존 상수와 동일해 미변경 시 동작 불변. 순수 인프라 상수는 제외하고 목록만 남긴다.
- 단계:
  - [완료] 착수 대조: docs/RELEASE-AUDIT.md 감사 축 1(주문)·2(손절) 확인, JS·네이티브 손절/예약/설정 경로 전수 파악
  - [완료] 기능 1·2 구현 (JS engine.js + index.html UI + 네이티브 StopLoss/TempNo/NativeCycle + write/StopLoss 오버로드)
  - [완료] 기능 3 구현: JS parseTradeSettings + 소비 함수 배선, 네이티브 TradeSettings.java + 오버로드 배선, UI 설정 카드 4개, 골든3 하네스·픽스처 갱신
  - [완료] 가드 테스트 (기본값 = 기존 상수 대조 포함, JS book §44 · NativeCycleTest tradeSettingsParse)
  - [완료] 검증: JS 전 슈트·JVM 전 슈트·골든 1~4·번들·APK+dex 통과, 축소 스모크 통과
  - [완료] 커밋 (roomedia 단독 저자) + 푸시 — 본 작업 커밋 0d4671ca9584abab2c14a32565beb740c77743a7 (25개 파일, 무관한 backtest-temp-no-20261119.md 수정분과 밤새 큐 산출물은 분리해 제외), 이 STATUS 파일은 후속 커밋으로 기록

## UI (동호 지시: 설정 나열표처럼 보이지 않게)

- 기존 앱 결(흰 카드 radius 20·부드러운 그림자·헤어라인·SF 서체·포인트 최소) 그대로, 설정은 카드 4개(기온 No 페이드 / 매수·검증 / 사이징 / 손절)로 분리하고 카드 사이를 비웠다.
- 입력 방식: 비율·가격 계열은 슬라이더 + 큰 숫자(30px 800) 표시, 건수·시각 계열은 원형 스테퍼, 큰 금액(최소 거래량·현금 하한 하한값)만 큰 숫자 직접 입력. 빽빽한 입력칸 격자·작은 회색 라벨·설명 문장·경고 문구 없음.
- 저장은 카드별 버튼, 키 단위 병합 저장이라 한 카드 저장이 다른 카드 키를 지우지 않는다.
- 스크린샷: settings-screen-20261122.png (이 파일과 같은 폴더, 폭 400px 렌더 — 기온 카드부터 손절 카드까지). 모바일 폭에서 넘침·깨짐 없음을 확인했다. 촬영은 메인 화면 강제 표시 렌더로 했다 (연동 전 기기에서는 연동 화면이 먼저 뜬다).

## 검증 결과

- JS: book 383 · temp 106 · bundle 6 — 전부 통과
- JVM: NativeCycle 226 · TempNo 97 · ExternalWatchdog 15 · RestartGuard 7 · SubmitGuard 21 · StageDeadline 19 — 전부 통과
- 골든: 1차 38/38 · 2차 200 · 3차 178 (픽스처 172케이스 재생성) · 4차 215 — 전부 통과
- 번들 재생성 완료 (APP_VERSION=0.3.37), APK 빌드 + DEX GATE PASSED
- 축소 스모크: `android/test/run_shrunk_smoke.sh` 통과 (2026-11-22) — 출하와 같은 keep.pro로 R8 축소한 바이트코드로 골든 38/38 (주문 서명 포함), shrunk extra redeem submitBody OK, "shrunk smoke: PASSED"

## 동작 메모

- 기본값 미변경 시 동작 불변은 테스트로 고정했다 (DEFAULT_TRADE_SETTINGS = 기존 상수 전수 대조, 네이티브 기본값 리터럴 ↔ 출처 상수 전수 대조).
- 가장자리 변경 1건: 기온 밴드 하한 = 상한(빈 밴드, 예: 하한만 0.998로 설정)은 이제 쌍째로 기본값(0.8–0.998)으로 되돌린다. 기존에는 0.998 단일 지점 밴드로 유지됐다.
- TradeSettings(네이티브)의 기본값은 출처 상수를 참조하지 않고 리터럴로 둬서 설정 해석기가 스캔·검증 클래스를 끌어오지 않게 했고, 일치는 NativeCycleTest가 고정한다.
- 연동 화면 하단 요약 문구(밴드 0.60–0.90 · 건당 1% · 현금 30% · 최대 70건 · 손절 −0.25)는 기존 문구 그대로 뒀다 — 설정을 바꾸면 이 요약은 기본값 기준 표기로 남는다.

## 설정으로 뚫은 수치 (기능 3)

| 설정 키 | 기본값 | 기존 상수 위치 |
|---|---|---|
| bandLo | 0.6 | engine.js BAND_LO / ScanFilter.BAND_LO |
| bandHi | 0.9 | engine.js BAND_HI / ScanFilter.BAND_HI |
| priorityBandLo | 0.7 | engine.js scanCandidates 인라인 0.7 / ScanFilter.bandGroup |
| priorityBandHi | 0.8 | engine.js scanCandidates 인라인 0.8 / ScanFilter.bandGroup |
| volumeMin | 10000 | engine.js scanCandidates 인라인 / ScanFilter.VOLUME_MIN |
| endWindowDays | 45 | engine.js 인라인 45일 / ScanFilter.END_WINDOW_DAYS_MS |
| spreadMax | 0.02 | engine.js SPREAD_MAX / CandidateVerifier.SPREAD_MAX |
| askDepthMinUsd | 10 | engine.js ASK_DEPTH_MIN_USD / CandidateVerifier.ASK_DEPTH_MIN_USD |
| slippageCap | 0.015 | engine.js SLIPPAGE_CAP (매수 지정가 버퍼 + 손절 매도 하한 공용) / CandidateVerifier·write/StopLoss |
| snapshotMatchTol | 0.03 | engine.js SNAPSHOT_MATCH_TOL / CandidateVerifier.SNAPSHOT_MATCH_TOL |
| askAboveSignal | 0.05 | engine.js verifyCandidate 인라인 0.05 / CandidateVerifier.ASK_ABOVE_SIGNAL |
| betPct | 0.01 | engine.js BET_PCT / PositionBook.BET_PCT |
| betMinUsd | 5 | engine.js BET_MIN_USD / PositionBook.BET_MIN_USD |
| cashFloorPct | 0.3 | engine.js CASH_FLOOR_PCT / PositionBook.CASH_FLOOR_PCT |
| reserveUsd | 2.87 | engine.js RESERVE_USD / PositionBook.RESERVE_USD |
| maxPositions | 70 | engine.js MAX_POSITIONS / PositionBook.MAX_POSITIONS |
| verifyCap | 60 | engine.js VERIFY_CAP / NativeCycle.VERIFY_CAP |
| stopDrop | 0.25 | engine.js STOP_DROP / engine·write StopLoss.STOP_DROP |
| tempStopDrop | 0.15 | (기능 1 신규) engine.js TEMP_STOP_DROP / StopLoss.TEMP_STOP_DROP |
| decidedHigh | 0.97 | engine.js DECIDED_HIGH / StopLoss.DECIDED_HIGH |
| decidedLow | 0.03 | engine.js DECIDED_LOW / StopLoss.DECIDED_LOW |
| tempNoEnabled | false | (기존) engine.js parseTempSettings / TempNo.parseSettings |
| tempNoBandLo | 0.8 | (기존) TEMP_NO_DEFAULT_BAND_LO / TempNo.DEFAULT_BAND_LO |
| tempNoBandHi | 0.998 | engine.js TEMP_NO_BAND_HI / TempNo.BAND_HI |
| tempEntryHourUtc | 6 | engine.js TEMP_NO_ENTRY_HOUR_UTC + verifyCandidate 인라인 6 / TempNo.ENTRY_HOUR_UTC |
| tempNoReserveSlots | 1 | (기능 2) TEMP_NO_DEFAULT_RESERVE_SLOTS / TempNo.DEFAULT_RESERVE_SLOTS |

## 설정으로 뚫지 않은 수치 (인프라·외부 사실·집계 규칙)

- 네트워크·수명주기: FETCH_TIMEOUT_MS 20000, CYCLE_MS 60000, HEARTBEAT_INTERVAL_MS, LOOP_WATCH/STALL, SELF_RELOAD 계열, RPC 타임아웃 15000, HttpTransport/ClobWriteClient 타임아웃, REDEEM_POLL_MS/MAX, UPDATE_CHECK_EVERY_CYCLES 30
- 페이지네이션·저장 캡: POSITIONS_PAGE_CAP 50, ACTIVITY_PAGE_CAP 10, STOPLOSS_RECENT_CAP 200, STOPLOSS_ARCHIVE_CAP 10000, 시도 토큰 캡 200, ManualSell 캡·요청 만료 600000ms
- 재시도: REDEEM_SUBMIT_ATTEMPTS 2, SUBMIT_GUARD_MAX_MS 600000
- 외부 사실 (거래소 규격이라 사용자 설정 대상 아님): 수수료율 표 (crypto 0.07·politics/finance/tech 0.04·geopolitics 0·기타/기온 0.05), 틱·최소 주문 수량은 오더북 값 사용
- 정산·리딤 검증: REDEEM_TOL_ABS 0.05, REDEEM_TOL_REL 0.02 (매매 판단이 아닌 회수 검증 허용 오차)
- 실행 세부: 손절 매도 최소 수량 0.01주 (먼지 판정), 기온 이벤트 캐시 15분 (TEMP_NO_CACHE_MS)
- 익절: 앱 안에 익절 로직 자체가 없다 (포워드 관측은 앱 밖 훅) — 뚫을 수치 없음
