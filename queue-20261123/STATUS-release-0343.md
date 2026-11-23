# v0.3.43 출시 상태

- 상태: awaiting (출시 완료, 설치는 동호 직접)
- 릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.43 (APK 첨부 확인)
- 출시 커밋: b737394 (roomedia 단독 저자, main 푸시 완료)
- 시작: 2026-11-24 01:05 KST
- 변경 1: 수동 매도 확인 팝업에 실제 매도가 (팝업 오픈 시점 주문장 최고 매수호가)와 예상 수령액 표시. 조회 실패 시 스냅샷가 참고가 + 체결가 변동 가능 문구, 매도는 막지 않음. 체결 로직 무변경.
- 변경 2 (범위 추가, 동호 확정): 주문 경로 측정 기능 제거. 설정 카드·측정 시작 버튼, bm_orderpath_test_req 소비 경로 (NativeCycle.processOrderPathTest), OrderPathTest 루틴, bm_orderpath_test_status 표시를 제거. ClobWriteClient의 createLimitOrder·cancelOrder·getOpenOrders (JS 패리티 포함)와 사이클 단계별 소요 기록은 유지.
- 단계:
  1. [완료] 코드 대조 (매도 팝업·주문장 조회 패턴·측정 기능 전 경로)
  2. [완료] 구현 + 가드 테스트
     - engine.js: bestBidFromBook·sellQuoteOf 헬퍼 신설 (BM 노출), 측정 섹션 (상수·헬퍼·run/process) + loop 호출 지점 + LS 키 제거
     - index.html: openSellPopup이 오픈 시점에 뷰어 fetch (AbortController 10초)로 /book 조회, 세대 가드로 stale 응답 차단, 실패 시 스냅샷가 폴백. 측정 카드·SET_GROUPS·상태 표시·핸들러 제거
     - 네이티브: NativeCycle 측정 블록 (533줄)·호출 지점·import 제거, OrderPathTest.java 삭제, NotifyPolicy 측정 알림 키 2개 제거
     - orderTestRounds 대조: JS·네이티브 파싱에만 남는 유산 필드로 유지 (읽는 곳 0건 grep 확정, 저장된 설정이 있어도 동작 변화 없음). UI 그룹만 제거
     - 테스트 정리: book.test 측정 블록 제거 (유산 파싱·단계 소요 8건 유지), sellquote.test.mjs 신설 30건, OrderPathFeatureTest → ClobWriteClientTest로 재구성 (유지 메서드 33건), NativeCycleTest 측정 44건 제거
  3. [완료] RELEASE-AUDIT 4축 감사 (회귀 중심) — 신규 발견 0건
     - 축1 주문: ClobWriteClient·JudgeFill diff 0, 수동 매도 제출 핸들러는 diff에 없음 (글자 단위 동일), 신규 호출은 읽기 전용 GET /book뿐
     - 축2 손절: NativeCycle diff는 import·측정 블록·호출 지점 3개 hunk뿐, 손절 경로 무변경
     - 축3 리딤·장부: 관련 파일 무변경. 측정 주문 자체가 사라져 장부 분리 코드 제거도 무영향
     - 축4 스냅샷·사이징·수명주기: 단계별 소요 기록 유지 (테스트 고정), 설정 유산 필드 무해 대조 완료
     - 구현 중 보강 1건: BM 헬퍼 호출 실패 시에도 팝업이 폴백으로 열리게 try/catch
  4. [완료] 버전 0.3.43 (versionCode 46) 범프, 번들 재생성, 전 슈트·골든
     - JS: book 466 · temp 183 · bundle 10 · html-guard 9 · sellquote 30
     - JVM: NativeCycle 315 · ClobWrite 33 · TempNo 150 · SubmitGuard 21 · Watchdog 35 · StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7
     - 골든: 1차 48/48 · 2차 200 · 3차 178 · 4차 215 ALL PASS (전부 v0.3.42와 동일)
  5. [완료] APK 빌드, DEX 게이트 PASSED, 축소 스모크 PASSED
     - 출하 APK 직접 대조: versionCode 46 / 0.3.43, assets에 측정 버튼·요청 키·루틴 잔존 0건, 실제 매도가 행 존재
  6. [완료] GitHub 릴리스 게시 (설치 없음)
- 미검증: 매도 팝업의 주문장 조회 표시는 실기기 미검증. 설치 후 첫 수동 매도 확인 팝업이 검증 지점이다.
