# v0.3.53 출시 — 기온 금액 저장 수리

상태: awaiting (출시 완료, 설치는 동호)

## 포함 내용 (기커밋 9dd64dc)
- 기온 카드 저장 핸들러의 tempBetUsd 쓰기 누락 수리
- 저장 후 재읽기 대조 검증 (어긋나면 "저장이 확인되지 않았습니다" 표시, 카드 잠금 보류)
- 차선 현금 차단을 temp_cash_short 사유로 집계해 사이클 요약에 표시
- native-golden3 복구 (같은 커밋)

## 감사 결과 (4축)
- 설정 저장 경로 전수 대조: UI 컨트롤 id 집합 = 필드 목록 (ts 19, temp 7) 정확히 일치.
  temp 카드는 전 필드 기록 + 재읽기 대조 확인. band/size/stop은 saveTradeGroup가
  필드 목록을 순회해 전 필드를 쓰므로 누락 패턴 없음. JS·네이티브 파서가 저장 키를
  전부 읽음 (패리티 일치).
- 발견 1건 수리: 매매 그룹 저장에는 재읽기 대조가 없어, writeSettingsObject가
  setItem 예외를 삼키면 조용히 미적용인 채 카드가 잠기던 경로. temp 카드와 같은
  재읽기 대조를 saveTradeGroup에 추가하고 가드 테스트 8건 신설
  (settings-lock 48 → 56건 통과).
- 주문·손절·리딤 축: 이번 diff가 설정 저장 경로로 한정됨을 대조해 무변경 확인.

## 단계
- [x] 전면 감사 4축 (설정 저장 경로 전수 대조 중심) — 발견 1건 수리 완료
- [x] 버전 범프 0.3.53 (versionCode 56)
- [x] 번들 재생성
- [x] 전 슈트·골든
- [x] APK 빌드·DEX 게이트·축소 스모크
- [x] 커밋·푸시·GitHub 릴리스 게시 — 출시 커밋 366d820 (수리 9dd64dc 포함해 main 푸시 완료), 릴리스 https://github.com/roomedia/boringmech/releases/tag/v0.3.53 (자산 boringmech-v0.3.53.apk 첨부 확인)

## 검증 수치
- JS 전 슈트 959건 통과 (book 468 · temp 198 · bundle 10 · html-guard 67 · settings-lock 56 · sellquote 30 · report 47 · positions 14 · stoploss-fallback 21 · relayer-auth 18 · relayer-keycheck 30)
- JVM 644건 통과 (NativeCycle 356 · TempNo 158 · ClobWrite 33 · SubmitGuard 21 · Watchdog 35 · StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7)
- 골든 전부 통과 (48/48 · 185 · 178 · 215)
- APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED. 출하 APK 직접 대조: versionCode 56, versionName 0.3.53, dex에 JavascriptInterface 존재.

## 미검증
- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다 (이번 변경은 서명 경로 무접촉 — 설정 저장 경로와 사이클 요약 집계만 변경).
