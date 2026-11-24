# v0.3.46 출시 상태 (설정 카드별 편집 잠금)

상태: awaiting (릴리스 게시 완료, 설치는 동호 몫)

## 변경 내용
- v0.3.45의 설정 전체 일괄 잠금 (기온 카드의 편집 아이콘 1개로 전 카드 해제)을 카드별 편집으로 교체
- 설정 카드 4개 (기온, 매수·검증, 사이징, 손절)마다 카드 안에 편집 아이콘 (btnEditTemp·btnEditBand·btnEditSize·btnEditStop)을 두고, 그 카드의 필드·스테퍼·저장 버튼만 연다
- 잠금 상태는 카드별 독립 (cardLocked): 한 카드를 열어도 다른 카드는 disabled 유지, 카드 여럿 동시 열림 허용
- 그 카드의 저장 (saveTradeGroup·기온 저장) 또는 되돌리기 (revertGroup)가 끝나면 그 카드만 재잠금 (setCardLocked(name, true))
- 숫자 입력 시트도 카드별 판정: groupOfInputId로 입력이 속한 카드를 찾아 그 카드가 잠겨 있으면 열지 않는다
- 잠금·해제 시 레이아웃 불변 (컨트롤 disabled 토글만, 요소 숨김·대체 없음) 유지
- 매매 동작·설정값 해석 무변경

## 감사 (RELEASE-AUDIT 4축, diff 대조)
- 변경 파일은 app/index.html (뷰어 잠금 로직·마크업), 테스트 3종, 버전 파일뿐. engine.js는 번들 재생성으로 버전 상수 0.3.45→0.3.46만, 네이티브·src 무변경
- 주문 실행: 제출·서명 경로 무변경
- 손절: 판정·실행 무변경. 설정값 해석 경로 (bm_settings) 무변경, 잠금은 뷰어 컨트롤 조작 가능 여부만 바꾼다
- 리딤·정화·장부: 계산·표시 무변경
- 스냅샷·사이징·수명주기: 계산 무변경
- 발견 0건

## 검증
- JS 전 슈트 829건: book 465 · temp 183 · bundle 10 · html-guard 55 (카드별 편집 아이콘·재잠금 지점 가드 갱신) · settings-lock 42 (카드별 독립 잠금으로 재작성: 한 카드를 열어도 다른 카드는 disabled 유지, 카드 저장 시 그 카드만 재잠금, 동시 열림, 카드별 숫자 시트 차단) · sellquote 30 · report 44
- JVM 598건: NativeCycle 318 · ClobWrite 33 · TempNo 150 · SubmitGuard 21 · Watchdog 35 · StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7
- 골든 1~4차 전부 통과 (48 · 185 · 178 · 215)
- APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED
- 출하 APK 직접 대조: versionCode 49 · versionName 0.3.46, dex에 JavascriptInterface 존재

## 미검증
- 서명 경로는 이번 변경과 무관하나, 출하본 기준 실기기 서명 제출 확인 전까지는 서명 경로 실기기 미검증 표기를 유지한다
- 카드별 잠금의 실기기 표시 확인은 설치 후 동호 확인 몫
