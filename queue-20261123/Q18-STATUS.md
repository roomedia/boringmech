# Q18 스캐너 선정 단계 가격 탈락 개별 기록 보강

- 상태: awaiting
- 시작: 2026-11-23 KST
- 범위: paper-temp-scanner/scanner.py만. 앱 코드 변경 없음.

## 결과

- 선정 단계 가격 탈락 (temp_band_out)이 집계 카운트와 별개로 scan_drop 레코드로 개별 기록된다. 필드: slug, groupItemTitle, reason, side (below_lo/above_hi), priceYes/priceNo (선정 시점 Gamma outcomePrices 쌍), bandLo/bandHi, endDate. 선정 단계에서는 오더북을 조회하지 않아 ask는 없으며, 역방향 유입 판정은 후단 verdict와 slug 조인으로 한다.
- 기존 scan_drops 카운트와 기존 레코드 형식은 변경 없음. 추가는 scan_drop 레코드와 pass_start의 band_drop_records·band_drop_records_omitted 필드뿐이다.
- 폭증 방지: 패스당 상한 MAX_BAND_DROP_RECORDS=500 (실측 최대 242건/패스라 현 규모에서는 전수 기록). 초과분은 scan_drop_truncated 레코드로 생략 개수를 명시한다.
- 검증: test_scanner.py (네트워크 모킹) 통과. 기존 requeryPrice 가드 유지, 신규 가드: 하한 아래·상한 위 탈락 레코드의 가격 쌍과 방향, 비활성 등 가격 외 사유는 레코드 없음, 상한 1로 낮춘 패스에서 기록 1건 + truncated omitted=1 확인.
