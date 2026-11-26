# 공리적 설계 분리 3단계 — runCycle 단계 분리 (결합 3)

- 상태: awaiting (2026-11-27 출시 완료)
- 릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.59
- 출시 커밋: 50b2127 (roomedia 단독 저자), 푸시·자산 첨부 확인 완료.
  다운로드한 릴리스 자산이 출하 APK와 바이트 동일함을 대조했다.
- 남은 확인은 실기기 몫이다: 설치 후 서명 제출 1회 (이번 변경은 서명
  경로 무접촉이라 게이트 표기만 유지).
- 기준: 감사 원문 ~/workspace/audits-20261126/boringmech-axiomatic-audit.md 결합 3
- 현재 버전: v0.3.58 → v0.3.59 (versionCode 62)

## 현행 구조 (실측)

- JS engine.js runCycle(2961~3417, 약 456줄): 스냅샷 취득(실패 시
  손절 단독 실행 폴백 포함), 손절, 리딤, 정산 통지·기준선, 스캔 전
  게이트, 스캔(메인+기온), 선정 드라이버(attemptCandidate 클로저),
  섀도우 기록, 요약을 한 함수가 직렬 실행. snap·stageMs* 누적값을
  전 단계가 공유한다.
- Java NativeCycle.runCycle(1462~1568) + runScanAndBuy(1597~1945):
  같은 단계들이 두 메서드에 나뉘어 있고 stageMs*·scanStartMs는
  인스턴스 필드로 공유한다. JS와 다른 지점: 설정은 사이클 시작에
  매매·기온을 1회 파싱(JS는 기온을 스캔 전 게이트에서 재읽기),
  수동 매도 처리가 스냅샷 단계 안에 있다. 이 차이는 기존 거동이라
  이번 단계에서 통일하지 않았다.

## 목표 구조 (구현 완료)

- runCycle은 오케스트레이터로 축소한다. 단계 함수 9개:
  snapshot → stoploss → redeem → settle → prescan → scan →
  selection → shadow → summary.
- 단계 간 공유는 사이클 시작에 만드는 컨텍스트 1개(JS
  createCycleContext, Java CycleContext)로 한정한다. 컨텍스트의
  가변 필드는 소유 단계만 쓴다: snap은 snapshot 단계가 취득하고
  stoploss·redeem 단계가 자기 단계 안에서만 갱신, settleNotice는
  snapshot이 계산하고 settle이 소비, 스캔 산출물(candidates·
  boughtAll·scanStats·기온 조회 결과)은 scan이 쓰고 selection·
  shadow·summary가 읽는다. timing 필드는 각 단계가 자기 필드만
  쓴다.
- 선정 카운터(슬롯·현금·검증 수·탈락 집계)는 selection 단계 내부
  지역 상태로 두고, 결과만 outcome으로 컨텍스트에 남긴다.
- Java는 stageMs*·scanStartMs 인스턴스 필드를 없애고 CycleContext의
  timing 필드로 옮겼다.

## 절대 조건 (준수)

- 거동 동일 리팩터. 단계 순서, 실패 전파, 로그 문자열, 사이클
  결과 레코드 형태 무변경. 단계 본문은 원본을 그대로 잘라 옮기고
  식별자 치환만 적용했다 (JS는 ctx 별칭 destructuring으로 원본
  지역 이름 유지, Java는 단계별 지역 snap 패턴).
- 실패 격리(단계 실패가 다른 단계를 막지 않게)는 이번에 넣지
  않았다. 거동 변경이라 별도 판단 건이다. 현행 전파 방식은 아래
  표가 정본이다.
- 매매·손절·리딤 판단 수치와 시도 순서 무변경.
- 구조 가드(JS 테스트의 runCycle 슬라이스 단언, book의
  `await snapshot(tradeCfg)` 전 파일 3회 단언, Java
  CycleAllocationTest의 NativeCycle 소스 가드)를 깨지 않는다 —
  단계 함수를 runCycle 뒤에 배치하고 리터럴을 보존했다.

## 현행 단계별 실패 전파 방식 (실측 정본)

