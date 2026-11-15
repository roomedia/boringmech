// 네이티브 이관 3단계 fixture 빌더 — fixtures.json을 프로그램으로 조립한다.
// 실행: node build_fixtures.mjs → fixtures.json
// nowMs는 고정 (2026-11-15T12:00:00Z) — scan·verify의 날짜 판정을 결정적으로 만든다.

import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const NOW = 1794744000000; // 2026-11-15T12:00:00Z
const DAY = 86400000;
const isoIn = (days) => new Date(NOW + days * DAY).toISOString();

const cases = [];
const add = (kind, name, description, input) => cases.push({ kind, name, description, input });

// ── scan ─────────────────────────────────────────────────────
const mkt = (over = {}) => ({
  active: true,
  closed: false,
  slug: "some-market",
  question: "질문?",
  endDate: isoIn(10),
  volume: 20000,
  outcomePrices: "[\"0.75\", \"0.25\"]",
  ...over,
});

add("scan", "scan-band-edges", "밴드 경계: 0.60·0.90 포함, 0.5999·0.9001 탈락", {
  nowMs: NOW, heldSlugs: [], boughtSlugs: [],
  markets: [
    mkt({ slug: "band-lo-edge", outcomePrices: "[\"0.6\", \"0.4\"]" }),
    mkt({ slug: "band-hi-edge", outcomePrices: "[\"0.9\", \"0.1\"]" }),
    mkt({ slug: "band-lo-out", outcomePrices: "[\"0.5999\", \"0.4001\"]" }),
    mkt({ slug: "band-hi-out", outcomePrices: "[\"0.9001\", \"0.0999\"]" }),
    mkt({ slug: "band-group-lo", outcomePrices: "[\"0.7\", \"0.3\"]" }),
    mkt({ slug: "band-group-hi", outcomePrices: "[\"0.8\", \"0.2\"]" }),
  ],
});

add("scan", "scan-sports", "스포츠 판정: sportsMarketType·feeType sports_* 제외, politics_fees 통과", {
  nowMs: NOW, heldSlugs: [], boughtSlugs: [],
  markets: [
    mkt({ slug: "sports-type", sportsMarketType: "moneyline" }),
    mkt({ slug: "sports-fee", feeType: "sports_fees_v2" }),
    mkt({ slug: "sports-fee-exact", feeType: "sports" }),
    mkt({ slug: "politics-fee", feeType: "politics_fees" }),
    mkt({ slug: "no-fee" }),
  ],
});

add("scan", "scan-drops", "탈락 사유 전종 + 만기·거래량 경계", {
  nowMs: NOW, heldSlugs: ["held-one"], boughtSlugs: ["bought-one"],
  markets: [
    mkt({ slug: "inactive", active: false }),
    mkt({ slug: "closed", closed: true }),
    mkt({ slug: "not-accepting", acceptingOrders: false }),
    mkt({ slug: "archived", archived: true }),
    mkt({ slug: "bitcoin-up-or-down-may-15" }),
    mkt({ slug: "random-up-or-down-5m" }),
    mkt({ slug: "held-one" }),
    mkt({ slug: "bought-one" }),
    mkt({ slug: "end-today", endDate: isoIn(0) }),
    mkt({ slug: "end-max", endDate: isoIn(45) }),
    mkt({ slug: "end-over", endDate: isoIn(46) }),
    mkt({ slug: "vol-low", volume: 9999 }),
    mkt({ slug: "vol-edge", volume: 10000 }),
    mkt({ slug: "vol-nan", volume: "abc" }),
    mkt({ slug: "price-bad-json", outcomePrices: "not-json" }),
    mkt({ slug: "price-bad-len", outcomePrices: "[\"0.75\"]" }),
    mkt({ slug: "price-bad-nan", outcomePrices: "[\"abc\", \"0.25\"]" }),
    mkt({ slug: "ok-one" }),
  ],
});

add("scan", "scan-sort", "정렬: 0.70–0.80 구간 우선 + 구간 내 만기 가까운 순 + 동률 안정", {
  nowMs: NOW, heldSlugs: [], boughtSlugs: [],
  markets: [
    mkt({ slug: "sort-far-band", endDate: isoIn(10) }),
    mkt({ slug: "sort-near-band", endDate: isoIn(3) }),
    mkt({ slug: "sort-near-out", outcomePrices: "[\"0.65\", \"0.35\"]", endDate: isoIn(2) }),
    mkt({ slug: "sort-far-out", outcomePrices: "[\"0.85\", \"0.15\"]", endDate: isoIn(5) }),
    mkt({ slug: "sort-tie-a", endDate: isoIn(3) }),
    mkt({ slug: "sort-tie-b", endDate: isoIn(3) }),
  ],
});

