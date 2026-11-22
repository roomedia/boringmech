# STATUS: v0.3.38 출시

- 상태: awaiting
- 릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.38 (APK boringmech-v0.3.38.apk 328,232바이트 첨부 확인)
- 출시 커밋: 6911065 (roomedia 단독 저자), 태그 v0.3.38, main 푸시 완료
- 서명 경로 실기기 검증은 아직이다. 실기기에서 실제 서명 제출 1회 성공이 확인되기 전까지 이 버전은 서명 경로 미검증 상태다 (릴리스 노트에 표기함).
- 시작: 2026-11-22
- 범위: 출시 전 전면 감사(4축) → 수리·가드 테스트 → 버전 범프 → 검증 전수 → 커밋·태그·GitHub 릴리스
- 변경분: 기온 손절 0.15, 기온 슬롯 수 설정, 매매 수치 26개 설정화 (커밋 0d4671c, main 푸시 완료)
- 단계:
  - [완료] 1단계: 출시 전 전면 감사 (4축) — 확정 발견 8건, 전부 수리 대상으로 확정
  - [완료] 1.5단계: 감사 발견 수리 + 가드 테스트 (아래 "1.5단계 수리 기록" 참조)
  - [완료] 2단계: 버전 0.3.38 범프 (build.sh APK명·version-code 41·version-name, package.json) + 번들 재생성 (APP_VERSION=0.3.38)
  - [완료] 3단계: 검증 전수 — JS book 405·temp 118·bundle 6, JVM NativeCycle 249·TempNo 105·ExternalWatchdog 15·RestartGuard 7·SubmitGuard 21·WatchdogLogic 35·StageDeadline 19, 골든 1차 38/38·2차 200·3차 178·4차 215, APK 빌드 + DEX GATE PASSED, 축소 스모크 PASSED
  - [완료] 4단계: 커밋 6911065 (roomedia 단독 저자), 태그 v0.3.38, GitHub 릴리스 + APK 첨부
- 주의: 기온 전략 토글 기본 OFF, 메인 손절 -0.25 등 기존 기본값 유지. 무관한 백테스트 산출물은 커밋에 섞지 않는다.

## 1.5단계 수리 기록 (2026-11-22, 작업 트리에만 반영 — 커밋·버전 범프 안 함)

수리 8건 + 경화 1건 전부 JS(engine.js)·네이티브 패리티로 적용, 번들 재생성 완료 (APP_VERSION=0.3.37 유지).

1. 설정 파서 타입 엄격화: JS `strictNum` 신설 (숫자·숫자 문자열만 인정, null·""·불리언·배열·객체는 NaN→기본값), `numField`와 `parseTempSettings`가 사용. 네이티브는 `TradeSettings.strictFieldNum` 신설 (Js.num은 불변, 필드 추출 지점에서만 필터), `TempNo.parseSettings`도 같은 헬퍼 사용. 숫자 문자열 허용은 유지 (기존 테스트 고정).
2. 사이클당 설정 1회 파싱: 네이티브 `runCycle`이 TradeSettings·TempNo.Settings를 시작에 1회 파싱해 `snapshot(cfg)`·`runStopLoss(gen, cfg)`·`checkStopLossPosition`·`runScanAndBuy(gen, snap, cfg, tempSettings)`에 전파 (기존 재파싱 3지점 제거). 사이클 밖 진입점(`snapshot()` 무인자, `NativeEngine` 복원, `runStopLoss(gen)` 무인자, `processManualSell`)은 각 진입점에서 새로 파싱. JS는 `runCycle`의 tradeCfg를 `snapshot(tradeCfg)` 3회 호출 전부에 전파, 사이클 밖 호출자(link·restore)는 `snapshot()` 무인자 → 내부에서 파싱. TempNo 파싱 횟수 확인: JS는 기존에도 사이클당 1회(스캔 절)였고, 네이티브는 runScanAndBuy 1회를 runCycle 시작으로 이동해 사이클 전체가 같은 파싱을 공유.
3. bet ≤ 0이면 slots 0: JS `sizing`, 네이티브 `PositionBook.sizing` 모두 `bet > 0`일 때만 cashSlots 계산.
4. decided 쌍 교차 가드: 양쪽 파서 모두 `decidedLow >= 0.5 || decidedHigh <= 0.5`면 쌍을 기본값(0.03/0.97)으로 복귀 (기존 순서 역전 복귀와 별개). 재현 케이스 {0.90, 0.95} 양쪽 테스트로 고정.
5. 수동 매도 slippageCap 설정 적용: 네이티브 `StopLossSeller.executeManualSell(row, current, gen, slippageCap)` 오버로드 신설, `NativeCycle.processManualSell`이 호출 지점에서 TradeSettings를 파싱해 전달 (손절 경로와 동일 방식). JS 확인 결과: JS 엔진에는 수동 매도 실행 경로 자체가 없다 (수동 매도는 네이티브 전용, JS는 수동 매도 원장 읽기만 한다) — JS 쪽 동일 문제 없음.
6. 예약 슬롯 교차 클램프: 실효 예약 = min(tempNoReserveSlots, maxPositions). JS `effectiveTempReserveSlots` 신설해 runCycle 판정 지점에서 계산, 네이티브 `TempNo.effectiveReserveSlots` 신설해 runScanAndBuy 판정 지점에서 계산.
7. 리딤 pending 절단 아카이브: 양쪽 모두 새 키 `bm_redeem_pending_archive` 도입. save는 전체 목록을 받아 300건 초과분을 아카이브 키에 보존(덮어씀 — 전체 목록에서 빠진 해소 id는 아카이브에서도 제거), load는 아카이브+본 목록을 condition_id|token_id 키로 중복 제거해 병합 → 리딤 사이클이 아카이브 항목을 다시 회수·재평가한다. 기존 history/closed의 done 아카이브 패턴과 같은 방식.
8. ShadowSnapshot 설정 반영: 기본값 오버로드 대신 `TradeSettings.parse(durable(bm_settings))`로 `PositionBook.sizing` 호출. JVM 슈트 대상이 아닌 클래스라 android.jar 전체 소스 컴파일로 검증 (아래 참조).
9. 경화 strategyOf: 양쪽 모두 slug 일치 항목 전수 스캔, temp_no가 하나라도 있으면 temp_no 반환.

