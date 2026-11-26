# 공리적 설계 분리 4단계 — 장부/스냅샷 단일 소유자 고정 (결합 4)

- 상태: awaiting (2026-11-27 출시 완료)
- 릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.60
- 출시 커밋: 80cf180 (roomedia 단독 저자), 푸시·자산 첨부 확인 완료.
  다운로드한 릴리스 자산이 출하 APK와 바이트 동일함을 대조했다.
- 남은 확인은 실기기 몫이다: 설치 후 서명 제출 1회 (이번 변경은 서명
  경로 무접촉이라 게이트 표기만 유지).
- 기준: 감사 원문 ~/workspace/audits-20261126/boringmech-axiomatic-audit.md 결합 4
- 현재 버전: v0.3.59 → v0.3.60 (versionCode 63)

## 현행 구조 (실측)

- JS engine.js: LS.bought 접근은 장부 클러스터(1640~1810) 안에만 있고
  LS.snap 접근은 cachedSnapshot·snapshot 안에만 있었다. 즉 엔진 내부
  규율은 이미 모여 있으나 소유자 선언과 가드가 없고, index.html이
  제보 payload를 만들 때 bm_bought·bm_snap을 localStorage에서 직접
  읽었다 (index.html:1764, 1766 + 주석 1759).
- snapshot() 취득은 JS 3회(runCycle 단계)·Java 4회로 단일 진입점
  (snapshot 함수/NativeCycle.snapshot)을 이미 통한다 — 3단계에서
  횟수·시점 고정済. 이번 단계에서 취득을 합치거나 줄이지 않았다.
- Java NativeCycle: "bm_bought" 리터럴 접근이 5곳에 흩어져 있었다 —
  boughtSlugsObjects(544), markBought(579), 기온 태그 판정(621),
  정화 되쓰기(1295), 손상 판정(1619). 순수 판정은 BoughtReconcile에
  이미 분리돼 있으나 저장소 접근 규율이 NativeCycle 곳곳에 있었다.
- ShadowSnapshot(EngineService)은 읽기 전용 대조 도구다 — GET만
  하고 장부를 건드리지 않으며 매매 판정에 닿지 않는다. 이번 단계
  범위 밖으로 뒀다.

## 구현 (완료)

- JS: 장부·스냅샷 구간을 소유자 구간으로 선언했다 (시작·끝 마커
  주석). 제보용 원문 읽기 readBoughtRaw·readSnapCacheRaw를 소유자
  구간 안에 신설해 window.BM으로 노출했다. 읽기 식은 종전 뷰어 식
  (localStorage 단독 파싱, 깨진 JSON은 던짐)을 글자 단위로 그대로
  옮겨 호출자의 try/catch 의미론까지 동일하다.
- index.html: 제보 payload의 snap 폴백과 bought 읽기를 BM 경유로
  교체했다. html 안에 bm_bought·bm_snap 문자열은 주석까지 포함해
  0건이다.
- Java: engine/BoughtLedger.java를 단일 소유자로 신설했다 —
  entries·entrySet·slugStrings·mark·corrupted·isTempTagged·
  writeEntries. 본문은 종전 NativeCycle 전용 메서드에서 그대로
  옮겼다 (mark의 집합 순회·중복 접기·쓰기 후 재읽기 검증·실패
  로그까지 동일). NativeCycle의 "bm_bought" 리터럴과 전용 private
  메서드 3개를 제거하고 전 호출 지점 12곳을 소유자로 재배선했다.
- 스냅샷은 양쪽 모두 취득 진입점이 이미 단일이라 본문을 옮기지
  않았다 — 소유자 선언과 가드로 고정만 했다. 근거: 감사 방향의
  핵심은 "호출 규율(직접 접근)을 끊는 것"이고, 취득 본체 이동은
  골든·슬라이스 가드(snapshot~verifyCandidate 구간 단언)와
  충돌하는 고위험 저효익 변경이다.

## 절대 조건 (준수)

- 거동 동일 리팩터. 스냅샷 취득 시점·횟수(JS 3회·Java 4회, grep
  대조), 장부 기록·정화 결과, 리딤 판정 입력, 판단 수치, 로그
  문자열 무변경.
- 구조 가드 신설: JS ledger-owner.test.mjs (index.html 키 문자열
  0건, engine.js의 LS.bought·LS.snap 출현이 소유자 구간 안에만
  있음, 원문 읽기와 종전 식의 전 형태 동등성·동반 던짐),
  Java BoughtLedgerTest (거동 26건 + NativeCycle 소스 가드 8건).

## 검증 수치

- 전면 감사 4축 발견 0건. 축 1·2·3: 델타 헝크가 소유자 선언·원문
  읽기 신설·BM 노출·index.html payload 재배선·BoughtLedger 이동과
  그 호출 지점에만 있음을 diff로 확인 — 제출·서명·손절·리딤 본문
  무접촉, 제출 직전 장부 재확인 위치 동일. 축 4: 스냅샷 취득
  횟수·시점 무변경, 장부 파싱 입력 동일.
- JS 전 슈트 88,980건 통과 (기존 88,949 + ledger-owner 31)
- JVM 106,912건 통과 (기존 106,878 + BoughtLedger 34)
- 골든 전부 통과 (48/48 · 185 · 178 · 215)
- APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED. 출하 APK
  직접 대조: versionCode 63, versionName 0.3.60, dex에
  JavascriptInterface·engine/BoughtLedger 존재, assets 번들이
  테스트한 번들과 바이트 동일(md5).

## 미검증

- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉).