add("scan", "scan-side", "우세 쪽 선택: 동률이면 yes, 낮으면 no", {
  nowMs: NOW, heldSlugs: [], boughtSlugs: [],
  markets: [
    mkt({ slug: "side-tie", outcomePrices: "[\"0.75\", \"0.75\"]" }),
    mkt({ slug: "side-no", outcomePrices: "[\"0.25\", \"0.75\"]" }),
  ],
});

// ── verify ───────────────────────────────────────────────────
const gammaMkt = (over = {}) => ({
  active: true,
  closed: false,
  slug: "cand-market",
  endDate: isoIn(10),
  clobTokenIds: "[\"tok-yes\", \"tok-no\"]",
  outcomePrices: "[\"0.75\", \"0.25\"]",
  ...over,
});
const book = (over = {}) => ({
  asks: [{ price: "0.75", size: "100" }],
  bids: [{ price: "0.74", size: "50" }],
  tick_size: "0.01",
  min_order_size: 5,
  neg_risk: false,
  ...over,
});
const cand = (over = {}) => ({ slug: "cand-market", question: "질문?", side: "yes", price: 0.75, endDate: isoIn(10), ...over });
const ver = (name, description, input) =>
  add("verify", name, description, { nowMs: NOW, bet: 5, cand: cand(), gammaPage: [gammaMkt()], book: book(), ...input });

