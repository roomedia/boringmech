# v0.3.44 출시 상태

- 상태: running
- 변경: 제보 payload에 settings 키 첨부 (bm_settings 실효값 전체, 엔진과 같은 원본)
- 단계:
  1. [완료] 코드 대조 — sendReport (index.html:1446), 설정 원본 경로 readSettingsObject (localStorage → AndroidBridge.getDurable('bm_settings'), 설정 화면과 동일), 엔진 해석 BM.parseTradeSettings + 기온은 parseTempSettings (BM 미노출이라 뷰어 readTempSettings와 규칙 대조 완료)
  2. [완료] 구현 + 가드 테스트
     - src/engine.js: effectiveSettingsSummary(raw) 신설 — parseTradeSettings + parseTempSettings를 저장 키 이름의 평면 객체로 병합, window.BM 노출
     - index.html: sendReport가 payload.settings에 첨부. try/catch 격리라 파싱 실패 시 settings만 빠지고 제보 본체 (트리거·재시도·엔드포인트)는 무변경
     - app/test/report.test.mjs 신설 (실효값 반영·필수 키·기본값 폴백·깨진 입력 무예외·배선 정적 대조), package.json test 스크립트에 편입
  3. [완료] RELEASE-AUDIT 4축 감사 (회귀 중심)
     - 축1 주문: 변경 파일은 src/engine.js (순수 함수 추가)·번들·index.html 제보 블록·테스트·버전뿐. 주문 제출 경로 diff 0
     - 축2 손절: 손절 판정·실행 경로 무변경. tempStopDrop은 읽기 전용으로 요약에만 실림
     - 축3 리딤·정화·장부: 관련 파일 무변경. 제보 소비 쪽 (tools/report_check.py)은 payload를 통째로 읽어 추가 키 무해
     - 축4 스냅샷·사이징·수명주기: 설정 첨부는 동기 파싱 1회, 실패는 try/catch로 격리돼 제보 전송 자체를 막지 않음. payload 증가는 수치 20여 개로 무시 가능. 신규 발견 0건
  4. [완료] 버전 0.3.44 (versionCode 47) 범프, 번들 재생성, 전 슈트·골든
     - JS: book 466 · temp 183 · bundle 10 · html-guard 9 · sellquote 30 · report 44 (신설)
     - JVM: NativeCycle 315 · ClobWrite 33 · TempNo 150 · SubmitGuard 21 · Watchdog 35 · StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7 (전부 v0.3.43과 동일)
     - 골든: 1차 48/48 · 2차 200 · 3차 178 · 4차 215 ALL PASS (전부 v0.3.43과 동일)
  5. [완료] APK 빌드, DEX 게이트 PASSED, 축소 스모크 PASSED
     - 출하 APK 직접 대조: versionCode 47 / 0.3.44, assets/index.html에 payload.settings 배선 존재, assets/engine.js에 effectiveSettingsSummary 존재
  6. [진행] GitHub 릴리스 게시 (설치 없음)
- 미검증: 제보 settings 첨부는 실기기 미검증. 설치 후 첫 제보에 settings 키가 실리는지가 검증 지점이다. 서명 경로는 이번 변경과 무관하다 (주문·서명 코드 무변경).
