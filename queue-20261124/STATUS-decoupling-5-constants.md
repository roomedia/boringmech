# 분리 5/7: 삼중 구현 상수 대조 게이트 + Python 트랙 지위 명시

상태: awaiting (2026-11-27 완료, 출하 코드 무변경이라 릴리스 없음)

## 조사 확정 사항 (착수 시점)

- 정본은 출하본 앱이다. JS `app/src/engine.js`가 상수 정본, Java는 그 미러.
- Python 트랙 실행 실태:
  - `scan_boring.py`, `stop_loss.py` (레포 사본): 페이퍼 평가용. 실제 실행은
    작업 디렉토리(`~/workspace/goals/goal/hidden_files/pm-trader/`) 사본을
    훅이 돌린다. pm-boring-scan(15분)이 scan_boring.py를, pm-stoploss-tick
    (60초)과 pm-boring-scan이 stoploss_boringmech.py를 실행한다. 레포의
    stop_loss.py 자체를 직접 실행하는 자동화는 없다 (미확인: 수동 실행 흔적).
  - `live/live_executor.py`: 앱과 별개 실험 boringmech-micro-live (자체
    `live/SPEC.md`가 정본, 시작 자본 $57.87, 실체결·슬리피지 실측 목적).
    실행용 훅은 초안(`~/hooks/scripts/boringmech-live.sh.draft`)만 있고
    미등록, 실행 디렉토리에 LIVE_ENABLED 없음, state.json 최종 갱신
    2026-11-14, 체결 0건. 현재 아무것도 실행하지 않는다.
  - 실행 디렉토리 사본의 live_executor.py는 레포 사본보다 낡았다
    (BET_USD 고정 방식, 사이징 일반화 커밋 ae951fe 미반영). 휴면 상태라
    실거래 영향은 없으나 재가동 전에 레포 사본으로 교체가 필요하다.
- 의도된 차이 (동기화 대상 아님, SPEC 확정): live_executor의 검증 밴드
  0.70~0.90 (앱·페이퍼 0.60~0.90), 사이징 총자본 1%·하한 $5 (앱 $5 고정).

## 진행

- [x] 상수 전수 목록화: 게이트 규칙 38개 (공유 32, 고정 6), 추출 지점 100건
- [x] 대조 게이트 `tools/constants_gate.py` + `app/test/constants-gate.test.mjs`를
      npm test 체인 끝에 연결. 음성 테스트(손절폭 0.30 변조)도 실패를 잡음을 확인
- [x] Python 트랙 지위 문서화: `docs/PYTHON-TRACK.md`, RELEASE-AUDIT.md에 게이트 절 추가
- [x] 검증: npm test 전 체인 통과 (게이트 PASS 포함). 어긋남 발견 0건 —
      JS↔Java 실거래 구현끼리도, Python 사본도 공유 상수는 전부 정본과 일치.
      의도된 차이(live 밴드·사이징)는 SPEC 확정 사항으로 고정값 처리