ver("verify-ok-yes", "정상 검증 (yes)", {});
ver("verify-ok-no", "정상 검증 (no — 가격 역전)", {
  cand: cand({ side: "no", price: 0.75 }),
  gammaPage: [gammaMkt({ outcomePrices: "[\"0.25\", \"0.75\"]" })],
  book: book({ asks: [{ price: "0.75", size: "100" }], bids: [{ price: "0.74", size: "50" }] }),
});
ver("verify-v2-position-ids", "v2는 positionIds 사용 — clobTokenIds가 깨져 있어도 파싱하지 않음", {
  gammaPage: [gammaMkt({ version: "v2", positionIds: ["pos-yes", "pos-no"], clobTokenIds: "not-json" })],
});
ver("verify-gamma-missing", "Gamma 페이지가 비면 gamma_market_missing", { gammaPage: [] });
ver("verify-gamma-sports", "검증 단계 스포츠 가드", {
  gammaPage: [gammaMkt({ sportsMarketType: "moneyline" })],
});
ver("verify-gamma-not-accepting", "주문 중단 마켓", {
  gammaPage: [gammaMkt({ acceptingOrders: false })],
});
ver("verify-gamma-parse-error", "clobTokenIds 파싱 실패", {
  gammaPage: [gammaMkt({ clobTokenIds: "not-json" })],
});
ver("verify-gamma-shape-error", "토큰이 선택 쪽 인덱스보다 적음", {
  cand: cand({ side: "no", price: 0.75 }),
  gammaPage: [gammaMkt({ clobTokenIds: "[\"tok-only\"]", outcomePrices: "[\"0.25\", \"0.75\"]" })],
});
ver("verify-market-inactive", "스캔 뒤 비활성화된 마켓", {
  gammaPage: [gammaMkt({ active: false })],
});
ver("verify-end-out", "만기가 범위 밖으로 이동", {
  gammaPage: [gammaMkt({ endDate: isoIn(60) })],
});
ver("verify-snapshot-mismatch", "신호가와 Gamma가 편차 초과", {
  gammaPage: [gammaMkt({ outcomePrices: "[\"0.8\", \"0.2\"]" })],
});
ver("verify-snapshot-boundary-quirk", "편차 정확히 0.03 — double 연산상 0.030000000000000027이라 탈락 (quirk)", {
  gammaPage: [gammaMkt({ outcomePrices: "[\"0.78\", \"0.22\"]" })],
  book: book({ asks: [{ price: "0.78", size: "100" }], bids: [{ price: "0.77", size: "50" }] }),
});
ver("verify-snapshot-near", "편차 0.02는 통과", {
  gammaPage: [gammaMkt({ outcomePrices: "[\"0.77\", \"0.23\"]" })],
  book: book({ asks: [{ price: "0.77", size: "100" }], bids: [{ price: "0.76", size: "50" }] }),
});
ver("verify-side-flipped", "스캔 뒤 우세가 뒤집힘", {
  cand: cand({ price: 0.42 }),
  gammaPage: [gammaMkt({ outcomePrices: "[\"0.4\", \"0.6\"]" })],
});
ver("verify-no-asks", "매도 호가 없음", { book: book({ asks: [] }) });
ver("verify-no-bids", "매수 호가 없음", { book: book({ bids: [] }) });
ver("verify-ask-out-of-band-low", "최저 매도호가가 밴드 아래", {
  book: book({ asks: [{ price: "0.55", size: "100" }], bids: [{ price: "0.54", size: "50" }] }),
});
ver("verify-ask-out-of-band-high", "최저 매도호가가 밴드 위", {
  book: book({ asks: [{ price: "0.92", size: "100" }], bids: [{ price: "0.91", size: "50" }] }),
});
ver("verify-ask-above-signal", "신호가 + 0.05 초과 호가", {
  book: book({ asks: [{ price: "0.81", size: "100" }], bids: [{ price: "0.8", size: "50" }] }),
});
ver("verify-ask-above-signal-boundary", "신호가 + 0.05 정확히 — 초과가 아니라 통과", {
  book: book({ asks: [{ price: "0.8", size: "100" }], bids: [{ price: "0.79", size: "50" }] }),
});
ver("verify-spread-wide", "스프레드 0.03 탈락", {
  book: book({ asks: [{ price: "0.75", size: "100" }], bids: [{ price: "0.72", size: "50" }] }),
});
ver("verify-spread-boundary", "스프레드 0.02 경계 — 1e-9 허용오차로 통과 (double상 0.020000000000000004)", {
  book: book({ asks: [{ price: "0.75", size: "100" }], bids: [{ price: "0.73", size: "50" }] }),
});
ver("verify-depth-low-bet", "호가 깊이 < $10 하한", {
  book: book({ asks: [{ price: "0.75", size: "10" }], bids: [{ price: "0.74", size: "50" }] }),
});
ver("verify-depth-low-bigbet", "호가 깊이 < 건당 금액(큰 베팅)", {
  bet: 20,
  book: book({ asks: [{ price: "0.75", size: "20" }], bids: [{ price: "0.74", size: "50" }] }),
});
ver("verify-depth-boundary", "호가 깊이 정확히 하한이면 통과", {
  cand: cand({ price: 0.78 }),
  gammaPage: [gammaMkt({ outcomePrices: "[\"0.78\", \"0.22\"]" })],
  book: book({ asks: [{ price: "0.8", size: "12.5" }], bids: [{ price: "0.79", size: "50" }] }),
});
ver("verify-tick-fallback", "book에 tick_size가 없으면 조회로 폴백", {
  book: (() => { const b = book({ asks: [{ price: "0.755", size: "100" }], bids: [{ price: "0.745", size: "50" }] }); delete b.tick_size; return b; })(),
  tickSizeFallback: 0.001,
});
ver("verify-limit-capped", "지정가가 밴드 상한 0.90으로 캡", {
  cand: cand({ price: 0.87 }),
  gammaPage: [gammaMkt({ outcomePrices: "[\"0.87\", \"0.13\"]" })],
  book: book({ asks: [{ price: "0.89", size: "100" }], bids: [{ price: "0.88", size: "50" }] }),
});
ver("verify-below-min-size", "추정 주식수가 최소 주문 수량 미달", {
  book: book({ min_order_size: 100 }),
});
ver("verify-book-throws", "오더북 조회 자체가 던지면 전파 (호출자가 verify_error)", { bookError: true });
ver("verify-asks-unsorted-filter", "호가 정렬 + 비유한 호가 제거", {
  book: book({
    asks: [
      { price: "0.78", size: "5" },
      { price: "0.76", size: "100" },
      { price: "abc", size: "10" },
      { price: "0.77", size: "xyz" },
    ],
    bids: [{ price: "0.75", size: "50" }],
  }),
});

// ── 손절 ─────────────────────────────────────────────────────
add("slVerdict", "sl-triggered-exact", "현재가 = 진입가 − 0.25 정확히 → triggered", { entry: 0.75, current: 0.5 });
add("slVerdict", "sl-holding-above", "경계 위 → holding", { entry: 0.75, current: 0.51 });
add("slVerdict", "sl-decided-high-exact", "0.97 정확히 → decided", { entry: 0.9, current: 0.97 });
add("slVerdict", "sl-decided-low-exact", "0.03 정확히 → decided (발화선 아래여도)", { entry: 0.28, current: 0.03 });
add("slVerdict", "sl-decided-crash", "0으로 추락 → decided", { entry: 0.28, current: 0.02 });
add("slVerdict", "sl-skip-nan-current", "현재가 NaN → skip", { entry: 0.75, current: "__NaN__" });
add("slVerdict", "sl-skip-nan-entry", "진입가 NaN → skip", { entry: "__NaN__", current: 0.4 });
add("slVerdict", "sl-skip-inf", "현재가 Infinity → skip (유한 아님)", { entry: 0.75, current: "__Inf__" });
add("slVerdict", "sl-triggered-low-band", "저밴드 발화", { entry: 0.62, current: 0.37 });

