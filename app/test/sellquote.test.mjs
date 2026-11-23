// 수동 매도 확인 팝업 견적 가드 — 표시 가격(스냅샷가)과 실제 체결가
// (최고 매수호가)가 달라, 팝업을 여는 시점에 주문장을 조회해 실제
// 매도가와 예상 수령액을 보여주는 경로를 고정한다 (v0.3.43).
// 조회 실패 폴백(스냅샷가 참고가 + 체결가 변동 문구, 매도 비차단)과
// 주문 경로 측정 기능 제거 상태도 함께 검증한다.
globalThis.window = {};
const { bestBidFromBook, sellQuoteOf } = await import("../src/engine.js");
const { readFileSync } = await import("node:fs");

let passed = 0;
let failed = 0;
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
  } else {
    failed++;
    console.error(`FAIL ${label}: expected ${e}, got ${a}`);
  }
}
function ok(cond, label) {
  eq(Boolean(cond), true, label);
}

// ── bestBidFromBook — 최고 매수호가 추출 ──────────────────────
{
  const book = { bids: [
    { price: "0.990", size: "10" },
    { price: "0.998", size: "5" },
    { price: "0.995", size: "7" },
  ] };
  eq(bestBidFromBook(book), 0.998, "sellquote: best bid is max");
  eq(bestBidFromBook({ bids: [{ price: "0.998", size: "0" }, { price: "0.990", size: "3" }] }),
    0.99, "sellquote: zero-size bid skipped");
  ok(Number.isNaN(bestBidFromBook({ bids: [] })), "sellquote: no bids NaN");
  ok(Number.isNaN(bestBidFromBook(null)), "sellquote: null book NaN");
  ok(Number.isNaN(bestBidFromBook({})), "sellquote: missing bids NaN");
  ok(Number.isNaN(bestBidFromBook({ bids: [{ price: "x", size: "1" }] })),
    "sellquote: invalid price NaN");
}

// ── sellQuoteOf — 예상 수령액(수수료 전)과 수수료 ─────────────
{
  const q = sellQuoteOf(10, 0.05, 0.998);
  eq(q.gross, 10 * 0.998, "sellquote: gross is shares x price");
  eq(q.fee, 10 * 0.05 * 0.998 * (1 - 0.998), "sellquote: fee formula");
  eq(q.feeKnown, true, "sellquote: fee known");
  const qu = sellQuoteOf(10, null, 0.9);
  eq(qu.fee, 10 * 0.07 * 0.9 * (1 - 0.9), "sellquote: unknown rate uses 0.07 cap");
  eq(qu.feeKnown, false, "sellquote: fee unknown flagged");
  eq(sellQuoteOf(0, 0.05, 0.9), null, "sellquote: zero shares null");
  eq(sellQuoteOf(10, 0.05, 0), null, "sellquote: zero price null");
  eq(sellQuoteOf("x", 0.05, 0.9), null, "sellquote: invalid shares null");
}

// ── 팝업 배선 (index.html 정적 대조) ──────────────────────────
{
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  ok(html.includes("https://clob.polymarket.com/book?token_id="),
    "sellquote: popup fetches order book at open");
  ok(html.includes("fetchSellBook(p.token_id)"), "sellquote: fetch wired into openSellPopup");
  ok(html.includes("실제 매도가 (최고 매수호가)"), "sellquote: best bid row shown");
  ok(html.includes("예상 수령액 (수수료 전)"), "sellquote: pre-fee proceeds labeled");
  ok(html.includes("참고가 (스냅샷가)"), "sellquote: snapshot fallback row");
  ok(html.includes("실제 체결가는 주문장 사정에 따라 달라질 수 있습니다"),
    "sellquote: fallback warns fill price may differ");
  ok(html.includes('id="sellNote"'), "sellquote: dynamic note element");
  // 조회 실패가 매도를 막지 않는다 — 제출 경로는 종전 그대로
  // btnSellConfirm 핸들러의 bm_manual_sell_req 기록뿐이다.
  ok(html.includes("AndroidBridge.putDurable('bm_manual_sell_req'"),
    "sellquote: manual sell request path unchanged");
}

// ── 주문 경로 측정 기능 제거 가드 (v0.3.43) ───────────────────
{
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  ok(!html.includes("btnOrderTestStart"), "removal: no measure start button");
  ok(!html.includes("bm_orderpath_test_req"), "removal: viewer never writes test request");
  ok(!html.includes("bm_orderpath_test_status"), "removal: no test status display");
  ok(!html.includes("renderOrderPathTestStatus"), "removal: no test status renderer");
  ok(!html.includes("<h2>주문 경로 측정</h2>"), "removal: no measure card");
  ok(!src.includes("processOrderPathTest"), "removal: engine does not consume test request");
  ok(!src.includes("runOrderPathTest"), "removal: no test routine in engine");
  ok(!src.includes("bm_orderpath_test_req"), "removal: engine has no test request key");
}

console.log(`sellquote tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
