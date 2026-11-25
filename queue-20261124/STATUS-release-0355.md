# v0.3.55 출시 상태

상태: running (2026-11-26 착수)
포함: v0.3.54 섀도우 기록 (a5da920) + 기온 예약 홀드 수리 (0636794)
주의: v0.3.54 GitHub 릴리스는 따로 만들지 않는다. .55가 대체한다.

## 단계
- [x] 전면 감사 4축 (RELEASE-AUDIT) — 발견 0건. 축 1·2·3은 델타
  (0636794·a5da920)가 제출 잠금·손절·리딤 경로 무접촉임을 diff로 확인.
  축 4는 수리 본체 대조: JS·네이티브 패리티, 부분 실패 결과의 캐시
  (15분)도 실패 건수를 실어 홀드 유지가 이어지는 것, 빈 응답 실패
  카운트의 트레이드오프(도시 슬러그 소멸 시 홀드 상시 유지)는 수리
  커밋·진단 문서에 이미 명시된 수용 사항이고 사유 로그가 남는다.
- [x] 버전 범프 0.3.55 (versionCode 58)
- [x] 번들 재생성 (APP_VERSION=0.3.55)
- [x] 전 슈트·골든
- [x] APK 빌드·DEX 게이트·축소 스모크
- [ ] 커밋·푸시·GitHub 릴리스 게시

## 검증 수치
- JS 전 슈트 1,048건 통과 (book 468 · temp 203 · bundle 10 ·
  html-guard 67 · settings-lock 56 · sellquote 30 · report 47 ·
  positions 14 · stoploss-fallback 21 · relayer-auth 18 ·
  relayer-keycheck 30 · shadow 84)
- JVM 988건 통과 (NativeCycle 367 · TempNo 158 · ClobWrite 33 ·
  SubmitGuard 21 · ShadowLog 333 · Watchdog/StageDeadline 54 ·
  ExternalWatchdog 15 · RestartGuard 7)
- 골든 전부 통과 (48/48 · 185 · 178 · 215)
- APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED. 출하 APK 직접
  대조: versionCode 58, versionName 0.3.55, dex에 JavascriptInterface·
  ShadowLog·bm_shadow·홀드 수리 로그 문자열 존재, 번들 engine.js에
  APP_VERSION 0.3.55와 홀드 수리 로그 문자열(\u 이스케이프) 존재.

## 미검증
- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉 — 섀도우 기록 + 홀드 판정만).
- 섀도우 기록의 실기기 축적 확인은 설치 후 첫 기온 사이클 제보로 한다.