add("pickIndex", "pick-name-match", "이름 대조 (대소문자·공백 무시)", {
  outcomes: ["Yes", "No"], row: { outcome: " yes " },
});
add("pickIndex", "pick-fallback-index", "이름 대조 실패 → outcome_index 폴백", {
  outcomes: ["Up", "Down"], row: { outcome: "Yes", outcome_index: 1 },
});
add("pickIndex", "pick-fraction-index", "outcome_index가 소수면 -1", {
  outcomes: [], row: { outcome_index: 1.5 },
});
add("pickIndex", "pick-negative-index", "outcome_index 음수면 -1", {
  outcomes: [], row: { outcome_index: -1 },
});
add("pickIndex", "pick-empty-outcomes-index0", "outcomes가 비어도 폴백은 동작", {
  outcomes: [], row: { outcome_index: 0 },
});
add("pickIndex", "pick-none", "이름도 인덱스도 없으면 -1", {
  outcomes: ["Yes", "No"], row: {},
});

const gammaPage = (outcomes, prices) => [{
  outcomes: JSON.stringify(outcomes),
  outcomePrices: JSON.stringify(prices),
}];
add("gammaParse", "gamma-parse-ok", "정상 파싱", { page: gammaPage(["Yes", "No"], ["0.5", "0.5"]) });
add("gammaParse", "gamma-parse-empty", "빈 페이지 → null", { page: [] });
add("gammaParse", "gamma-parse-bad-json", "outcomes 파싱 실패 → null", {
  page: [{ outcomes: "not-json", outcomePrices: "[\"0.5\", \"0.5\"]" }],
});
add("gammaParse", "gamma-parse-len-mismatch", "outcomes·prices 길이 불일치 → null", {
  page: gammaPage(["Yes", "No"], ["0.5"]),
});
add("gammaParse", "gamma-parse-nonfinite", "가격이 숫자가 아니면 null", {
  page: gammaPage(["Yes", "No"], ["abc", "0.5"]),
});
add("gammaParse", "gamma-parse-empty-outcomes", "outcomes가 비면 null", {
  page: gammaPage([], []),
});

const slRow = (over = {}) => ({
  slug: "held-market", token_id: "tok-1", avg_price: 0.75, current_size: 10, outcome: "Yes", ...over,
});
add("slEvaluate", "eval-triggered", "1차·2차 모두 발화 → triggered", {
  row: slRow(), page1: gammaPage(["Yes", "No"], ["0.5", "0.5"]), page2: gammaPage(["Yes", "No"], ["0.48", "0.52"]),
});
add("slEvaluate", "eval-holding", "1차 미발화 → holding", {
  row: slRow(), page1: gammaPage(["Yes", "No"], ["0.6", "0.4"]), page2: null,
});
add("slEvaluate", "eval-decided-as-holding", "결정 구간은 미발화로 취급 (원본은 false 반환)", {
  row: slRow(), page1: gammaPage(["Yes", "No"], ["0.98", "0.02"]), page2: null,
});
add("slEvaluate", "eval-recheck-dropped", "재검증에서 발화 해제 → recheck_dropped", {
  row: slRow(), page1: gammaPage(["Yes", "No"], ["0.5", "0.5"]), page2: gammaPage(["Yes", "No"], ["0.6", "0.4"]),
});
add("slEvaluate", "eval-bad-entry", "진입가 0 → bad_entry", {
  row: slRow({ avg_price: 0 }), page1: null, page2: null,
});
add("slEvaluate", "eval-skip-no-token", "token_id 없으면 skip", {
  row: (() => { const r = slRow(); delete r.token_id; return r; })(), page1: null, page2: null,
});
add("slEvaluate", "eval-skip-size0", "수량 0이면 skip", {
  row: slRow({ current_size: 0 }), page1: null, page2: null,
});
add("slEvaluate", "eval-no-index", "보유 쪽을 가격 목록에서 못 찾음 → no_index", {
  row: slRow(), page1: gammaPage(["Up", "Down"], ["0.5", "0.5"]), page2: null,
});
add("slEvaluate", "eval-skip-fetch-fail", "1차 조회 실패 → skip", {
  row: slRow(), page1: null, page2: null,
});

