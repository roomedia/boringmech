# runCycle 단계 실패 격리 (동호 결정 2026-11-27, 거동 변경)

- 상태: awaiting (구현·검증·출시 완료, 실기기 제출 확인 전까지 서명 경로는 미검증으로 표기 — 이번 변경은 서명 경로 무접촉)
- 기준: STATUS-decoupling-3-runcycle.md의 실패 전파 표가 격리 경계의 기준.
- 출시: v0.3.63 (versionCode 66)

## 결정 사항

- 9단계(snapshot·stoploss·redeem·settle·prescan·scan·selection·shadow·
  summary)를 각각 격리 경계로 감싼다. 한 단계가 예외로 실패하면 그
  단계만 건너뛰고 나머지 단계는 계속 돈다 (JS·Java 양쪽).
- 스냅샷은 뒤 단계 전부의 입력이라 기존 폴백 체인(손절 단독 실행 →
  폴백 손절)을 단계 안에 그대로 두고, 폴백까지 실패하면 그 사이클은
  진행 불가로 끝낸다. 단 루프는 죽지 않고 다음 사이클로 살아남는다.
- scan 실패 시 selection·shadow·summary는 실행하지 않는다 (입력이
  없는 단계를 빈 입력으로 돌리지 않는다). selection 실패 시도
  shadow·summary를 생략한다 (종전 중단 거동과 동일).
- prescan 실패는 prescan 정상 false와 같이 매수를 건너뛰는 쪽으로
  처리한다 (안전 방향).

## 표면 연결 (카운터 의미 변경 명시)

- 격리된 실패는 ctx.stageFailures에 {stage, message}로 기록하고,
  실패 즉시 로그 1줄("단계 실패 격리 — <stage> …")을 남기며,
  사이클 요약에도 "단계 실패 <stage…>" 구간을 붙인다 (실패가 있는
  사이클만 — 성공 경로 로그 문자열은 무변경).
- 루프(JS loop·Java runIteration)는 stageFailures가 1건이라도 있는
  사이클을 오류 사이클로 센다: 기존 "사이클 오류: <첫 실패 메시지>"
  로그를 사이클 끝에 남기고 cycleErrors를 올린다.
  - 카운터 의미 변경: 종전 "예외로 중단된 사이클" → 이후 "단계 실패가
    1건이라도 있는 사이클 (중단·격리 무관)". 뷰어의 로그 꼬리 연속
    "사이클 오류:" 5회 자동 제보와 10회 자가 재로드/재시작 임계는
    이 카운터를 그대로 타므로 격리된 실패도 같은 표면으로 드러난다.
  - 섀도우 단계는 종전 내부 try/catch로 조용히 삼키던 실패도 이제
    기록·표면 대상이다 (동호 지시: 조용히 삼키지 말 것).

## 구현

- JS: runCycle이 export된 runCycleStages(ctx, stages)에 위임한다.
  isolateCycleStage·recordStageFailure(메시지 120자 절단) 신설,
  createCycleContext에 stageFailures 추가, cycleShadowStage의 내부
  catch 제거, summarizeCycle에 stageFailures 구간 추가 (durations 앞).
  loop()는 반환 ctx의 stageFailures로 오류 사이클을 판정한다.
- Java: CycleContext.stageFailures + StageFailure 신설. runCycle이
  CycleContext를 반환하고 isolateStage로 9단계를 재배선한다.
  summarizeCycle 9인자 오버로드 (8인자는 위임 — 기존 서명 보존).
  runIteration이 같은 규칙으로 오류 사이클을 판정한다.

## 검증 수치

- 실패 주입 대조: JS 51건 (오케스트레이션 9단계 전수 + recordStageFailure
  + 요약 패리티 + 소스 가드), Java 실사이클 6케이스 (손절·스냅샷·스캔·
  정산·리딤·섀도우 주입, 손절 케이스는 매도 실행·스캔 계속·매수 제출·
  오류 카운터·다음 사이클 생존까지 단언) + 요약 구간 3건 + 소스 가드
  11건 (CycleAllocationTest).
- 성공 경로 패리티: JS 전 슈트 통과 (settings-parity 2,576 ·
  allocation 85,304 · shadow 84 외 전 파일 0 failed), NativeCycleTest
  408 passed (기존 375 + 신규 33), CycleAllocation 85,316, Settings
  20,560, 골든 48/48 · golden2/3/4 ALL PASS, shrunk smoke PASSED.
- 전면 감사 4축: 델타는 오케스트레이션·요약·루프 판정 구간에만 있고
  제출·서명·손절·리딤·스냅샷 본문 헝크 0건. 손절 실패 시 매도는 단계
  안에서 완료·원장 기록 후라 재매수 차단(boughtAll)이 유지되고,
  스냅샷이 매도 전 값이라 슬롯·현금은 보수 방향이다. 발견 0건.
  - 잔여 관찰 1건 (수용): settle 저장 실패 시 메모리 기준선만 먼저
    갱신돼 재시작하면 정산 알림이 1회 재발할 수 있다. 종전(사이클
    전체 중단)보다 strictly 낫고 매매 판단 무영향이라 수용한다.
- 출하본 대조: versionCode 66 · versionName 0.3.63, DEX GATE PASSED,
  번들 md5가 빌드 산출물과 동일, dex에 StageFailure·격리 문자열 존재.

## 단계

- [x] JS 격리 오케스트레이션 (runCycleStages 분리 + loop 연결)
- [x] Java 격리 오케스트레이션 (runCycle 재배선 + runIteration 연결)
- [x] 실패 주입 픽스처 (JS 오케스트레이션 9단계 + Java 실사이클 주입)
- [x] 전면 감사 4축 — 발견 0건
- [x] JS·JVM 전 슈트 + 골든
- [x] 버전 범프 0.3.63 (versionCode 66) + 번들 + APK 게이트
- [ ] 커밋·푸시·릴리스
