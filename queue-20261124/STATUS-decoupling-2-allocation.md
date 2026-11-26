# STATUS: 공리적 분리 2단계 — 자금 배분 계층 신설 (결합 2)

- 상태: awaiting (2026-11-26 출시 완료)
- 릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.58
- 출시 커밋: 8ab4c9d (roomedia 단독 저자), 푸시·자산 첨부 확인 완료.
  다운로드한 릴리스 자산이 출하 APK와 바이트 동일함을 대조했다.
- 남은 확인은 실기기 몫이다: 설치 후 서명 제출 1회 (이번 변경은 서명
  경로 무접촉이라 게이트 표기만 유지). 섀도우 기록 축적 확인도 설치
  후 첫 기온 사이클 제보로 한다 (.55부터의 누적 항목).
- 기준: 감사 원문 ~/workspace/audits-20261126/boringmech-axiomatic-audit.md 결합 2
- 현재 버전: v0.3.57 → v0.3.58 (versionCode 61)

## 현행 구조 (실측)

- JS engine.js: sizing(155)이 스냅샷 슬롯·금액·현금 하한을 만들고,
  선정 드라이버(runCycle 3239~3285)가 tempReserveBlocksMain(901)·
  tempReserveLaneAllows(917)·tempLaneCashBlocked(938)을 직접 호출한다.
  attemptCandidate(3046~)는 tempCashGuardBlocks(972)·tempCashGuardFloor
  (957)를 기온 설정으로 직접 조립해 호출한다. 스캔 전 차선 가능성
  판정(2959~2963)도 effectiveTempReserveSlots(884)를 직접 쓴다.
- Java: NativeCycle이 TempNo.effectiveReserveSlots·reserveBlocksMain·
  reserveLaneAllows·laneCashBlocked·cashGuardBlocks·cashGuardFloor를
  같은 지점들에서 직접 호출한다 (1546, 1677~1763).
- 즉 전략 간 내부 상태(예약 슬롯·홀드·포지션 수·기온 금액) 참조는
  전부 선정 드라이버와 시도 지점에 모여 있다.

## 목표 구조

- JS: computeCycleAllocation(사이클 입력 → 배분 결과) +
  allocationBlocksMain·allocationLaneAllows·allocationLaneCashBlocked·
  allocationCashGuardBlocks·allocationLaneEntryPossible.
  배분 결과: 메인 허용 슬롯·금액·현금 하한, 실효 예약, 홀드, 차선
  허용 건수, 현금 가드 하한. 기존 순수 판정 함수는 배분 계층이
  합성하는 원형으로 유지한다.
- Java: engine/CycleAllocation.java가 같은 계약을 미러한다.
- 드라이버는 배분 결과만 소비한다. 카운터(슬롯·차선·현금 잔여)는
  사이클 실행 상태라 드라이버가 계속 소유한다.

## 단계

- [x] 현황 파악: JS·Java 판정 함수와 호출 지점 전수
- [x] 배분 계층 설계 (입력/출력 계약)
- [x] 픽스처 대조 하네스 — 리팩터 전 순수 판정 + 드라이버 식으로
      기준선 캡처 (app/test/gen_allocation_fixture.mjs →
      fixtures/allocation-parity.json, 23개 시나리오·2,279건).
      사고 재현값(현금 $8.67·기온 건당 $6·예약 1·하한 $8.87), 가드
      경계(하한 정확히 일치 허용), 홀드 유지(전수·일부 실패)·해제,
      예약 0·2·3개, 기온 OFF, 예약 상한 클램프 포함.
- [x] JS 배분 계층 구현 + 호출부 재배선 — computeCycleAllocation +
      allocation* 판정 5종을 engine.js에 신설, runCycle의 선정
      드라이버·attemptCandidate·스캔 전 차선 판정을 배분 결과
      소비로 교체. 드라이버에 구 판정 직접 호출 0건 (구조 가드).
- [x] Java 배분 계층 구현 + 호출부 재배선 — engine/CycleAllocation
      .java 신설, NativeCycle의 같은 지점들을 alloc 소비로 교체.
- [x] 픽스처 전수 대조 — JS 85,304건·Java 85,305건 통과, 어긋남 0
      (픽스처 2,279건 + 라이브 교차 대조 81,600건 + 구조 가드 포함).
- [x] 전면 감사 4축 — 발견 0건. 축 1·2·3: 델타 헝크가 engine.js
      배분 구간(977~1080)과 runCycle 배선 6곳, NativeCycle 배선
      5곳에만 있음을 diff로 확인 — 제출·서명·손절·리딤 경로는
      무접촉이고 시도 순서(금액 0 → 현금 하한 → 현금 가드 → 검증)
      무변경. 축 4: sizing·snapshot 무변경, 배분은 스냅샷 출력을
      그대로 받는다. NaN·Infinity 거동은 교차 대조로 동일 확인.
      평가 순서 변화(창 판정이 배분 인자로 조기 평가)는 양쪽 모두
      순수·null 안전이라 무영향. JS alloc 늦은 바인딩은 할당과
      선정 루프 사이에 반환 지점이 없어 안전하다.
- [x] JS·JVM 전 슈트 + 골든 + APK 게이트 — 아래 검증 수치 참조.
- [x] 버전 범프 0.3.58 (versionCode 61) + 번들 재생성 + APK 빌드·
      DEX 게이트 PASSED·축소 스모크 PASSED. 출하 APK 직접 대조:
      versionCode 61, versionName 0.3.58, dex에 JavascriptInterface·
      engine/CycleAllocation 존재, 번들에 APP_VERSION 0.3.58·
      tempHoldSignal·현금 가드/홀드 해제 로그 문자열 존재.
- [x] 커밋·푸시·릴리스 — 릴리스 자산 boringmech-v0.3.58.apk

## 검증 수치

- 픽스처 패리티: JS 85,304건 · Java 85,305건 통과, 어긋남 0
  (픽스처 기준선 2,279건 + 라이브 교차 대조 81,600건 + 구조 가드)
- JS 전 슈트 88,949건 통과 (book 468 · temp 224 · bundle 10 ·
  html-guard 67 · settings-lock 56 · sellquote 30 · report 47 ·
  positions 14 · stoploss-fallback 21 · relayer-auth 18 ·
  relayer-keycheck 30 · shadow 84 · settings-parity 2,576 ·
  allocation 85,304)
- JVM 106,878건 통과 (NativeCycle 375 · TempNo 175 · ClobWrite 33 ·
  SubmitGuard 21 · ShadowLog 333 · WatchdogLogic 35 ·
  StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7 ·
  Settings 패리티 20,560 · CycleAllocation 85,305)
- 골든 전부 통과 (48/48 · 185 · 178 · 215)

## 미검증

- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉 — 배분 재배치만).

## 메모

- 거동 동일 리팩터. 배분 계층은 새 판정을 만들지 않고 기존 판정을 재배치만 한다.
- 로그 사유 문자열 (temp_cash_guard, temp_cash_short 등) 무변경.
