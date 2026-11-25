# v0.3.56 현금 가드 출시 상태

상태: awaiting (2026-11-26 출시 완료)
릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.56
남은 확인은 실기기 몫이다: 설치 후 서명 제출 1회 (이번 변경은 서명
경로 무접촉이라 미검증 표기는 릴리스 노트에 명시).
사용자 결정: 2026-11-26 승인 (현금 가드 도입).

## 명세
- 조건: 기온 ON + 예약 슬롯 > 0일 때만 작동.
- 보호 하한 = 예약 슬롯 수(실효값) × 기온 건당 금액 + 예비금 $2.87.
  메인 매수는 매수 후 현금이 하한 아래로 내려가면 차단. 현금이 이미
  하한 아래면 메인 매수 전부 차단.
- 기온 차선·기온 후보·손절·리딤 무접촉.
- 차단 시 사유 temp_cash_guard로 집계하고, 하한·현재 현금을 넣은
  로그를 사이클당 1회 남긴다.

## 단계
- [x] 구현 (JS engine.js + 네이티브 TempNo·NativeCycle 패리티)
  순수 판정 tempCashGuardFloor/tempCashGuardBlocks (JS),
  TempNo.cashGuardFloor/cashGuardBlocks (네이티브). 드라이버는 일반
  슬롯 시도에서만 적용하고 temp_cash_guard로 집계, 차단 로그는
  사이클당 1회 (하한·현금·건당 포함).
- [x] 가드 테스트 양쪽 (경계값·예약 2개·OFF/예약 0·기온 차선 무접촉)
  JS temp 224건 (+21), 네이티브 TempNo 175건 (+17) 통과.
- [x] 전면 감사 4축 (RELEASE-AUDIT) — 발견 0건.
  축 1: 가드는 검증·제출 잠금 이전에 시도만 건너뜀. 차선(viaLane)과
  기온 후보(strategy)는 판정에서 제외됨을 코드로 확인.
  축 2: 손절 경로 diff 무접촉, 손절은 매수 패스보다 앞 단계 유지.
  축 3: 리딤·정화·장부 diff 무접촉. 신규 사유는 요약 집계에만 흐르고
  라벨은 양쪽 모두 등록.
  축 4: 가드는 사이클 로컬 cashLeft와 해석 완료된 설정만 읽음.
  비유한 입력이면 하한 null로 가드가 꺼지지만, 그 경우 sizing이
  슬롯을 먼저 닫고 기존 cash_floor 가드와 슬롯 홀드가 그대로라
  위험 방향으로 떨어지지 않음. 설정 손상 시 parse 기본값이 기온
  OFF라 가드도 함께 꺼짐 (안전 방향).
- [x] 버전 범프 0.3.56 (versionCode 59), 번들 재생성
- [x] 전 슈트·골든·APK 빌드·DEX 게이트·축소 스모크
- [x] 커밋·푸시·GitHub 릴리스 게시

## 감사 중 처리한 사항
- NativeCycleTest 기존 4건(창 지남·전부 기매수·조회 빈 목록·홀드
  해제)이 새 가드에 걸려 실패했다. 픽스처 현금 $12 + 기본 기온 건당
  $5면 하한 $7.87인데 매수 후 현금이 $7이라, 홀드 해제 검증이 가드에
  가려진 것이다. 가드는 명세대로 창 상태와 무관하게 작동하는 게
  맞으므로 코드를 되돌리지 않고, 4개 픽스처의 현금을 슬롯 수가
  그대로인 $14로 올려 홀드 검증 의도를 유지했다. 가드 자체는 사고
  재현값 드라이버 테스트 2건을 따로 추가했다.

## 검증 수치
- JS 전 슈트 1,069건 통과 (temp 224 포함, 신규 가드 21건)
- JVM 1,013건 통과 (NativeCycle 375 · TempNo 175 · ClobWrite 33 ·
  SubmitGuard 21 · ShadowLog 333 · WatchdogLogic 35 · StageDeadline
  19 · ExternalWatchdog 15 · RestartGuard 7)
- 골든 전부 통과 (48/48 · golden2/3/4 ALL PASS)
- APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED. 출하 APK
  직접 대조: versionCode 59, versionName 0.3.56, dex에
  JavascriptInterface·temp_cash_guard·cashGuardBlocks 존재, 번들에
  APP_VERSION 0.3.56과 가드 로그 문자열(\u 이스케이프) 존재.

## 미검증
- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉 — 매수 시도 게이트만 추가).
