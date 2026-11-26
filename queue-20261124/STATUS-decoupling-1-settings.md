# 공리적 설계 분리 1단계 — 설정 네임스페이스 분리·파서 단일화

상태: awaiting (2026-11-26 출시 완료)
릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.57
출시 커밋: 61e9827 (roomedia 단독 저자), 푸시·자산 첨부 확인 완료.
남은 확인은 실기기 몫이다: 설치 후 서명 제출 1회 (이번 변경은 서명
경로 무접촉이라 게이트 표기만 유지).
감사 원문: ~/workspace/audits-20261126/boringmech-axiomatic-audit.md 결합 1

## 현행 구조 (실측)

- JS: engine.js parseTradeSettings(672)·parseTempSettings(746)가 bm_settings
  단일 블롭을 나눠 파싱. settingsObject·strictNum 공유.
- index.html: readSettingsObject(원본 블롭 읽기) + readTempSettings(자체
  파서, Number() 강제변환 — 정본과 의미 다름) + groupSavedValues의
  필드별 Number/fieldValid 해석(쌍 리셋 없음).
- Java: TradeSettings.parse(매매) + TempNo.parseSettings(기온) — 파서 2개.

## 목표 구조

- JS: parseSettings(raw) 단일 정본 파서. 반환은 FR 네임스페이스
  (scan/sizing/stoploss/temp)로 나뉜 동결 객체 + orderTestRounds(유산).
  parseTradeSettings·parseTempSettings는 정본 위의 투영 뷰로 축소.
- Java: engine/Settings.java 단일 정본 파서(네임스페이스 뷰 보유).
  TradeSettings.parse·TempNo.parseSettings는 투영으로 축소.
- index.html: readTempSettings·groupSavedValues의 자체 해석을 지우고
  BM 정본 뷰(parseTradeSettings·parseTempSettings)를 거치게 한다.
  저장 경로(writeSettingsObject 병합 + 전 필드 쓰기 + 재읽기 대조)는 유지.

## 단계

- [x] 픽스처 기준선: 구 파서(JS) 출력 고정 + 구 Java와 대조해 기존
      JS↔Java 어긋남을 먼저 가려낸다 — 픽스처 514건
      (app/test/fixtures/settings-parity.json), 구 Java 대조 514/514
      일치, 어긋남 0건. 기대값 하나로 양쪽을 검증할 수 있다.
- [x] JS 정본 파서 + 뷰 투영 + 패리티 테스트 — parseSettings 신설
      (scan/sizing/stoploss/temp 동결 네임스페이스), parseTradeSettings·
      parseTempSettings는 투영 뷰로 축소, effectiveSettingsSummary도
      정본 1회 해석으로. 패리티 2,576건 통과 (settings-parity.test.mjs,
      npm test에 등록).
- [x] Java Settings 정본 + 투영 + 패리티 테스트 — engine/Settings.java
      신설, TradeSettings.parse·TempNo.parseSettings는 투영으로 축소,
      NativeCycle은 사이클당 정본 1회 해석 + 투영 2개로. 패리티
      20,560건 통과 (SettingsTest, run_settings_test.sh).
- [x] index.html 정본 경로 전환 — readTempSettings 자체 해석 삭제,
      groupSavedValues 매매 채우기도 BM.parseTradeSettings 실효값으로.
      저장 경로(writeSettingsObject 병합·이중 쓰기·재읽기 대조)는 무변경.
- [x] 전면 감사 4축 (RELEASE-AUDIT) — 발견 0건. 축 1·2·3: 델타 헝크가
      engine.js 설정 구간(613~856행 + BM 내보내기 2줄), index.html
      설정 표시 2곳, NativeCycle 파싱 3줄에만 있음을 diff로 확인 —
      주문·손절 실행·리딤 경로는 무접촉. 축 4: 사이징 입력 5필드와
      손상 블롭의 기본값 폴백이 픽스처로 고정됐고, 정본 결과의 동결
      객체는 소비자에게 닿지 않는다(뷰는 매번 새 객체).
- [x] 전 슈트·골든·번들 — JS 3,645건, JVM 1,013건 + 패리티
      20,560건, 골든 48/185/178/215 전부 통과 (번들은 범프 후 재생성).
- [x] 버전 범프 0.3.57 (versionCode 60) + 번들 재생성 + APK 빌드·
      DEX 게이트 PASSED·축소 스모크 PASSED. 출하 APK 직접 대조:
      versionCode 60, versionName 0.3.57, dex에 JavascriptInterface·
      engine/Settings 존재, 번들에 APP_VERSION 0.3.57·parseSettings 존재.
- [x] 커밋·푸시·릴리스 — 61e9827, 릴리스 자산 boringmech-v0.3.57.apk
      (356,904B, uploaded 상태 확인)

## 검증 수치

- JS 전 슈트 3,645건 통과 (book 468 · temp 224 · bundle 10 ·
  html-guard 67 · settings-lock 56 · sellquote 30 · report 47 ·
  positions 14 · stoploss-fallback 21 · relayer-auth 18 ·
  relayer-keycheck 30 · shadow 84 · settings-parity 2,576)
- JVM 21,573건 통과 (NativeCycle 375 · TempNo 175 · ClobWrite 33 ·
  SubmitGuard 21 · ShadowLog 333 · WatchdogLogic 35 ·
  StageDeadline 19 · ExternalWatchdog 15 · RestartGuard 7 ·
  Settings 패리티 20,560)
- 골든 전부 통과 (48/48 · 185 · 178 · 215)
- 패리티 대조: 픽스처 514건 — JS 2,576건 + Java 20,560건, 어긋남 0

## 미검증

- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉 — 설정 해석 단일화만).

## 어긋남 기록 (픽스처 대조에서 확정된 것만)

- 엔진 해석(JS·Java): 어긋남 0건. 픽스처 514건 전수에서 구 파서와
  신 정본·투영이 동일 (JS 2,576건, Java 20,560건 대조).
- index.html 표시 전용 어긋남 5건 — 전부 비정상 블롭에서만, 새 표시가
  엔진이 원래 쓰던 실효값과 일치하는 방향이다. 매매 판단은 무변경.
  1. tempNoBandHi: true — 구 UI 표시 1 → 엔진 실효 0.998
  2. tempNoBandLo: [0.9] — 구 UI 표시 0.9 → 엔진 실효 0.8 (기본값)
  3. tempEntryHourUtc: true — 구 UI 표시 1 → 엔진 실효 6 (기본값)
  4. tempBetUsd: [6] — 구 UI 표시 6 → 엔진 실효 5 (기본값)
  5. 매매 폼 채우기 쌍 리셋 — {bandLo 0.95, bandHi 0.9}를 구 UI는
     0.95/0.9로 채웠으나 엔진은 쌍 리셋으로 0.6/0.9를 써 왔다.
     새 채우기는 0.6/0.9 (엔진 실효값).
  정상 블롭(숫자·숫자 문자열·유효 범위 값)은 표시도 전부 동일함을
  같은 대조로 확인했다.
