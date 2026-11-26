# 공리적 설계 분리 7단계 — 섀도우 입력 계약 명시 (결합 7)

- 상태: awaiting (2026-11-27 출시 완료)
- 릴리스: https://github.com/roomedia/boringmech/releases/tag/v0.3.62
- 출시 커밋: f2e91a5 (roomedia 단독 저자), 푸시·자산 첨부 확인 완료.
  다운로드한 릴리스 자산이 출하 APK와 바이트 동일함을 대조했다
  (md5 2e75e76799c064792c959462e63ddf5c, 자산 356,904B uploaded).
- 남은 확인은 실기기 몫이다: 설치 후 서명 제출 1회 (이번 변경은 서명
  경로 무접촉이라 게이트 표기만 유지).
- 기준: 감사 원문 ~/workspace/audits-20261126/boringmech-axiomatic-audit.md 결합 7
- 현재 버전: v0.3.61 → v0.3.62 (versionCode 65)

## 현행 구조 (실측)

- JS: buildShadowCycleRecord(engine.js:1349)가 스캔 후보 객체를 그대로
  받아 slug·endDate·price만 읽는다. 후보의 나머지 필드(question·side·
  strategy·bandLo·bandHi·feeRate)는 섀도우가 쓰지 않는데도 스캔 내부
  형태가 바뀌면 입력이 함께 흔들리는 결합이 남아 있다.
  recordShadowCycle(1475)은 snap 객체를 받아 cash·slots·openCount를
  안에서 꺼낸다. ctx.tempCandsSeen은 스캔 단계가 채우고 섀도우 단계만
  소비한다 (진입 판정 slug 대조 + 기록 입력).
- Java: ShadowLog.buildCycleRecord·recordCycle이
  List<ScanFilter.Candidate>를 받아 cand.slug·cand.endDate·cand.price만
  읽는다. NativeCycle.cycleShadowStage가 ctx.tempCandsSeen을 그대로
  넘긴다.

## 목표 구조 (계약)

섀도우 입력 계약을 명시한다. 계약은 두 부분이다.

- 후보 계약 (후보 1건당 필드 3개):
  - slug: string — 후보 식별자. 도시 파싱의 입력.
  - price: number — 평가 시점 No 가격.
  - endDate: string — 이벤트 종료 시각 ISO 문자열. 날짜 키의 입력.
- 사이클 헤더 계약: ts(number, ms)·cycle(number)·cash(number|null)·
  slots(number|null)·openCount(number|null)·enteredSlugs(slug 집합).

스캔 단계가 스캔 후보를 후보 계약으로 변환해 넘기고(JS
toShadowCandInputs, Java ShadowLog.CandInput.ofAll), 섀도우 기록은
계약만 의존한다. recordShadowCycle은 snap 대신 헤더 계약을 평면으로
받는다.

## 절대 조건

- 거동 동일 리팩터. 섀도우 레코드와 저장소 직렬화가 기존과 바이트
  단위로 동일해야 한다 (연구 표본 축적 중이라 형태 변경 금지).
  리팩터 전 코드로 기준선을 먼저 캡처하고 전후를 대조한다.
- 매매·손절·리딤 판단과 스캔 선정 결과는 바꾸지 않는다.

## 구현 (완료)

- JS: 섀도우 절에 toShadowCandInput·toShadowCandInputs 신설 (계약
  필드 slug·price·endDate 3개, 값 복사만). 스캔 단계가
  toShadowCandInputs(tempCands)로 변환해 ctx.shadowInputs에 넣고,
  섀도우 단계는 계약만 소비한다. recordShadowCycle은 snap 대신 헤더
  계약(ts·cycle·cash·slots·openCount)을 평면으로 받는다 — 추출은
  섀도우 단계가 종전과 같은 null 의미론으로 한다. ctx.tempCandsSeen과
  스캔 원형 전달은 제거했다.
- Java: ShadowLog.CandInput 신설 (of·ofAll 변환). buildCycleRecord·
  recordCycle이 List<CandInput>을 받는다. CycleContext.tempCandsSeen을
  shadowInputs로 교체하고 NativeCycle 스캔·섀도우 단계를 재배선했다.

## 단계

- [x] 기준선 캡처 (JS·Java, 리팩터 전 코드) — JS 레코드 5건·저장소
      3,794바이트 (fixtures/shadow-contract-baseline.json), Java
      레코드 4건·저장소 3,542바이트
      (android/test/fixtures/shadow-contract-baseline.json)
- [x] JS 계약 변환 + 배선 교체
- [x] Java CandInput + 배선 교체
- [x] 픽스처 전수 대조 (바이트 동일) — JS shadow-contract 17건,
      Java ShadowLog 344건 (기존 333 + 계약 가드 5 + 기준선 6)
- [x] 전면 감사 4축 발견 0건. 축 1·2·3: 델타 헝크가 섀도우 구간과
      배선 지점에만 있음을 diff로 확인 — 제출·서명·손절·리딤 본문
      무접촉, 섀도우 저장소 쓰기 경로(파싱·붙이기·쓰기) 무변경. 축 4:
      스냅샷 취득 횟수 무변경, 헤더 추출 식은 종전 드라이버 내부 식과
      동일(null 의미론 포함, 같은 try 블록). 변환 시점이 스캔 단계로
      앞당겨진 것은 후보 필드(slug·price·endDate)를 재기록하는 코드가
      JS에 없고 Java Candidate가 final 필드라 값이 불변임을 전수
      대조로 확인 — 복사 시점 이동이 값을 바꿀 수 없다.
- [x] JS 전 슈트 89,068건 통과 (신규 shadow-contract 17 포함),
      JVM 전 슈트 106,916건 통과 (ShadowLog 344 포함), 골든 전부
      통과 (48/48 · 185 · 178 · 215)
- [x] APK 빌드 완료, DEX 게이트 PASSED, 축소 스모크 PASSED. 출하 APK
      직접 대조: versionCode 65, versionName 0.3.62, dex에
      JavascriptInterface·ShadowLog$CandInput 존재, assets 번들이
      테스트한 번들과 바이트 동일(md5)

## 미검증

- 서명 경로는 실기기 서명 제출 확인 전까지 미검증으로 표기한다
  (이번 변경은 서명 경로 무접촉).

## 분리 7단계 완료 메모

- 이 단계로 감사 분리 순서 7개가 전부 끝났다. 종결 판정은 감사 지시
  대로 재감사(같은 FR/DP 행렬로 상삼각 성분 감소 대조)를 거쳐야
  한다 — 재감사 요청은 상위 라인 몫으로 남긴다.