| 단계 | 실패 시 거동 |
|---|---|
| 스냅샷 | 전용 폴백: 포지션만 재조회해 손절 단독 실행, 그것도 실패하면 마지막 스냅샷 장부 기준 폴백 손절. 그 뒤 원래 오류를 throw해 사이클 전체 중단 (호출자가 "사이클 오류"로 기록). 스캔·매수 미실행 |
| 손절 | throw하면 사이클 전체 중단 (이후 단계 전부 미실행) |
| 리딤 | throw하면 사이클 전체 중단 |
| 정산 통지·기준선 | 동기 코드 — 예외 시 사이클 중단 |
| 스캔 전 게이트 | 예외 시 중단. 조기 반환 2곳(슬롯 없음+차선 불가, 기매수 손상)은 오류가 아닌 정상 종료로 사이클을 건너뜀 |
| 스캔 | fetchMarkets throw 시 사이클 전체 중단. 단 기온 이벤트 조회는 단계 안에서 흡수: 전수 실패는 홀드 유지 신호, 예외는 로그만 남기고 기온 후보 없이 계속 |
| 선정 | 후보별 검증 예외는 verify_error 탈락으로 흡수, 제출 예외·결과 불명은 흡수. 세대 교체·정지 시 aborted로 즉시 중단(섀도우·요약 생략, Java는 홀드 로그도 생략). failsafe 감지 시 엔진 정지 후 중단 |
| 섀도우 기록 | try/catch로 흡수 — 실패해도 사이클은 정상 종료 |
| 요약 | 동기 로그 |

## 단계

- [x] JS 단계 분리 (컨텍스트 팩토리 + 단계 함수 9개)
- [x] Java 단계 분리 (CycleContext 신설 + 단계 메서드 9개)
- [x] 전면 감사 4축 — 발견 0건. 축 1·2·3: 델타 헝크가 engine.js의
      runCycle 구간과 NativeCycle 오케스트레이션 구간 + 신규
      CycleContext에만 있음을 diff로 확인 — 제출·서명·손절·리딤
      본문 무접촉이고 시도 순서 무변경. Java 제출 블록의 변환은
      return→return true 5곳뿐이며 finally(잠금 해제·endSubmit)
      구조를 그대로 유지한다. 축 4: 스냅샷 취득 횟수는 JS 3회
      (테스트 가드로 고정)·Java 4회로 기존과 동일하고, 컨텍스트는
      사이클마다 새로 만들어 이전 사이클 상태가 새지 않는다.
- [x] JS·JVM 전 슈트 + 골든 — 아래 검증 수치 참조.
- [x] 버전 범프 0.3.59 (versionCode 62) + 번들 재생성 + APK 빌드·
      DEX 게이트 PASSED·축소 스모크 PASSED. 출하 APK 직접 대조:
      versionCode 62, versionName 0.3.59, dex에 JavascriptInterface·
      engine/CycleContext 존재, assets 번들이 테스트한 번들과
      바이트 동일(md5), 번들에 "0.3.59"·temp_cash_guard 존재.
- [x] 커밋·푸시·릴리스 — 릴리스 자산 boringmech-v0.3.59.apk

## 검증 수치

- JS 전 슈트 88,949건 통과 (book 468 · temp 224 · bundle 10 ·
  html-guard 67 · settings-lock 56 · sellquote 30 · report 47 ·
  positions 14 · stoploss-fallback 21 · relayer-auth 18 ·
  relayer-keycheck 30 · shadow 84 · settings-parity 2,576 ·
  allocation 85,304) — 2단계와 동일 총합
- JVM 106,878건 통과 (NativeCycle 375 · TempNo 175 · ClobWrite 33 ·
  SubmitGuard 21 · ShadowLog 333 · WatchdogLogic 35 ·
  StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7 ·
  Settings 패리티 20,560 · CycleAllocation 85,305) — 2단계와 동일 총합
- 골든 전부 통과 (48/48 · 185 · 178 · 215)

## 미검증

- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉 — 사이클 오케스트레이션 재배치만).

## 메모

- 거동 동일 리팩터. 단계 실패 격리는 넣지 않았고, 현행 전파 방식은
  위 표가 정본이다. 격리를 원하면 별도 판단 건으로 올려야 한다.
- JS와 Java의 기존 차이(설정 파싱 시점, 수동 매도 위치, 중단 시
  홀드 로그 유무)는 이번 단계에서 통일하지 않았다.
