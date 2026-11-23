# Q25 스캐너 역방향 유입 표본 오더북 프로브

- 상태: awaiting
- 시작: 2026-11-23 KST
- 범위: ~/workspace/boringmech-repo/paper-temp-scanner/scanner.py 와 test_scanner.py만. 앱 코드 변경 없음.

## 구현

- 상수: PROBE_NEAR_HI_EXCESS = 0.002 (스냅샷 No 가격의 상한 초과분 이내), MAX_PROBES_PER_PASS = 10.
- scan_candidates가 above_hi 탈락 중 근접 구간 시장을 probe_targets로 따로 수집한다 (No 토큰 id 포함, 토큰이 없으면 None으로 넣어 실패로 기록되게 한다). below_lo와 초과분 0.002 초과 시장은 대상이 아니다.
- select_probe_targets가 초과분이 작은 순으로 정렬해 패스당 상한까지만 고른다.
- probe_book이 CLOB 오더북을 조회해 scan_probe 레코드 (slug, priceYes/priceNo, excess, bestAsk, bestBid, bestAskSize, inBand = bestAsk가 [0.80, 0.998] 안인지)를 만든다. 조회 실패는 probe_error, 토큰 없음은 probe_no_token, 매도호가 없음은 no_asks로 기록한다. 공급자 차단 (429/401/403)은 기존 규칙대로 패스 전체 중단으로 올린다.
- run_pass는 pass_start에 scan_probes_eligible·scan_probes_planned를 명시하고, 후보 emit 전에 프로브 레코드를 따로 남기며, summary에 probes·probe_in_band를 병기한다. 프로브 대상은 후보에 넣지 않아 본 선정·판정 흐름은 그대로다.

## 검증

- test_scanner.py (네트워크 모킹) 통과. 추가 가드: 스냅샷 0.999 (밴드 밖)인데 ask 0.85 (밴드 안)인 시장의 scan_probe 레코드와 inBand=True 확인, 후보 수가 2건으로 불변임을 확인, 표본 선정 상한 (15개 중 10개, 상한 1로 낮추면 1개), 근접 구간 밖·below_lo 제외, 토큰 없음·네트워크 오류의 실패 레코드 확인.
- 기존 가드 (requeryPrice, scan_drop, 상한 절단 표기)도 전부 그대로 통과.
