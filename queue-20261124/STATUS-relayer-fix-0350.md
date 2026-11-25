# v0.3.50 출시 상태 — relayer 401 정정 (b51b5fa)

- 상태: awaiting
- 시작: 2026-11-25 12:17 KST
- 포함: relayer 키 등록 시 Signer Address(EOA) 대조 검증, 제출 401 래치, 발급 안내 정정 (기커밋 b51b5fa)
- 절차: RELEASE-AUDIT 4축 감사 → 수정 → 버전 0.3.50 (versionCode 53) → 번들 → 전 슈트·골든 → APK 빌드 → DEX 게이트·축소 스모크 → GitHub 릴리스

## 감사 결과 (발견 0건)
- 주문·손절·스냅샷 축: .49 이후 diff는 b51b5fa와 README·RESULTS 자동 갱신뿐, 해당 경로 무변경.
- 리딤 축: 래치는 제출·조회 401 문구에서만 발동, 그 외 실패는 종전 건별 재시도 유지. 지문은 키+주소, 재등록·삭제 시 해제돼 자동 재개. 등록 검증은 연동 EOA를 알 때만 대소문자 무시 대조로 거부하고, 이미 저장된 키는 재검증하지 않는다. JS·네이티브 동일 의미 확인.

## 단계
- [x] 4축 감사 (리딤 축 집중: 래치 정상 제출 비차단, 등록 검증 유효 키 비거부)
- [x] 버전 범프 + 번들 재생성 (0.3.50, versionCode 53)
- [x] JS 전 슈트 894건 + JVM 624건 + 골든 1~4차 전부 통과
- [x] APK 빌드 + DEX 게이트 PASSED + 축소 스모크 PASSED (출하 APK 대조: versionCode 53, versionName 0.3.50, dex JavascriptInterface 존재)
- [x] GitHub 릴리스 게시: https://github.com/roomedia/boringmech/releases/tag/v0.3.50

## 검증 수치
- JS 전 슈트 894건 통과 (book 465 · temp 183 · bundle 10 · html-guard 67 · settings-lock 42 · sellquote 30 · report 44 · positions 14 · stoploss-fallback 21 · relayer-auth 18)
- JVM 624건 통과 (NativeCycle 344 · ClobWrite 33 · TempNo 150 · SubmitGuard 21 · Watchdog 35 · StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7)
- 골든 1~4차 전부 통과 (1차 48/48, 2~4차 ALL PASS)
- 미검증: 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 릴리스 노트에 명시. 리딤 실제 회수는 동호 키 재등록 후 다음 정산 건에서 확정된다.
