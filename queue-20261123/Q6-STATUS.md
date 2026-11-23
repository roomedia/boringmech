# STATUS Q6: 기온 이벤트 조회 캐시 자정 경계 수리

- 상태: awaiting
- 시작: 2026-11-23
- 범위: 이벤트 조회 캐시가 UTC 자정을 넘길 때 전날 캐시가 오늘·내일 집합을 최대 15분 덮는 문제 수리. JS·네이티브 패리티, 가드 테스트, 번들 재생성, 커밋·푸시까지. 출시는 하지 않는다.
- 단계:
  - [완료] 캐시 코드 파악 (JS engine.js + 네이티브 TempNo/NativeCycle)
  - [완료] 수정: 캐시에 기준 UTC 날짜 키를 함께 저장하고, 날짜가 다르면 15분 안이어도 무효화 (JS tempFetchDayKey / 네이티브 TempNo.fetchDayKey, ISO 날짜 문자열로 패리티)
  - [완료] 가드 테스트: JS tempFetchDayKey 단위 4건 + 배선 구조 3건, 네이티브 NativeCycleTest.tempCacheExpiresAtUtcMidnight (자정 전후 재조회·새 집합 슬러그·같은 집합 캐시 유지)
  - [완료] 검증: JS book 458 · temp 161 · bundle 10, JVM NativeCycle 297 · TempNo 132 · ExternalWatchdog 15 · RestartGuard 7 · SubmitGuard 21 · StageDeadline 19, 골든 1차 38/38 · 2~4차 ALL PASS. 번들 재생성 완료 (APP_VERSION=0.3.39, 번들 안에 dayKey 저장·비교 로직 포함을 문자열로 확인)
  - [완료] 커밋 (roomedia 단독 저자) + 푸시
- 병렬 작업 메모: 실행 중 같은 트리에서 Q4 (c4c6fcb)와 temp-guarantee 작업이 같은 파일들을 건드렸다. Q6의 app/src/engine.js 변경은 c4c6fcb에 함께 실려 이미 커밋됐고 (HEAD 번들에도 dayKey 로직 포함 확인), 나머지 Q6 변경 (TempNo·NativeCycle·테스트 양쪽)은 헝크 단위로 분리해 이 항목 커밋에 따로 담았다. app/engine.js 작업 트리 번들은 temp-guarantee의 JS까지 포함하므로 그쪽 커밋이 번들을 함께 실어야 정합이 맞는다.
- 환경 메모: 검증 착수 시 ~/jdk-17/lib/modules 파일이 없어 JVM이 기동 불가 상태였다 (Failed setting boot class path). 공식 Temurin 17.0.21+9 tarball (gzip 무결성 확인, sha256 8b9b611d…)에서 lib/modules만 스트리밍 추출해 복원했고 java -version 정상 기동을 확인했다. /tmp tmpfs (512M)가 가득 차는 문제도 있어 추출은 홈 파일시스템으로 직접 했다.
  - [대기] 커밋 (roomedia 단독 저자) + 푸시
