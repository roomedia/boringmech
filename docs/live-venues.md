# 라이브 베뉴 설계 — 미국 거주 사용자용 Polymarket US 어댑터

작성: 2026-11-14. 설계 문서다. 코드 구현은 포함하지 않는다.
대상 독자: **이 레포를 받아 쓰는 미국 거주 사용자.** 레포 소유자 본인의 거래를 위한 문서가 아니다.

전제: boringmech 전략 규칙(밴드 0.60–0.90, 건당 고정액, 만기 보유, 손절 −0.25)은 베뉴와 무관하게 동일하게 유지한다. 이 문서는 베뉴가 달라질 때 **실행 계층에서 무엇이 달라지는가**만 다룬다.

---

## 1. 사실 확인 결과 (2026-11-14, 공식 출처 기준)

### 1.1 Polymarket US 개발자 API — 존재한다, 공개돼 있다

- 공식 개발자 문서가 공개돼 있다: [docs.polymarket.us](https://docs.polymarket.us/llms.txt). FAQ 페이지가 Retail API와 Python·TypeScript SDK(`polymarket-us`)의 존재를 명시한다 — [Does Polymarket have an API?](https://docs.polymarket.us/learn/faq/api.md)
- 구성 ([API Introduction](https://docs.polymarket.us/api-reference/introduction.md)):
  - 인증 API `https://api.polymarket.us` — Orders(주문 생성·수정·취소·조회), Portfolio(포지션·활동), Account(잔고·buying power)
  - 공개 API `https://gateway.polymarket.us` — Markets, Events, Series, Sports, Search. **API 키 없이 시세 조회 가능**
  - WebSocket 2종: `wss://api.polymarket.us/v1/ws/private` (주문·포지션·잔고), `wss://api.polymarket.us/v1/ws/markets` (오더북·체결)
- 인증 ([Authentication](https://docs.polymarket.us/api-reference/authentication.md)): **Ed25519 키페어.** 앱에서 계정을 만들고 **신원확인(KYC)을 완료한 뒤** 개발자 포털(polymarket.us/developer)에서 Key ID + Secret Key를 발급받는다. 요청마다 `X-PM-Access-Key` / `X-PM-Timestamp` / `X-PM-Signature` 헤더 서명 (타임스탬프 허용 오차 30초). KYC 없는 샌드박스·데모 모드는 문서에 없다 — **API 접근 자체가 KYC 완료 계정의 권한이다.**
- 레이트리밋 ([Rate Limits](https://docs.polymarket.us/api-reference/rate-limits.md)): Retail API 일반 **25 req/s** (키가 아니라 IP 기준 공유). 주문은 5초 내 미처리 시 stale price 보호를 위해 거부될 수 있다 (`Order Processing Max Delay Exceeded` — 재제출 가능한 일시 거부).
- 운영 주체: QCX LLC (CFTC 지정 DCM) + QC Clearing LLC (DCO). USD 법정화폐 거래 — [What is Polymarket US?](https://docs.polymarket.us/getting-started/what-is-polymarket-us.md)

### 1.2 마켓 범위 — 이진 이벤트 마켓이 카테고리 전반에 있다

- 전 마켓이 YES/NO 이진 계약, 정산 $1.00 / $0.00, 중앙지정가 오더북 — [What is Polymarket US?](https://docs.polymarket.us/getting-started/what-is-polymarket-us.md)
- 카테고리 (같은 공식 페이지): **스포츠** (NFL·NBA·NHL·MLB·MLS·대학스포츠·테니스·골프 등), **정치, 크립토, 경제, 날씨, 기술, 문화**
- 구조는 Series → Event → Market 3층, 식별자는 **slug** 단일 체계 — [Events & Markets](https://docs.polymarket.us/concepts/events-and-markets.md). 마켓당 instrument는 YES 하나뿐이고, NO 매수는 YES 매도(intent `BUY_SHORT`)로 표현한다 — [Orders & Trading](https://docs.polymarket.us/concepts/orders.md), [Create Order](https://docs.polymarket.us/api-reference/orders/create-order.md)

**boringmech 유니버스 성립 판정: 구조적으로 성립한다.** 밴드(0.60–0.90) 판정에 필요한 이진 가격·만기·거래량이 존재하는 마켓 형태다. 단 두 가지 주의:

- 현행 스캐너는 스포츠 **경기** 마켓과 크립토 단타(up-or-down)를 제외한다. Polymarket US 카탈로그는 스포츠 비중이 크고, 제외 후 남는 정치·경제·기술·문화·날씨 장기 마켓의 **물량·유동성 규모는 문서만으로 확정할 수 없다 (미확인 — §5 참조).**
- 현행 스캐너의 거래량 필터(누적 10,000 이상)는 Gamma 필드 기준이다. Polymarket US 공개 API가 동등한 누적 거래량 필드를 주는지는 미확인.

### 1.3 미국 거주자의 글로벌 CLOB — 신규 매수 불가 (close-only)

공식 [Geographic Restrictions](https://docs.polymarket.com/api-reference/geoblock) (2026-11-12 갱신본):

| 구분 | 미국(US) | 한국(KR) | OFAC 제재국 |
|---|---|---|---|
| 글로벌 CLOB | **Close-only (프론트엔드·API 모두)** — 기존 포지션 청산만 가능, 신규 주문 불가 | Close-only (프론트엔드만, API는 제한 없음) | 완전 차단 (신규·청산 모두 불가) |

따라서 미국 거주 사용자에게 글로벌 CLOB 어댑터를 켜는 것은 설계상 성립하지 않는다. 미국 거주자의 적격 베뉴는 Polymarket US뿐이다.

---

## 2. 베뉴 라우팅 — 지역·약관 사전 게이트의 구체화

기존 감사(AUDIT-LIVE-20261114)가 지적한 "지역·약관 사전 게이트 부재"를 다음 라우팅으로 메운다. 원칙: **라우팅 기준은 사용자의 실제 거주지이며, 실행 위치(IP)를 거주로 위장하는 우회는 설계에 넣지 않는다.**

```
설정: residency (사용자 선언, 기본값 없음) + venue (자동 결정, 수동 override 불가)
```

| 선언된 거주 | 적격 베뉴 | 글로벌 CLOB | Polymarket US | 비고 |
|---|---|---|---|---|
| 미국 | Polymarket US | **비활성** (close-only) | 활성 (KYC·API 키 게이트 통과 시) | |
| 그 외 적격 지역 | 글로벌 CLOB | 활성 (주문 전 geoblock 실측) | 비활성 (미국 거주 전용) | 적격 = geoblock 문서의 차단·close-only 목록에 없음 |
| 차단·close-only 지역 | 없음 | 비활성 | 비활성 | **페이퍼 전용으로 폴백** |

게이트 절차 (라이브 활성화 시, 베뉴 공통):

1. **선언 확인** — `residency` 미선언이면 라이브 경로 진입 자체를 거부한다 (기본값은 페이퍼).
2. **자격 실측** —
   - 글로벌: 주문 경로를 열기 전에 공식 geoblock 엔드포인트로 현재 IP의 `blocked` 여부를 확인한다 (공식 문서가 빌더에게 요구하는 절차). 선언과 실측이 충돌하면 라이브 거부.
   - Polymarket US: API 키 존재 + 인증 API의 Account 조회 성공을 KYC 완료의 대리 증거로 삼는다. 키가 없거나 인증이 실패하면 라이브 거부 (키 발급 자체가 KYC 뒤에만 가능하므로 별도 KYC 판정 로직은 두지 않는다).
3. **기존 3중 게이트 유지** — `--live` 플래그 + 환경변수 confirm + `LIVE_ENABLED` 파일. 베뉴가 정해져도 이 게이트는 베뉴별로 그대로 적용한다.
4. **사이클 중 재검증** — 글로벌은 매 사이클 geoblock 재확인, 어느 베뉴든 403·자격 오류는 그 사이클만 중단하고 사유를 원문으로 남긴다 (2026-11-14 래치 제거 결정과 동일 — 영구 차단 래치 없음).

---

## 3. 어댑터 인터페이스 — 고리별 차이

전략 코어(스캔 필터·밴드 판정·사이징·손절 판정)는 베뉴 무관하게 공유하고, 아래 고리만 어댑터 뒤로 숨긴다. 현행 글로벌 구현은 `live/live_executor.py` + `scan_boring.py` 기준.

| 고리 | 현행 글로벌 구현 | Polymarket US 어댑터 |
|---|---|---|
| 마켓 발견 | Gamma API (`gamma-api.polymarket.com/markets`), 유동성 상위 1,000개 | 공개 게이트웨이 Markets/Events/Series/Search (키 불요) |
| 식별자 | `clobTokenIds` (결과별 토큰 ID) + condition_id | **marketSlug 단일 식별자** — 토큰 개념 없음 |
| 가격 조회 | Gamma `outcomePrices`, CLOB 오더북 | 공개 API 오더북·BBO·가격 히스토리, 또는 WS markets 스트림 |
| 인증 | EOA 개인키 EIP-712 서명 + CLOB API creds, sig_type 3 (POLY_1271), deposit wallet이 funder | **Ed25519 API 키 서명** (지갑·온체인 서명 없음), KYC 완료 계정 |
| 자금·담보 | pUSD (Polygon), 입금 지갑, POL 가스 잔고 관리 필요 | **USD 법정화폐**, Polymarket Clearing 보관 — 가스 개념 없음 |
| 주문 제출 | py-clob-client-v2, 토큰 단위 BUY | `POST /v1/orders` — slug + intent. 우세 쪽이 YES면 `BUY_LONG`, NO면 `BUY_SHORT` (가격은 항상 YES 기준이므로 NO 가격은 1 − YES가로 환산) |
| 주문 옵션 | FOK 중심 (현행 실행기) | Limit/Market, TIF = DAY·GTC·GTD·IOC·FOK, `synchronousExecution`으로 체결까지 블로킹 조회 가능 |
| 체결 확인 | CLOB 주문 조회 + Data API 포지션 대조 | 주문 조회·executions 응답 + WS private 스트림 (주문·포지션·잔고) |
| 손절 매도 | 보유 토큰 매도 주문 | `SELL_LONG` intent 또는 close-position 엔드포인트 (전량 청산 전용) |
| 정산 감지 | Data API에서 REDEEMABLE 상태 감지 | Portfolio/활동 조회 — 정산이 잔고에 자동 반영됨 |
| 정산 회수 | **redeem 실행 코드 없음 (감사 최대 구멍)** — relayer·Builder 키 필요 | **불요.** Polymarket Clearing이 $1/$0로 자동 현금 정산 — [Market Settlement](https://docs.polymarket.us/learn/markets/contract-settlement.md). 글로벌 구현의 미완 고리가 베뉴 특성으로 소멸한다 |
| 수수료 회계 | 마켓별 `feeSchedule.rate` 실측 (공식: 수량 × rate × p × (1−p), 실측 평균 0.56%) | 전역 스케줄 (§4) — 어댑터가 체결 시점에 Θ를 확정해 원장에 기록 |

---

## 4. 베뉴별 차이표 (확인된 것만)

| 항목 | 글로벌 CLOB (현행) | Polymarket US |
|---|---|---|
| 운영·규제 | 무허가 글로벌 베뉴 (Polygon 온체인) | QCX LLC, CFTC DCM + DCO |
| 이용 자격 | geoblock 통과 지역 (미국은 close-only) | **미국 거주 + KYC 완료** |
| 거래 통화 | pUSD (USDC 담보, Polygon) | USD 법정화폐 |
| 테이커 수수료 | 마켓별 rate (실측 평균 매수 0.56%) | `Θ × 계약수 × p × (1−p)` — Θ는 직전 30일 테이커 물량 티어: **Tier 1 (<$10k) 0.095** / Tier 2 0.085 / Tier 3 0.075 / Tier 4 0.0695. 신규 계정은 첫 7일 Θ=0.0695 ([Fee Schedule](https://docs.polymarket.us/fees.md), 2026-11-11 시행본) |
| 메이커 비용 | 마켓별 스케줄 따름 | 메이커 수수료 0 + 물량 티어별 리베이트 (전략이 테이커 전용이라 무관) |
| 최소 주문 | 레포 문서에 전역 하한 명시 없음 (라이브 변형은 오더북 재검증으로 대체: 스프레드 ≤$0.02, ask depth ≥$10) | 전역 고정 하한 없음 — 마켓별 `minimumTradeQty`로 결정, 1 미만 허용 마켓은 소수 계약 가능 ([Create Order](https://docs.polymarket.us/api-reference/orders/create-order.md)) |
| 정산 방식 | UMA 해소 → redeem 트랜잭션으로 회수 (현행 미구현) | Clearing 자동 현금 정산 ($1/$0), 최종성(finality)은 거래소 규칙집 기준 |
| 레이트리밋 | 현행 구현은 15분 스캔 주기로 여유 | Retail 25 req/s (IP 기준) — 스캔 주기 대비 충분 |

### 수수료가 전략에 미치는 영향 (계산)

테이커 수수료 ÷ 거래금액 = Θ × (1 − p). 밴드 구간에서:

| 진입가 p | 글로벌 실측 평균 | US Tier 1 (Θ=0.095) | US Tier 4 (Θ=0.0695) |
|---|---|---|---|
| 0.60 | 0.56% (구간 무관 평균) | **3.80%** | 2.78% |
| 0.75 | 〃 | **2.38%** | 1.74% |
| 0.90 | 〃 | **0.95%** | 0.70% |

소매 티어(Tier 1) 기준 글로벌 실측의 약 2~7배다. STRATEGY.md의 손익분기 조건(승률 > 진입가 + 수수료)이 그대로 적용되므로, **US 어댑터는 체결 직후 실제 부과 수수료를 건별로 원장에 기록하고, 평가 지표의 수수료 총액에 반드시 포함해야 한다.** 밴드 하단(0.60~0.70)은 글로벌에서도 유일한 손실 구간이었는데 US 수수료에서는 부담이 가장 크다 — 밴드 자체는 전략 규칙이라 이 문서에서 바꾸지 않되, 평가에서 구간별로 갈라 보게 될 지점이다.

---

## 5. 미확인 항목 (구현 착수 전에 실측할 것)

| 항목 | 상태 | 확인 방법 |
|---|---|---|
| 스포츠 제외 후 남는 장기 이벤트 마켓(정치·경제 등)의 개수·유동성 규모 | 미확인 — 문서만으로 확정 불가 | 공개 게이트웨이로 카테고리별 마켓 목록 실측 |
| US 공개 API의 누적 거래량 필드 존재 여부 (스캐너 물량 필터 대응) | 미확인 | Markets API 응답 스키마 확인 |
| 주(state)별 추가 제한 — 2차 출처는 네바다(법원 명령)·미시간(스포츠 중단) 사례를 전함 | 공식 문서에서 확인 못 함 | Polymarket US 약관·주별 안내 원문 확인 |
| 앱 접근의 초대 코드 필요 여부 — 인증 문서는 "invite code가 필요하면 지원팀에 문의" 문구를 포함 | 부분 확인 (API 문서·SDK는 공개, 계정 개설 경로는 앱 심사 존재 가능) | 계정 개설 절차 원문 확인 |

---

## 6. 구현 가능성 판정

**가능하다.** 근거: ① 주문·시세·포지션을 모두 커버하는 공식 Retail API와 SDK가 공개돼 있고, ② 마켓 형태(이진 $1/$0, CLOB, slug 체계)가 전략의 전제와 호환되며, ③ 글로벌 구현의 최대 미완 고리였던 redeem이 베뉴 특성상 불필요하다.

조건:
- 사용자는 **미국 거주 + KYC 완료 계정 보유자**여야 한다. 이 조건은 코드로 우회할 수 없고, 라우팅 게이트(§2)가 이를 강제한다.
- 어댑터는 **신규 구현**이다. 인증(Ed25519 vs EIP-712), 식별자(slug vs 토큰), 정산(자동 현금 vs redeem)이 전부 달라서 현행 `live_executor.py`를 고쳐 쓰는 방식은 성립하지 않는다. 공유 가능한 것은 전략 코어(필터·밴드·사이징·손절 판정)와 원장 포맷뿐이다.
- 착수 순서는 §5 실측(유니버스 규모) → 어댑터 인터페이스 확정 → 페이퍼 모드 대조 운용 → 라이브 게이트 순을 권한다. 이 문서는 그 첫 단계의 전제를 고정하는 데까지다.

---

## 출처

전부 2026-11-14 열람한 공식 문서다.

- Polymarket US API 소개: https://docs.polymarket.us/api-reference/introduction.md
- Polymarket US 인증: https://docs.polymarket.us/api-reference/authentication.md
- Polymarket US API FAQ: https://docs.polymarket.us/learn/faq/api.md
- Polymarket US 개요: https://docs.polymarket.us/getting-started/what-is-polymarket-us.md
- Polymarket US 이벤트·마켓 구조: https://docs.polymarket.us/concepts/events-and-markets.md
- Polymarket US 주문 모델: https://docs.polymarket.us/concepts/orders.md
- Polymarket US 주문 생성 API: https://docs.polymarket.us/api-reference/orders/create-order.md
- Polymarket US 수수료: https://docs.polymarket.us/fees.md
- Polymarket US 정산: https://docs.polymarket.us/learn/markets/contract-settlement.md
- Polymarket US 레이트리밋: https://docs.polymarket.us/api-reference/rate-limits.md
- 글로벌 CLOB 지역 제한: https://docs.polymarket.com/api-reference/geoblock