// ── 리딤 ─────────────────────────────────────────────────────
const CID_A = "aa".repeat(32);
const CID_B = "bb".repeat(32);
const CID_C = "cc".repeat(32);
add("plan", "plan-mixed", "승·패 혼합 그룹 분류 + expected 합산", {
  entries: [
    { condition_id: CID_A, status: "REDEEMABLE", current_value: 10.5, token_id: "t1" },
    { condition_id: CID_A, status: "REDEEMABLE_LOST", current_value: 0, token_id: "t2" },
    { condition_id: CID_B, status: "REDEEMABLE_LOST", current_value: 0, token_id: "t3" },
    { condition_id: CID_B, status: "REDEEMABLE_LOST", current_value: 0, token_id: "t4" },
  ],
});
add("plan", "plan-redeemable-zero", "REDEEMABLE이어도 가치 0이면 패 버킷 → worthless", {
  entries: [{ condition_id: CID_A, status: "REDEEMABLE", current_value: 0, token_id: "t1" }],
});
add("plan", "plan-errors", "condition_id 형식 오류는 errors로", {
  entries: [
    { condition_id: "xyz", status: "REDEEMABLE", current_value: 5 },
    { condition_id: "abcd", status: "REDEEMABLE", current_value: 5 },
    { status: "REDEEMABLE", current_value: 5 },
  ],
});
add("plan", "plan-normalize-grouping", "0x·대문자 표기가 달라도 같은 condition으로 그룹", {
  entries: [
    { condition_id: `0x${CID_A.toUpperCase()}`, status: "REDEEMABLE", current_value: 3, token_id: "t1" },
    { condition_id: CID_A, status: "REDEEMABLE_LOST", current_value: 0, token_id: "t2" },
  ],
});
add("plan", "plan-null-entries", "entries null → 빈 계획", { entries: null });
add("plan", "plan-expected-rounding", "expected_usd는 round2 (0.1 + 0.2)", {
  entries: [
    { condition_id: CID_C, status: "REDEEMABLE", current_value: 0.1, token_id: "t1" },
    { condition_id: CID_C, status: "REDEEMABLE", current_value: 0.2, token_id: "t2" },
  ],
});

add("verifyRecovery", "recovery-exact", "정확히 회수 → verified", { before: 100, after: 105, expected: 5 });
add("verifyRecovery", "recovery-short", "부족 → mismatch", { before: 100, after: 103, expected: 5 });
add("verifyRecovery", "recovery-rel-tol", "2% 상대 허용오차 안 → verified", { before: 0, after: 1015, expected: 1000 });
add("verifyRecovery", "recovery-rel-over", "허용오차 밖 → mismatch", { before: 0, after: 1030, expected: 1000 });
add("verifyRecovery", "recovery-abs-boundary", "절대 허용오차 경계 안 (0.04 차이)", { before: 100, after: 104.96, expected: 5 });
add("verifyRecovery", "recovery-zero-expected", "예상 0 — 절대 허용오차만", { before: 50, after: 50.04, expected: 0 });

add("balanceGate", "gate-all-zero", "전부 0 → already", { balances: ["0", "0"] });
add("balanceGate", "gate-some-left", "잔고가 남음 → submit", { balances: ["0", "5"] });
add("balanceGate", "gate-empty", "빈 목록 → unknown", { balances: [] });
add("balanceGate", "gate-null", "null → unknown", { balances: null });

add("normalizeCid", "cid-0x-upper", "0x + 대문자 → 소문자 정규화", { raw: `0x${CID_A.toUpperCase()}` });
add("normalizeCid", "cid-plain", "접두사 없는 64자리", { raw: CID_B });
add("normalizeCid", "cid-0X", "0X도 소문자화 후 제거", { raw: `0X${CID_A}` });
add("normalizeCid", "cid-short", "짧으면 null", { raw: "abcd" });
add("normalizeCid", "cid-nonhex", "hex가 아니면 null", { raw: "zz".repeat(32) });
add("normalizeCid", "cid-null", "null → null", { raw: null });

add("toBytes31", "bytes31-end00", "끝 00을 떼어 31바이트", { conditionId: `0x${"ab".repeat(31)}00` });
add("toBytes31", "bytes31-end01", "끝 01을 떼어 31바이트", { conditionId: `0x${"ab".repeat(31)}01` });
add("toBytes31", "bytes31-end-other-throws", "끝이 00/01이 아닌 64자리는 변환 불가 → throw", { conditionId: `0x${"ab".repeat(32)}` });
add("toBytes31", "bytes31-already31", "이미 62자리면 그대로", { conditionId: `0x${"cd".repeat(31)}` });
add("toBytes31", "bytes31-short-throws", "너무 짧으면 throw", { conditionId: "0xabcd" });

add("redeemCallCtf", "ctf-call-standard", "CTF 상환 콜 (negRisk 아님)", { conditionId: `0x${CID_A}`, negRisk: false });
add("redeemCallCtf", "ctf-call-negrisk", "CTF 상환 콜 (negRisk — 어댑터 교체)", { conditionId: `0x${CID_A}`, negRisk: true });
add("redeemCallV2", "v2-call", "protocol v2 상환 콜", {
  conditionId: `0x${"ab".repeat(31)}01`, outcomeIndex: "1", amount: "12345",
});

