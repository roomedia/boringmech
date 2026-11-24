# STATUS: 보유 종목 영역 정리 + v0.3.47 출시

- 상태: running
- 시작: 2026-11-24 23:50 KST
- 범위: 표시 전용 (매매 동작 무변경)
  1. 보유 종목 영역 접힘·펼침, 접힌 상태 요약 (보유 n건 / 포지션 가치·전체 손익 / 수익 m건 +x% / 손실 l건 -x%)
  2. 매도 버튼 축소, 포지션 행 오른쪽 끝에 배치
  3. 펼침 상태 재렌더 유지

## 단계
- [x] 현황 파악 (index.html 보유 종목 렌더, RELEASE-AUDIT 정본 확인)
- [x] 구현 (요약 접힘 + 매도 버튼 알약형·행 오른쪽 끝)
- [x] 가드 테스트 신설 + 전 슈트·골든: JS 855 (book 465, temp 183, bundle 10, html-guard 67, settings-lock 42, sellquote 30, report 44, positions 14 신설), JVM 598, 골든 1~4차 전부 통과
- [x] 전면 감사 4축: diff가 app/index.html 뷰어 표시 코드 (renderPositions·CSS·마크업·토글 핸들러)와 테스트로만 한정됨을 확인. engine.js는 번들 재생성 (버전 상수), src·네이티브 무변경. 매도 핸들러 (stopPropagation + openSellPopup) 동일, 부모 노드만 변경. 발견 0건
- [ ] 버전 0.3.47 (versionCode 50), 번들 재생성 완료, APK 빌드·DEX 게이트·축소 스모크 (진행 중)
- [ ] GitHub 릴리스 게시
