# v0.3.54 출시 — 도시 점수 섀도우 기록

상태: running (로컬 완료, 푸시·릴리스만 VM 출구 장애로 보류)

## 보류 사유 (2026-11-26 00:5x KST)
- 출시 커밋 a5da920까지 로컬 main에 완료. git push가 "Proxy CONNECT
  aborted"로 2회 실패 (간격 재시도 포함). VM 출구 경로 장애가 다른
  작업에서도 전 호스트 타임아웃으로 확인되는 중이라 재시도를 멈췄다.
- 남은 단계는 둘뿐이다: git push origin main, gh release create
  v0.3.54 (자산 android/boringmech-v0.3.54.apk). 출구가 복구되면
  그대로 이어서 게시하면 된다.

## 배경
- 도시×시간 연구(citytime-sort-20261126.md) 판정: "섀도우 기록만 가치 있음".
  축소 점수에서 버킷 편차 0 (tau2=0)이라 점수는 사실상 도시 평균.
  정렬 키 정본: queue-20261124/citytime_sort_keys.json (40개 도시).
- 거래 동작은 일절 바꾸지 않고 기록만 한다.

## 기록 사양
- 기온 사이클이 진입 후보를 평가할 때마다 그 사이클의 기온 후보 전체를 기록:
  후보별 slug·도시·현지 시각 버킷·진입가·점수·점수 순위·실제 진입 여부,
  사이클 시점 현금·슬롯 상태.
- 하루(기온 이벤트 날짜 기준) 단위 롤업: 상한 1·2·3건 섀도우 픽을 실제
  진입 목록과 나란히, slug 포함.
- 점수 조회는 연구 스코어러와 같은 규칙: (도시,버킷) 셀 → 도시 평균 →
  없으면 무점수(중립, 순위 뒤, 기록에 무점수 표시).
- 점수표는 엔진별 한 곳(JS 상수·네이티브 클래스)에 데이터로 내장, 패리티
  테스트로 일치 강제.
- 저장: durable 키 bm_shadow (bm_durable 체계), 바이트 상한으로 오래된
  사이클 기록부터 버리고 일별 롤업은 14일 보관. 제보 payload.shadow로 회수.
- 기록 실패는 거래를 막지 않는다 (try/catch 격리, 그 사이클만 건너뜀).

## 감사 결과 (4축)
- 주문 실행: diff는 주문 구성·제출·잠금·가격 계산을 건드리지 않는다.
  추가분은 선정 패스 전후의 매수 기록 읽기(읽기 전용)와 패스 종료 후
  try/catch 격리된 기록뿐 (JS·네이티브 diff 대조).
- 손절: 손절은 스캔 전에 끝나고 기록은 매수 드라이버 뒤에 붙는다.
  손절 키·판정 무변경.
- 리딤·정화·장부: 섀도우 저장소는 전용 키 bm_shadow만 쓴다. bought는
  읽기만 하고 진입 판정은 전후 차이로만 계산한다. 리딤·정화 무변경.
- 스냅샷·사이징·수명주기: 저장소 손상 시 빈 저장소로 떨어지고, 보관
  상한(256KB·400사이클·14일)이 매 붙이기마다 강제돼 저장소 고갈이
  다른 키 쓰기를 막을 수 없다. 중단된 사이클은 양 엔진 모두 기록하지
  않아 패리티가 맞고, Intl·ZoneId 실패 시 버킷 null로 기록만 남는다.
- 발견 1건 수리: 저장소에 null 항목이 섞이면 JS 날짜 정렬이 던져 그
  사이클 기록이 막히던 경로. parseShadowStore가 객체 아닌 항목을
  양 엔진 모두 거르게 하고 가드 테스트를 양쪽에 추가했다.

## 단계
- [x] 설계·구현 (JS engine.js + index.html 제보 + 네이티브 ShadowLog/NativeCycle)
- [x] 테스트 (JS 신규 슈트 + JVM 신규 슈트 + 패리티)
- [x] 전면 감사 4축 (RELEASE-AUDIT) — 발견 1건 수리 완료
- [x] 버전 범프 0.3.54 (versionCode 57)
- [x] 번들 재생성 (APP_VERSION=0.3.54)
- [x] 전 슈트·골든
- [x] APK 빌드·DEX 게이트·축소 스모크
- [ ] 커밋·푸시·GitHub 릴리스 게시 — 커밋 a5da920 완료, 푸시·릴리스는
  출구 장애로 보류 (위 보류 사유 참조)

## 검증 수치
- JS 전 슈트 1,043건 통과 (book 468 · temp 198 · bundle 10 · html-guard
  67 · settings-lock 56 · sellquote 30 · report 47 · positions 14 ·
  stoploss-fallback 21 · relayer-auth 18 · relayer-keycheck 30 ·
  shadow 84 신규)
- JVM 977건 통과 (NativeCycle 356 · TempNo 158 · ClobWrite 33 ·
  SubmitGuard 21 · Watchdog/StageDeadline 54 · ExternalWatchdog 15 ·
  RestartGuard 7 · ShadowLog 333 신규, JS 내장 표와의 패리티 포함)
- 골든 전부 통과 (48/48 · 185 · 178 · 215)
- APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED. 출하 APK 직접
  대조: versionCode 57, versionName 0.3.54, dex에 JavascriptInterface·
  ShadowLog·bm_shadow 존재.

## 미검증
- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다 (이번
  변경은 서명 경로 무접촉 — 기록 경로만 추가).
- 섀도우 기록의 실기기 축적 확인은 설치 후 첫 기온 사이클 제보로 한다.
