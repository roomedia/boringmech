# STATUS: 보유 요약 단위 정정 (v0.3.48)

상태: running
시작: 2026-11-25 01:43 KST

## 작업
- [x] 현황 파악 (v0.3.47 요약 계산 코드·테스트 위치)
- [x] 전면 감사 4축: diff가 app/index.html 뷰어 요약 (summarizePositions·렌더 2줄·주석)과 positions 테스트뿐임을 확인. engine.js·src·네이티브 무변경이라 주문 제출·서명, 손절 판정·실행, 리딤·장부 계산, 스냅샷·사이징 계산 전부 무변경. 발견 0건
- [x] 정정: 수익·손실 종목 표기를 그룹 손익률(%) → 그룹 손익 합계($). winPct·lossPct·winCost·lossCost 제거, 렌더는 전체 손익 줄과 같은 부호+fmt 관례
- [x] 테스트 갱신 (positions 14건 달러 기준) + 전 슈트 JS 855 · JVM 598 · 골든 48/185/178/215 전부 통과
- [x] 버전 0.3.48 (versionCode 51) 범프, 번들 재생성 (APP_VERSION=0.3.48)
- [x] APK 빌드, DEX 게이트 PASSED, 축소 스모크 PASSED, 출하 APK 대조 (versionCode 51·0.3.48·JavascriptInterface 존재)
- [ ] 현재 보유 달러 렌더 예시 실측 (data-api 조회 중)
- [ ] 커밋·푸시·GitHub 릴리스 게시
