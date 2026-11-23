# Q15 상태: 스캐너 판정 시 재조회 가격 로그 기록

- 상태: awaiting
- 시작: 2026-11-23
- 범위: paper-temp-scanner/scanner.py의 검증 판정 기록에 재조회 outcomePrices(선정 쪽 가격)를 추가 필드로 남긴다. 앱 코드 변경 없음. 기존 로그 파일 형식 불변, 집계가 깨지지 않게 추가 필드로만 붙인다.
- 배경: Q8 조사에서 snapshot_mismatch 판정 시점의 재조회 가격이 로그에 없어 방향·크기를 다음 패스 가격으로 간접 추정할 수밖에 없었다.
- 단계:
  - [완료] verify_candidate가 재조회 가격을 verdict에 실어 돌려주게 수정: 재조회 outcomePrices가 확정된 시점부터 선정 쪽 가격을 requeryPrice로 실어, snapshot_mismatch를 포함한 이후 모든 탈락 판정과 통과 판정에 붙는다. 재조회 전에 탈락하는 판정 (gamma_market_missing 등)에는 필드가 없다.
  - [완료] run_pass의 verdict 로그 레코드에 requeryPrice 복사 추가. 기존 필드는 그대로라 요약·다일 집계는 영향 없음. 기존 로그 파일은 건드리지 않았다.
  - [완료] 단위 테스트 test_scanner.py (네트워크 모킹, run_pass 전 경로): snapshot_mismatch 판정에 requeryPrice 0.95, 통과 판정에 0.85가 찍히고, 재조회 전 탈락에는 필드가 없음을 확인. PASS.
  - [완료] 커밋 + 푸시 (scanner.py, test_scanner.py, 이 상태 파일만. 스캐너 폴더의 로그·조사 문서는 기존대로 미추적 유지)
