# STATUS: Q4 연동 화면 하단 요약의 설정 반영

- 상태: awaiting
- 시작: 2026-11-23
- 범위: 연동 화면 하단 요약 문구가 기본값으로 고정 표기되는 문제를, 저장된 설정(bm_settings)의 TradeSettings 해석 결과로 렌더하게 고친다. 출시 없음.
- 전제 대조:
  - 요약은 app/index.html footer 정적 문구. 네이티브(android/src)에는 같은 문구가 없어 패리티 영향 없음.
  - 해석 정본은 app/src/engine.js parseTradeSettings. 뷰어에서는 window.BM 경유로 쓴다 (기존에는 BM에 미노출).
- 단계:
  - [완료] 구현: engine settingsSummaryText 추가 + BM 노출, index.html 렌더 연결
  - [완료] 가드 테스트 (기본값 렌더 일치, 설정 변경 반영)
  - [완료] 검증: JS 슈트, 번들 재생성
  - [완료] 커밋 (roomedia 단독 저자) + 푸시

## 구현

- app/src/engine.js: 순수 함수 settingsSummaryText(cfg) 추가. parseTradeSettings 해석 결과로 요약을 만들고, 기본값이면 기존 고정 문구와 글자까지 같다. 기온 값은 요약에 넣지 않았다. window.BM에 parseTradeSettings와 settingsSummaryText를 노출했다.
- app/index.html: footer 첫 줄을 span#settingsSummary로 감싸고 renderSettingsSummary()가 저장본(readSettingsObject)을 해석해 다시 그린다. refresh의 메인·연동 양쪽 경로와 매매 설정 저장 직후에 호출한다. 폼의 미저장 변경이 아니라 저장된 설정을 기준으로 한다.

## 검증

- JS: book 458 (신규 §47 가드 13건: 기본값=기존 문구 일치, 변경 반영, 소수 퍼센트 표기, 폴백 반영, BM 경로, 구조 가드) · temp 161 · bundle 10 (신규 4건: 출하 번들에서 해석·렌더 경로 실재와 기본값·변경 렌더 확인) — 전부 통과
- 번들 재생성 완료 (APP_VERSION=0.3.39)