// ── judgeFill ────────────────────────────────────────────────
add("judgeFill", "fill-null", "null 응답 → unfilled", { resp: null });
add("judgeFill", "fill-string", "객체가 아닌 응답 → unfilled", { resp: "oops" });
add("judgeFill", "fill-transport-no-status", "status 없는 error → unknown", { resp: { error: "Network Error" } });
add("judgeFill", "fill-transport-status0", "status 0인 error → unknown", { resp: { error: "Network Error", status: 0 } });
add("judgeFill", "fill-http-error", "HTTP 403 error → error", { resp: { error: "forbidden", status: 403 } });
add("judgeFill", "fill-error-object-quirk", "error가 객체면 JSON 문자열화 + status 없으면 unknown (quirk)", {
  resp: { error: { code: 400, message: "bad" } },
});
add("judgeFill", "fill-errormsg", "errorMsg → error", { resp: { errorMsg: "order rejected", status: "live" } });
add("judgeFill", "fill-rejected", "success false → rejected", { resp: { success: false, status: "unmatched" } });
add("judgeFill", "fill-matched", "matched → filled", { resp: { success: true, status: "matched" } });
add("judgeFill", "fill-hash-evidence", "트랜잭션 해시가 있으면 filled", {
  resp: { success: true, status: "unmatched", transactionsHashes: ["0xabc"] },
});
add("judgeFill", "fill-making-evidence", "makingAmount > 0이면 filled", {
  resp: { success: true, status: "unmatched", makingAmount: "5000000" },
});
add("judgeFill", "fill-unmatched-no-evidence", "success여도 증거 없으면 unfilled", {
  resp: { success: true, status: "unmatched" },
});
add("judgeFill", "fill-no-success-flag", "success 플래그가 없으면 matched여도 unfilled", {
  resp: { status: "matched" },
});
add("judgeFill", "fill-delayed", "delayed는 증거 없으면 unfilled (호출자가 기록 처리)", {
  resp: { success: true, status: "delayed" },
});

// ── 기매수 정화 ──────────────────────────────────────────────
add("reconcile", "reconcile-basic", "온체인·보유에 없는 기록만 dropped (순서 유지)", {
  bought: ["a", "b", "c", "d"], onChain: ["a"], held: ["c"],
});
add("reconcile", "reconcile-empty", "빈 기록", { bought: [], onChain: ["a"], held: [] });

const actRow = (slug, over = {}) => ({ type: "TRADE", side: "BUY", slug, ...over });
const actPage = (rows, hasMore, next) => ({ data: rows, pagination: { has_more: hasMore, next_cursor: next } });
add("buySlugs", "buy-two-pages", "2페이지 순회 + 비매수 행 필터 + 완결", {
  pages: [
    actPage([actRow("s1"), actRow("s2", { side: "SELL" }), actRow("s3", { type: "REWARD" }), { type: "TRADE", side: "BUY" }], true, "c2"),
    actPage([actRow("s4")], false, null),
  ],
});
add("buySlugs", "buy-array-root", "루트가 배열인 응답 → 완결", { pages: [[actRow("s1")]] });
add("buySlugs", "buy-bad-shape", "data가 배열이 아니면 incomplete", {
  pages: [{ data: { nope: 1 } }],
});
add("buySlugs", "buy-empty-hasmore", "빈 페이지 + has_more → incomplete", {
  pages: [actPage([actRow("s1")], true, "c2"), actPage([], true, "c3")],
});
add("buySlugs", "buy-cursor-repeat", "커서가 반복되면 완결로 종료", {
  pages: [actPage([actRow("s1")], true, "c2"), actPage([actRow("s2")], true, "c2")],
});
add("buySlugs", "buy-page-cap", "페이지 상한 도달 → incomplete", {
  pages: Array.from({ length: 10 }, (_, i) => actPage([actRow(`s${i}`)], true, `c${i + 2}`)),
});
add("buySlugs", "buy-fetch-throws", "2페이지 조회 실패는 전파", {
  pages: [actPage([actRow("s1")], true, "c2"), { $throw: true }],
});

