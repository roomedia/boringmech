# STATUS: v0.3.39 출시

- 상태: running
- 시작: 2026-11-23
- 범위: 출시 전 전면 감사 4축 → 수리·가드 테스트 → 버전 범프 → 검증 전수 → 커밋·태그·GitHub 릴리스. 서명 경로 실기기 검증은 동호 몫이라 미검증 표기를 유지한다.
- 변경분: 설정 화면 UX 수정 (3ef273f), 기온 예약 슬롯 구멍 수리 (1e8f82d)
- 단계:
  - [완료] 1. 전면 감사 4축 — 확정 발견 6건 (축2는 0건)
    - A (축1) 매수 지정가를 bandHi로 절단하면 틱 유효성을 깨 기온 상단 후보가 서명 단계에서 전건 실패
    - B (축1, 경미) 수동 매도가 사이클 단일 파싱 원칙을 어기고 설정을 재파싱
    - C (축3) 리딤 pending의 status가 최초 수집값에 고정돼 패→승 전이한 승자가 worthless로 영구 종결
    - D (축3+축1) hasUpcomingWindow가 markets를 안 봐 후보 불가능 이벤트에도 예약이 걸림
    - E (축4) 기온 이벤트 조회 전수 실패 시 예약이 무로그로 조용히 풀림 (fail-open)
    - F (축4) 숫자 직접 입력 칸을 비우면 0으로 저장됨 (volumeMin 0 = 필터 무력화)
    - 경화 1건: JS runRedeems에 네이티브에만 있는 worthless streak 정산 조회 가드 패리티 추가
  - [완료] 2. 감사 발견 수리 + 가드 테스트 (6건 + 경화 1건, JS·JVM 전 슈트 통과)
  - [완료] 3. 버전 범프 0.3.39 (version-code 42) + 번들 재생성 (APP_VERSION=0.3.39)
  - [완료] 4. 검증 전수 통과: JS book 445·temp 146·bundle 6, JVM NativeCycle 276·TempNo 126·ExternalWatchdog 15·RestartGuard 7·SubmitGuard 21·WatchdogLogic 35·StageDeadline 19, 골든 1차 38/38·2차 200·3차 178·4차 215, APK 빌드 + DEX GATE PASSED, 축소 스모크 PASSED
  - [진행] 5. 커밋·태그·GitHub 릴리스
