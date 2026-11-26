# 공리적 설계 분리 6단계 — 손절 장부 인터페이스 고정 (결합 6)

- 상태: awaiting (2026-11-27 출시 완료)
- 릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.61
- 출시 커밋: fca6ed7 (roomedia 단독 저자), 푸시·자산 첨부 확인 완료.
  다운로드한 릴리스 자산이 출하 APK와 바이트 동일함을 대조했다.
- 남은 확인은 실기기 몫이다: 설치 후 서명 제출 1회 (이번 변경은 서명
  경로 무접촉이라 게이트 표기만 유지).
- 기준: 감사 원문 ~/workspace/audits-20261126/boringmech-axiomatic-audit.md 결합 6
- 현재 버전: v0.3.60 → v0.3.61 (versionCode 64)

## 현행 구조 (실측)

세 경로와 attempted 장부:

- 자동 손절 (runStopLoss)과 폴백 손절 (runFallbackStopLoss)은 양쪽 다
  executeStopLossSell 한 실행부를 타고, 결과 불명일 때만
  recordStopLossAttempt(tokenId, slug)가 bm_stoploss_attempted(토큰,
  꼬리 캡 200)와 bm_stoploss_attempted_slugs(slug, 꼬리 캡 10,000)를 쓴다.
  호출 지점은 executeStopLossSellLocked 안 2곳 (제출 예외, 판정 unknown).
- 수동 매도는 네이티브 전용 경로다. Java StopLossSeller.executeManualSell이
  같은 실행 코어를 타되 기록만 ManualSellRecorder로 갈리고, 결과 불명이면
  bm_manual_sell_attempted(최근 캡 200, 초과분은
  bm_manual_sell_attempted_archive로 회전, 아카이브 캡 10,000)에 쓴다.
  JS는 쓰지 않고 manualSellAttemptedTokens로 읽기만 한다.
- 조회 소비처: 스캔 단계가 stopLossAttemptedSlugs를 재매수 차단 집합에
  합치고 (engine.js:3180), 폴백 손절이 처분 완료 토큰 제외에
  stopLossRecords·manualSellRecords를 쓴다. attempted 토큰 집합 자체는
  매도 재시도를 막지 않는다 — 다음 사이클이 포지션 실재로 다시 판정한다.
  세대·날짜 경계 리셋은 없다 (캡 회전만 있다).

고정돼 있지 않던 부분:

- JS: attempted 접근 함수 4개(stopLossAttemptedTokens·
  stopLossAttemptedSlugs·manualSellAttemptedTokens·recordStopLossAttempt)는
  손절 원장 클러스터에 모여 있으나 소유자 구간 선언과 구조 가드가 없었다.
  recordStopLossAttempt만 export가 없어 외부에서 부를 수 없었다.
- Java: 손절 attempted는 write/StopLossLedger가 이미 소유자였다. 그러나
  수동 매도 장부(기록·attempted)의 저장 조작이 NativeCycle 생성자의
  익명 ManualSellRecorder 안에 인라인으로 있었고 (loadDurableJsonList +
  ManualSell.append*WithArchive + env.durableWrite), 폴백 손절의 수동
  기록 읽기도 NativeCycle가 키를 직접 읽었다.

## 구현 (완료)

- JS: 손절 원장 클러스터를 소유자 구간으로 선언하고 attempted 고정
  인터페이스(기록 recordStopLossAttempt, 조회 3개)를 명시했다. 코드
  이동은 하지 않았다. recordStopLossAttempt에 export만 붙였다 (픽스처
  구동용, 거동 무변경). 신규 테스트 stoploss-ledger-owner.test.mjs에
  교차 경로 픽스처와 구조 가드를 넣고 npm test 체인에 연결했다.
- Java: write/ManualSellLedger 신설 — StopLossSeller.ManualSellRecorder를
  구현하고 StopLossLedger.Store 뒤에 수동 매도 기록·attempted 저장
  조작을 소유한다. 조회(records·attemptedTokens)도 제공한다.
  NativeCycle은 익명 리코더를 ManualSellLedger로 교체하고, 폴백의 수동
  기록 읽기를 records()로 재배선했다. 신규 테스트 ManualSellLedgerTest
  (종전 인라인 합성과의 전 단계 대조 픽스처 + NativeCycle 소스 가드).

## 진행·검증

- [x] JS 픽스처 기준선: 거동 46건을 리팩터 전 코드에서 먼저 통과
      (recordStopLossAttempt export 추가만 선행 — 거동 무변경 표면 변경)
- [x] JS: 소유자 구간 선언 + 구조 가드 23건 추가 (합계 69건 통과)
- [x] Java: ManualSellLedgerTest 28건 통과 — 종전 인라인 합성과
      전 단계 저장소 대조(회전·손상·중복 포함) 어긋남 0
- [x] 감사 발견 1건 수리: 첫 배선은 ManualSellLedger를 공유
      ledgerStore에 붙여 수동 장부의 저장 백엔드를 env durable에서
      ledgerStore로 바꿔 버렸다. 실기기에서는 둘 다 bm_durable이라
      겹치지만, 백엔드가 갈리는 테스트 하네스에서 수동 기록이 다른
      저장소로 새는 것이 NativeCycleTest 실패(수동 매도 3건)로
      드러났다. 4단계 BoughtLedger 패턴대로 env durable 어댑터로
      되돌리고, 배선을 소스 가드로 고정했다. 수리 후 NativeCycleTest
      375건 전부 통과. (resolve-outside-timeout 1건은 실패 실행에서
      한 번만 관찰됐고 수리 후 재현 없음 — 병렬 부하 시점의 일시
      실패로 기록)
- [x] 전면 감사 4축 발견 0건. 축 1: 델타가 제출·서명 본문 무접촉
      (StopLossSeller 무변경, JS 델타는 주석·export만). 축 2: attempted
      기록 지점·판정·순서 무변경, 폴백 제외 입력의 필터 동등성
      (combineRecords와 parseStopLossList가 같은 조건)을 코드로 확인.
      축 3: 리딤 무접촉, 장부 집계 입력 무변경. 축 4: 스냅샷 취득
      횟수·사이징 무변경, 상수 게이트 PASS.
- [x] JS 전 슈트 체인 전부 통과 (신규 stoploss-ledger-owner 69 포함),
      JVM 전 슈트 통과 (NativeCycle 375, Settings 20,560,
      CycleAllocation 85,305, BoughtLedger 34, ManualSellLedger 28,
      ClobWrite 33, ShadowLog 333, TempNo 175, SubmitGuard 21,
      StageDeadline 19, ExternalWatchdog 15, RestartGuard 7)
- [x] 골든 전부 통과 (48/48 · golden2/3/4 ALL PASS)
- [x] APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED. 출하 APK
      직접 대조: versionCode 64, versionName 0.3.61, dex에
      JavascriptInterface·write/ManualSellLedger 존재, assets 번들이
      테스트한 번들과 바이트 동일(md5)

## 미검증

- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉).