// ── 사이징 ───────────────────────────────────────────────────
add("sizing", "sizing-standard", "표준: 현금 하한 30%·슬롯 70 캡", { cash: 1000, equity: 1000, openCount: 0 });
add("sizing", "sizing-at-floor", "현금이 하한과 같으면 슬롯 0", { cash: 300, equity: 1000, openCount: 0 });
add("sizing", "sizing-below-bet", "가용액이 건당보다 적으면 슬롯 0", { cash: 305, equity: 1000, openCount: 0 });
add("sizing", "sizing-position-cap", "보유 상한이 슬롯을 제한 (69건 보유 → 1)", { cash: 100000, equity: 100000, openCount: 69 });
add("sizing", "sizing-position-full", "보유 70건이면 슬롯 0", { cash: 100000, equity: 100000, openCount: 70 });
add("sizing", "sizing-nan-closed", "NaN 입력이면 사이징을 닫음", { cash: "__NaN__", equity: 1000, openCount: 0 });
add("sizing", "sizing-small-equity", "소액 평가금 — 건당 하한 $5·현금 하한이 평가를 넘음", { cash: 100, equity: 499, openCount: 0 });
add("sizing", "sizing-bet-boundary", "1%가 정확히 $5인 경계", { cash: 10000, equity: 500, openCount: 0 });

add("betUsd", "bet-zero", "평가금 0 → 하한 $5", { equity: 0 });
add("betUsd", "bet-large", "평가금 1만 → $100", { equity: 10000 });
add("betUsd", "bet-null", "null → $5", { equity: null });
add("betUsd", "bet-just-below", "1%가 $5에 못 미치면 $5", { equity: 499.99 });
add("betUsd", "bet-exact", "1%가 정확히 $5", { equity: 500 });
add("betUsd", "bet-just-above", "1%가 $5를 넘으면 그 값", { equity: 501 });

// ── 정산 기준선 ──────────────────────────────────────────────
add("settledParse", "base-ok", "정상 기준선", { raw: "{\"count\": 10, \"realized\": 12.5}" });
add("settledParse", "base-null-fields", "null 필드 허용", { raw: "{\"count\": null, \"realized\": null}" });
add("settledParse", "base-missing-field", "필드 누락 → null", { raw: "{\"count\": 10}" });
add("settledParse", "base-bad-json", "깨진 JSON → null", { raw: "not json" });
add("settledParse", "base-null-raw", "원문 없음 → null", { raw: null });
add("settledParse", "base-string-field", "문자열 필드 → null", { raw: "{\"count\": \"10\", \"realized\": 1}" });

add("settledNotice", "notice-new", "신규 정산 알림분", {
  base: { count: 10, realized: 100 }, snap: { settledCount: 12, settledRealizedPnl: 130.25 },
});
add("settledNotice", "notice-none", "증가 없으면 null", {
  base: { count: 10, realized: 100 }, snap: { settledCount: 10, settledRealizedPnl: 100 },
});
add("settledNotice", "notice-snap-null", "스냅샷 미확정이면 null", {
  base: { count: 10, realized: 100 }, snap: { settledCount: null, settledRealizedPnl: null },
});
add("settledNotice", "notice-base-realized-null", "기준선 실현이 null이면 null", {
  base: { count: 10, realized: null }, snap: { settledCount: 12, settledRealizedPnl: 130 },
});

// ── 손절 원장 ────────────────────────────────────────────────
const slRec = (token, pnl, over = {}) => ({
  slug: `m-${token}`, condition_id: "c", token_id: token, outcome: "Yes",
  entry_price: 0.75, exit_price: 0.5, shares: 10, proceeds: 5, realized_pnl: pnl,
  ts: "2026-11-15T00:00:00.000Z", ...over,
});
add("slLedger", "ledger-append", "기록 추가 — 최근·합본", {
  recentRaw: JSON.stringify([slRec("t1", -2.5)]),
  archiveRaw: "[]",
  rec: slRec("t2", 1.25),
});
add("slLedger", "ledger-parse-filter", "파싱 필터: token_id 없거나 실현이 숫자가 아닌 행 제거", {
  recentRaw: JSON.stringify([
    slRec("t1", -2.5),
    { slug: "no-token", realized_pnl: 1 },
    { token_id: "t3", realized_pnl: "abc" },
    { token_id: "t4", realized_pnl: "2.5" },
  ]),
  archiveRaw: "[]",
  rec: slRec("t5", 0.5),
});
add("slLedger", "ledger-rec-unfiltered-quirk", "추가되는 기록 자체는 검증 없이 들어감 (quirk)", {
  recentRaw: "[]", archiveRaw: "[]", rec: { token_id: 123, realized_pnl: "x" },
});
add("slParse", "slparse-merged-order", "합본은 아카이브 먼저 + 최근 순", {
  recentRaw: JSON.stringify([slRec("t2", 1)]),
  archiveRaw: JSON.stringify([{ token_id: "t1", realized_pnl: -3 }]),
});

add("attempted", "attempted-add", "시도 토큰 추가", { raw: "[\"a\", \"b\", 123]", add: "c" });
add("attempted", "attempted-dup", "중복 추가는 그대로", { raw: "[\"a\", \"b\"]", add: "a" });
add("attempted", "attempted-empty", "빈 토큰 추가는 그대로", { raw: "[\"a\"]", add: "" });

