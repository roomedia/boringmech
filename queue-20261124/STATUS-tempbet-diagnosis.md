# STATUS tempBetUsd 진단

- 상태: awaiting (진단·수리·테스트·커밋 완료, 출시만 남음)
- 커밋: 9dd64dc (로컬 main, 미푸시·미출시)

## 판정

원인은 코드 결함 하나로 특정된다. **기온 카드 저장 핸들러가 tempBetUsd를 bm_settings에 쓴 적이 없다.**

- app/index.html btnTempSave 핸들러(구 1806-1853행)의 writeSettingsObject가 tempNoEnabled·tempNoBandLo·tempNoBandHi·tempEntryHourUtc·tempStopDrop·tempNoReserveSlots 6개만 썼다. TEMP_FIELDS(1157행)에는 tempBetUsd가 있어 dirty 판정·저장 버튼 활성화·점 표시는 정상 동작했지만, 정작 저장 목록에서 빠져 있었다.
- 저장 직후 syncGroupForm('temp', true)가 저장본으로 폼을 다시 채우는데, readTempSettings(1027행)는 bm_settings에 없는 tempBetUsd를 기본값 5로 해석하므로, 사용자는 저장 버튼을 누르는 순간 값이 5로 되돌아가는 것을 봤다. "올리고 저장했는데 계속 $5" 보고와 정확히 일치한다.
- 나머지 경로는 전부 정상이었다. UI 입력(스테퍼 253-258행, 숫자 시트 1408-1410행)은 hidden input을 바꾸고, JS parseTempSettings(src/engine.js:776)·네이티브 TempNo.parse(TempNo.java:400)는 tempBetUsd를 읽으며, NativeCycle:1683은 tempSettings.betUsd를 후보별 금액으로 쓴다. 값이 저장소에 들어간 적이 없어 전 경로가 기본값으로 떨어진 것뿐이다.
- 표시 쪽 착시 요인(부차): 홈 요약 settingsSummaryText(engine.js:733)는 설계상 메인 betUsd만 표기하고 사이클 머리 로그(2457행)도 메인 snap.bet만 찍는다. 단독 원인은 아니고, 카드 값 자체가 되돌아가는 게 주증상이다.

## 현금 상태 (부차 사실)

- data-api v2 재확인: 보유 8건, 포지션 평가 합계 $37.25, 포트폴리오 value $37.33.
- 현금 재구성 $3.46 + 리딤 $5.11 − 타이베이 매수 $5.00 ≈ $3.57 (data-api는 현금을 직접 노출하지 않아 제보·활동 기록 기반 재구성, 수수료 약 $0.005 수준이라 결론에 영향 없음).
- 이 현금이면 sizing 슬롯 0 (현금 하한 max($2.87, 평가금×30% ≈ $12.3))이고, 예약 차선 조건(건당 $5 + 예비금 $2.87 = $7.87)에도 못 미쳐, 저장 결함이 없었어도 오늘 창에서 기온 진입은 불가능했다.
- 그런데 차선 현금 차단은 탈락 집계에 남지 않아, 진입이 없는 이유가 화면·로그 어디에도 드러나지 않았다. 이것도 결함으로 보고 함께 수리했다.

## 수리 (커밋 9dd64dc)

1. temp 저장 핸들러에 tempBetUsd 검증(0~100)·저장 추가. 저장 뒤 저장본을 다시 읽어 쓴 값 전부와 대조하고, 어긋나면 tempNoErr에 "저장이 확인되지 않았습니다"를 표시하고 카드를 잠그지 않는다 (설정 미적용 시 피드백 규칙).
2. 차선 현금 차단 가시화: 순수 판정 tempLaneCashBlocked (JS) / TempNo.laneCashBlocked (네이티브)를 추가하고, 드라이버가 해당 기온 후보를 탈락 사유 temp_cash_short("기온현금부족")로 집계해 사이클 요약에 남긴다. 차선 소진·포지션 상한은 헤더 로그 수치로 이미 보여 제외했다.
3. 부수 수리: native-golden3가 .52 사이징 전환 이후 깨져 있던 것 복구 (HEAD에서도 동일하게 실패함을 대조 확인). Golden3Test betUsdFor 서명, betUsd 픽스처의 금액 방식 전환, gen_expected 추출 목록·누락 헬퍼 보강, expected 재생성.

## 검증

- JS 전 슈트 통과: settings-lock 48 (신규: 기온 건당 금액 저장·유지 6건), temp (tempLaneCashBlocked 8건 + 구조 가드 2건 신규), bundle 10, html-guard 67, sellquote 30, report 47, positions 14, stoploss-fallback 21, relayer-auth 18, relayer-keycheck 30, book 통과.
- JVM: TempNo 158 (신규 8건), NativeCycle 356 통과.
- 골든: 1차 48/48, 2차 185, 3차 178, 4차 215 전부 통과.
- APK 빌드 완료 + DEX 게이트 PASSED. 주의: 검증 빌드가 로컬 android/boringmech-v0.3.52.apk를 덮어썼다 (git 미추적 파일). GitHub 릴리스 자산은 건드리지 않았다.

## 남은 것

- 출시 (버전 범프 포함)는 부모 결정 대기. 출시명은 미정 — 이 커밋은 버전 문자열을 올리지 않았다 (APP_VERSION 0.3.52 그대로).
- 출시 후에도 오늘 현금($3.57)으로는 기온 진입 불가 — 금액을 올려도 현금이 건당 금액 + 예비금 $2.87 이상 있어야 차선이 열린다. 이 상태는 이제 사이클 요약의 "기온현금부족" 탈락으로 보인다.
