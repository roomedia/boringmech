# Q14 상태: 상한 근처 탈락 가격 쌍 로깅 보강

- 상태: awaiting
- 시작: 2026-11-23
- 범위: 기온 후보가 ask_out_of_band로 탈락하고 bestAsk가 밴드 상한에서 0.01 이내일 때만 스냅샷가·bestAsk를 로그 한 줄로 기록. 메인 후보·그 외 탈락은 카운트만 유지. JS·네이티브 패리티, 가드 테스트 양쪽, 전 슈트, 번들 재생성, 커밋·푸시까지. 출시 없음.
- 단계:
  - [완료] 탈락 판정 코드 위치 확정 (JS verifyCandidate, 네이티브 CandidateVerifier)
  - [완료] 로깅 보강 구현 (양쪽 패리티): 순수 판정 함수 JS tempNearCapRejectNote / 네이티브 TempNo.nearCapRejectNote (여유 0.01). 검증기가 ask_out_of_band 탈락에 메모를 붙이고 (Result.note / r.note), 사이클 드라이버가 탈락 집계 뒤 메모가 있을 때만 로그 한 줄 출력. 조건: 기온 후보 + bestAsk > 밴드 상한 + 초과분 0.01 이내 (+1e-9 허용). 하한 아래 탈락·메인 후보·그 외 사유는 메모 없음.
  - [완료] 가드 테스트: JS temp 단위 8건 (경계 0.01 정확히 포함, 0.05 초과·하한 아래·메인·NaN 무메모) + 구조 가드 3건. 네이티브 TempNoTest 검증기 경유 7건 (0.999/0.998 메모 문구 일치, 메인 무메모, gap 0.07 무메모, 하한 아래 무메모, 단위 경계 3건).
  - [완료] 전 슈트 통과: JS book 458 · temp 183 · bundle 10. JVM NativeCycle 315 · TempNo 150 · ExternalWatchdog 15 · RestartGuard 7 · SubmitGuard 21 · StageDeadline 19. 골든 1차 38/38 · 2~4차 ALL PASS. 번들 재생성 (APP_VERSION=0.3.40), 출하 번들에 메모 문구 (유니코드 이스케이프 형태)와 note 배선 포함 확인, 번들 테스트 재생성 후 재통과.
  - [완료] 커밋 + 푸시 (본 상태 파일의 커밋 해시는 커밋 메시지 기준, main 푸시)
- 비고: 출시는 하지 않았다. 다음 출시 때부터 폰 로그에서 "기온 상한 근처 탈락 <slug> · 스냅샷 <가> · bestAsk <가> · 밴드 상한 <가>" 한 줄로 상한 구조를 직접 확인할 수 있다.