// ── 리딤 pending 수집 ────────────────────────────────────────
const pendEntry = (cid, token, value, over = {}) => ({
  condition_id: cid, token_id: token, slug: "m", status: "REDEEMABLE", current_value: value, ...over,
});
add("ingest", "ingest-basic", "승·패 행 수집", {
  pending: [], history: [], closed: [],
  winRows: [{ condition_id: CID_A, token_id: "t1", slug: "ma", current_value: 10 }],
  lostRows: [{ condition_id: CID_B, token_id: "t2", slug: "mb", current_value: 0 }],
  closedRows: [],
});
add("ingest", "ingest-done-excluded", "회수 이력·종결된 condition은 다시 넣지 않음", {
  pending: [], history: [{ condition_id: CID_A }], closed: [CID_B],
  winRows: [
    { condition_id: CID_A, token_id: "t1", slug: "ma", current_value: 10 },
    { condition_id: CID_B, token_id: "t2", slug: "mb", current_value: 5 },
    { condition_id: CID_C, token_id: "t3", slug: "mc", current_value: 7 },
  ],
  lostRows: [], closedRows: [],
});
add("ingest", "ingest-value-update", "이미 수집된 항목의 가치 갱신", {
  pending: [pendEntry(CID_A, "t1", 0)], history: [], closed: [],
  winRows: [{ condition_id: CID_A, token_id: "t1", slug: "ma", current_value: 5 }],
  lostRows: [], closedRows: [],
});
add("ingest", "ingest-no-change", "같은 가치면 변경 없음", {
  pending: [pendEntry(CID_A, "t1", 5)], history: [], closed: [],
  winRows: [{ condition_id: CID_A, token_id: "t1", slug: "ma", current_value: 5 }],
  lostRows: [], closedRows: [],
});
add("ingest", "ingest-closed-win-est", "CLOSED 승자는 진입원가+실현으로 추정 수집", {
  pending: [], history: [], closed: [],
  winRows: [], lostRows: [],
  closedRows: [{
    condition_id: CID_A, token_id: "t1", slug: "ma", current_price: 1,
    current_value: 0, entry_cost_usdc: 5, realized_pnl: 2,
  }],
});
add("ingest", "ingest-closed-win-keeps-value", "CLOSED 승자인데 현재 가치가 있으면 그 값 유지", {
  pending: [], history: [], closed: [],
  winRows: [], lostRows: [],
  closedRows: [{
    condition_id: CID_A, token_id: "t1", slug: "ma", current_price: 1,
    current_value: 3, entry_cost_usdc: 5, realized_pnl: 2,
  }],
});
add("ingest", "ingest-closed-not-win", "CLOSED인데 승이 아니면 수집 안 함", {
  pending: [], history: [], closed: [],
  winRows: [], lostRows: [],
  closedRows: [{
    condition_id: CID_A, token_id: "t1", slug: "ma", current_price: 0.5,
    current_value: 0, entry_cost_usdc: 5, realized_pnl: -1,
  }],
});

// ── boughtCorrupted / isFailsafe ─────────────────────────────
add("corrupted", "corrupted-ok", "배열이면 정상", { raw: "[\"a\"]" });
add("corrupted", "corrupted-object", "객체면 손상", { raw: "{\"x\": 1}" });
add("corrupted", "corrupted-bad-json", "파싱 실패면 손상", { raw: "not-json" });
add("corrupted", "corrupted-null", "없으면 정상", { raw: null });
add("corrupted", "corrupted-empty-array", "빈 배열은 정상", { raw: "[]" });

add("failsafe", "failsafe-403", "403 감지", { text: "HTTP 403 Forbidden" });
add("failsafe", "failsafe-signature", "invalid signature 감지", { text: "invalid signature detected" });
add("failsafe", "failsafe-clean", "정상 텍스트", { text: "all good" });
add("failsafe", "failsafe-null", "null은 안전", { text: null });
add("failsafe", "failsafe-not-allowed", "not allowed 감지", { text: "user not allowed" });

writeFileSync(join(here, "fixtures.json"), JSON.stringify({
  meta: {
    target: "app/src/engine.js 판정 로직 (scan·verify·stoploss·redeem·fill·reconcile·sizing·settled)",
    nowMs: NOW,
    note: "nowMs 고정으로 날짜 판정을 결정적으로 만들었다. __NaN__·__Inf__·__-Inf__는 JSON에 없는 JS 수치의 센티넬 표기다.",
  },
  cases,
}, null, 1) + "\n");
console.log(`fixtures.json 생성: ${cases.length} 케이스`);
