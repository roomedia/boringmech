# STATUS tempBetUsd 진단

- 상태: running
- 시작: 2026-11-25
- 과제: v0.3.52 기온 건당 금액(tempBetUsd)을 올리고 저장했는데 계속 $5로 보인다는 보고의 원인 특정. 출시 금지, 진단+수리 준비까지.

## 단계
- [ ] 현금 재구성 재확인 (data-api positions·value) — v2로 조회 중
- [x] 경로 1: UI 입력 — 스테퍼(index.html:253-258)·숫자 시트(1408-1410)는 hidden input만 바꾼다. 저장은 btnTempSave(261행, dirty일 때만 활성) 필요. dirty 판정·점 표시는 동작함.
- [x] 경로 2: 저장 — **결함 확정.** btnTempSave 핸들러(index.html:1806-1853)의 writeSettingsObject가 tempNoEnabled·tempNoBandLo·tempNoBandHi·tempEntryHourUtc·tempStopDrop·tempNoReserveSlots 6개만 쓰고 **tempBetUsd가 누락**됐다. 저장 직후 syncGroupForm('temp', true)가 저장본(기본값 5)으로 폼을 되돌려, 사용자는 값이 5로 되돌아가는 것을 본다.
- [x] 경로 3: 엔진 소비 — 정상. JS parseTempSettings(src/engine.js:776)·UI readTempSettings(1027)·네이티브 TempNo.parse(TempNo.java:400) 모두 tempBetUsd를 읽고, NativeCycle:1683은 tempSettings.betUsd를 후보별 금액으로 쓴다. 값이 bm_settings에 들어간 적이 없어 전 경로가 기본값 5로 떨어진 것.
- [x] 경로 4: 표시 — 홈 요약 settingsSummaryText(engine.js:733)는 설계상 메인 betUsd만 표기(기온 값 제외 명시). 사이클 머리 로그(2457행)도 메인 snap.bet만. 착시를 키우지만 주원인은 경로 2.
- [ ] 판정 + 수리 준비