가드 테스트 (전부 신규 추가):
- JS book.test.mjs §45: {cashFloorPct: null}→0.3, {betPct: true}→0.01, {volumeMin: true}→10000, {askDepthMinUsd: null}→10, {bandLo: ""/[]/{}}→기본값, 숫자 문자열(공백 포함) 유지, decided {0.90,0.95}·{0.4,0.45}→기본값·{0.2,0.8} 유지, bet 0→slots 0, 사이클 1회 파싱 구조 가드, 리딤 아카이브 저장·병합·해소 제거.
- JS temp.test.mjs: {tempNoReserveSlots: null/true/""}→1, {tempNoBandLo: true}→0.8, {tempEntryHourUtc: null}→6, effectiveTempReserveSlots 클램프, boughtStrategyOf 중복 항목 경화, 예약 드라이버 구조 가드를 클램프 형태로 갱신.
- JVM NativeCycleTest: auditSettingsGuards (위와 같은 케이스 + strategyOf 경화), cycleSettingsParsedOnce (스캔 중 설정 변경에도 사이클 시작 설정으로 매수), manualSellSlippageCapFromSettings (캡 0.1 → 하한가 0.45, takerAmount 대조), redeemPendingArchiveSurvives (301건 절단 아카이브 보존 + 아카이브 worthless 재평가·종결·제거).
- JVM TempNoTest: {tempNoReserveSlots: null/true/""}→1 등 엄격화 케이스 + effectiveReserveSlots 클램프.
- 기존 구조 가드 2건은 시그니처 변경에 맞춰 갱신 (book의 snapshot 절단 문자열, temp의 예약 드라이버 문자열).

테스트 결과 (전부 통과):
- JS: book 405 passed / temp 118 passed / bundle 6 passed.
- JVM: NativeCycle 249 passed, TempNo 105 passed, ExternalWatchdog 15 passed, RestartGuard 7 passed, SubmitGuard 21 passed, StageDeadline 19 passed (WatchdogLogic 35 passed 포함 실행).
- 비고: NativeCycle 첫 실행에서 기존 타이밍 테스트 postOrderResolveOutsideTimeout 1건이 실패했으나 재실행에서 249/249 통과 (원본 HEAD 워크트리 대조 226/226 통과 — 수리와 무관한 플레이크로 기록).
- ShadowSnapshot 포함 전체 안드로이드 소스 javac 컴파일 (android-34 android.jar) 통과.
- 골든 테스트는 지시대로 이 단계에서 실행하지 않음 (3단계 몫).

## 감사 확정 발견 (4축, 코드 대조 완료)

1. (축4 높음/축1) 설정 파서가 손상 타입(null·""·불리언·배열)을 위험 값으로 강제변환: cashFloorPct null→0, betPct true→1, volumeMin true→1, askDepthMinUsd null→0, tempNoReserveSlots null→0. JS numField(engine.js:663), 네이티브 TradeSettings.numField, TempNo 파싱 양쪽.
2. (축1/축4/축3) 설정이 사이클 안 여러 지점에서 재파싱돼 구·신 설정이 섞이고 JS/네이티브 패리티가 깨짐: 네이티브 snapshot(:357)·손절 건별(:560)·스캔(:1312), JS snapshot(:1394) 재파싱.
3. (축1/축4) betMinUsd=0 + 작은 betPct 조합에서 bet=0인데 slots가 maxPositions까지 참 (0 나눗셈 무한대 캡). PositionBook.java:118-128, engine.js:148-160.
4. (축2) decided 쌍 조합 가드 부재: {decidedLow 0.90, decidedHigh 0.95}면 전부 decided 판정이라 손절 영구 미발화. UI로는 도달 불가, 저장소 직접 편집 시만.
5. (축4) 네이티브 수동 매도가 slippageCap 설정을 안 타고 상수 0.015 고정 (StopLossSeller.java:173).
6. (축3) tempNoReserveSlots와 maxPositions 교차 검증 없음: 예약 70 + maxPositions 2면 메인이 사이클마다 전량 defer.
7. (축3) 리딤 pending 300건 캡이 아카이브 없이 절단해 영구 리딤 누락 가능 (NativeCycle.java:704-707, engine.js:2718-2720). maxPositions 상향으로 도달 가능성 증가.
8. (축3) ShadowSnapshot이 기본값 사이징 오버로드를 써서 설정 변경 시 패리티 대조가 상시 거짓 불일치 (ShadowSnapshot.java:94).
- 경화 1건 (축2·축3 미확정 M1): strategyOf가 첫 일치 항목만 봄. 중복 항목이 있으면 temp_no 태그가 가려질 수 있어, 전 항목 스캔해 temp_no 우선으로 경화한다.
- 기본값 26개 전수 대조는 양쪽 감사 모두 불일치 0건 확인. 미확정 항목(태그 가림의 실재 여부, worthless streak 키 형식 등)은 절차상 수리 대상 아님으로 기록만 한다.
