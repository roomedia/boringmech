// gen_golden4.mjs — 네이티브 이관 4단계(쓰기 경로) 골든·fixture 생성기.
//
// 기대값은 전부 현행 JS 원본에서 만든다:
// - 주문 본문: SDK ExchangeOrderBuilderV2/V3로 서명 → orderToJsonV2 +
//   JSON.stringify (POST /order 본문 바이트 그대로)
// - L2 헤더: SDK createL2Headers (ClobClient 인스턴스, 네트워크 없음)
// - judgeFill·stopLossVerdict·pickOutcomeIndex·roundTick·손절 체결 산출:
//   app/src/engine.js 원문을 추출해 그대로 실행
// - 브리지 shaping·재시도: app/src/order-bridge.js를 직접 import해 가짜
//   Bridge(postSync)로 실행
// - 리딤 제출 본문: engine.js submitRedeemBatch의 객체 리터럴 순서를
//   JSON.stringify로 재현 (서명은 고정 테스트 서명 문자열)
//
// 결정성: 키·salt·timestamp·nonce 전부 고정. 네트워크 호출 없음.
// 실행: node gen_golden4.mjs   (이 디렉토리에서)

import { readFileSync, writeFileSync } from "node:fs";

import { ExchangeOrderBuilderV2, ExchangeOrderBuilderV3 } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-utils/exchangeOrderBuilderV2.js";
import { getMarketOrderRawAmounts } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-builder/helpers/getMarketOrderRawAmounts.js";
import { ROUNDING_CONFIG } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-builder/helpers/roundingConfig.js";
import { orderToJsonV2 } from "../app/node_modules/@polymarket/clob-client-v2/dist/types/ordersV2.js";
import { getContractConfig } from "../app/node_modules/@polymarket/clob-client-v2/dist/config.js";
import { bytes32Zero } from "../app/node_modules/@polymarket/clob-client-v2/dist/constants.js";
import { createL2Headers } from "../app/node_modules/@polymarket/clob-client-v2/dist/headers/index.js";
import { Side } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-utils/model/side.js";
import { SignatureTypeV2 } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-utils/model/signatureTypeV2.js";
import { privateKeyToAccount } from "../app/node_modules/viem/_esm/accounts/privateKeyToAccount.js";
import { createWalletClient } from "../app/node_modules/viem/_esm/clients/createWalletClient.js";
import { http } from "../app/node_modules/viem/_esm/clients/transports/http.js";
import { polygon } from "../app/node_modules/viem/_esm/chains/definitions/polygon.js";
import { parseUnits } from "../app/node_modules/viem/_esm/utils/unit/parseUnits.js";

import { deriveUupsDepositWallet } from "../app/src/depositWallet.js";
import { bridgePost } from "../app/src/order-bridge.js";

// shapeNativeResult는 모듈 내부 함수라 원문을 추출해 실행한다
const bridgeSrc = readFileSync(new URL("../app/src/order-bridge.js", import.meta.url), "utf8");
function extractBridgeFn(src, name) {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`order-bridge.js에서 ${name}을 찾지 못함`);
  const end = src.indexOf("\n}", start);
  return src.slice(start, end + 2);
}
const shapeNativeResult = new Function(
  `${extractBridgeFn(bridgeSrc, "shapeNativeResult")}\nreturn shapeNativeResult;`,
)();

// ---------------------------------------------------------------------------
// 고정 테스트 값 (1단계 골든과 동일 — 실계정 무관)
// ---------------------------------------------------------------------------
const TEST_PRIVATE_KEY = "0x" + "11".repeat(32);
const CHAIN_ID = 137;
const FIXED_SALT = "1234567890123456789";
const FIXED_ORDER_TIMESTAMP = "1760000000000";
const FIXED_L2_TIMESTAMP = 1760000123; // 초
const TEST_API_KEY = "11111111-2222-3333-4444-555555555555";
const TEST_SECRET = "dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==";
const TEST_PASSPHRASE = "test-passphrase-0000";

const account = privateKeyToAccount(TEST_PRIVATE_KEY);
const EOA = account.address;
const wallet = createWalletClient({
  account,
  chain: polygon,
  transport: http("http://127.0.0.1:9/unused-rpc"),
});
const FUNDER = deriveUupsDepositWallet(EOA);

const TOKEN_A = "5217830831767552351603457242192184817666125891744021388456190691134219482513";
const TOKEN_B = "9800787273216002583860617742040128587601631587605531883592612768894865019786";

// ---------------------------------------------------------------------------
// 1. 주문 본문 — SDK 빌더 서명 + orderToJsonV2 직렬화
// ---------------------------------------------------------------------------
const contracts = getContractConfig(CHAIN_ID);

function amountsFor(side, amount, price, tickSize) {
  const rc = ROUNDING_CONFIG[tickSize];
  const { rawMakerAmt, rawTakerAmt } = getMarketOrderRawAmounts(
    side === "BUY" ? Side.BUY : Side.SELL, amount, price, rc,
  );
  return {
    makerAmount: parseUnits(String(rawMakerAmt), 6).toString(),
    takerAmount: parseUnits(String(rawTakerAmt), 6).toString(),
  };
}

async function orderBodyCase({ name, version, negRisk, side, amount, price, tickSize, tokenId, orderType }) {
  const amt = amountsFor(side, amount, price, tickSize);
  const exchangeContract =
    version === 3 ? contracts.exchangeV3 : negRisk ? contracts.negRiskExchangeV2 : contracts.exchangeV2;
  const orderData = {
    maker: FUNDER,
    tokenId,
    makerAmount: amt.makerAmount,
    takerAmount: amt.takerAmount,
    side,
    signer: FUNDER,
    signatureType: SignatureTypeV2.POLY_1271,
    timestamp: FIXED_ORDER_TIMESTAMP,
    metadata: bytes32Zero,
    builder: bytes32Zero,
  };
  const builder =
    version === 3
      ? new ExchangeOrderBuilderV3(exchangeContract, CHAIN_ID, wallet, () => FIXED_SALT)
      : new ExchangeOrderBuilderV2(exchangeContract, CHAIN_ID, wallet, () => FIXED_SALT);
  const signed = await builder.buildSignedOrder(orderData);
  const body = JSON.stringify(orderToJsonV2(signed, TEST_API_KEY, orderType));
  return {
    name,
    input: {
      version, negRisk, tokenId, side, amount, price, tickSize,
      maker: FUNDER, signer: FUNDER, signatureType: 3,
      salt: FIXED_SALT, timestamp: FIXED_ORDER_TIMESTAMP,
      metadata: bytes32Zero, builderCode: bytes32Zero,
      owner: TEST_API_KEY, orderType,
    },
    expected: { signature: signed.signature, body },
  };
}

const orderBodies = [];
orderBodies.push(await orderBodyCase({ name: "v2_buy_fok", version: 2, negRisk: false, side: "BUY", amount: 5, price: 0.75, tickSize: "0.01", tokenId: TOKEN_A, orderType: "FOK" }));
orderBodies.push(await orderBodyCase({ name: "v2_sell_fok", version: 2, negRisk: false, side: "SELL", amount: 6.66, price: 0.72, tickSize: "0.01", tokenId: TOKEN_A, orderType: "FOK" }));
orderBodies.push(await orderBodyCase({ name: "v2_negrisk_sell_fok", version: 2, negRisk: true, side: "SELL", amount: 12.5, price: 0.335, tickSize: "0.005", tokenId: TOKEN_B, orderType: "FOK" }));
orderBodies.push(await orderBodyCase({ name: "v3_buy_fok", version: 3, negRisk: false, side: "BUY", amount: 5, price: 0.75, tickSize: "0.01", tokenId: TOKEN_A, orderType: "FOK" }));
orderBodies.push(await orderBodyCase({ name: "v2_buy_gtc", version: 2, negRisk: false, side: "BUY", amount: 5, price: 0.75, tickSize: "0.01", tokenId: TOKEN_A, orderType: "GTC" }));
orderBodies.push(await orderBodyCase({ name: "v2_sell_tick0001", version: 2, negRisk: false, side: "SELL", amount: 3.21, price: 0.7135, tickSize: "0.0001", tokenId: TOKEN_B, orderType: "FOK" }));

// ---------------------------------------------------------------------------
// 2. L2 헤더 — SDK createL2Headers (서명 시각 고정)
// ---------------------------------------------------------------------------
const l2Creds = { key: TEST_API_KEY, secret: TEST_SECRET, passphrase: TEST_PASSPHRASE };
const l2Headers = [];
{
  const body = orderBodies[0].expected.body;
  const h = await createL2Headers(wallet, l2Creds,
    { method: "POST", requestPath: "/order", body }, FIXED_L2_TIMESTAMP);
  l2Headers.push({
    name: "post_order",
    input: { method: "POST", path: "/order", bodyCase: "v2_buy_fok", timestamp: FIXED_L2_TIMESTAMP },
    expected: h,
  });
  const h2 = await createL2Headers(wallet, l2Creds,
    { method: "GET", requestPath: "/data/trades" }, FIXED_L2_TIMESTAMP);
  l2Headers.push({
    name: "get_trades",
    input: { method: "GET", path: "/data/trades", timestamp: FIXED_L2_TIMESTAMP },
    expected: h2,
  });
}

// ---------------------------------------------------------------------------
// 3. engine.js 원본 함수 추출 실행 — judgeFill·판정·틱·체결 산출
// ---------------------------------------------------------------------------
const engineSrc = readFileSync(new URL("../app/src/engine.js", import.meta.url), "utf8");
function extractFn(src, name) {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`engine.js에서 ${name}을 찾지 못함`);
  const end = src.indexOf("\n}", start);
  return src.slice(start, end + 2).replace(/^export /, "");
}
const round2Src = "const round2 = (x) => Math.round((x + Number.EPSILON) * 100) / 100;";
const engineFns = new Function(
  "STOP_DROP", "DECIDED_HIGH", "DECIDED_LOW",
  `${round2Src}\n${extractFn(engineSrc, "judgeFill")}\n${extractFn(engineSrc, "stopLossVerdict")}\n` +
  `${extractFn(engineSrc, "pickOutcomeIndex")}\n${extractFn(engineSrc, "roundTick")}\n` +
  `return { judgeFill, stopLossVerdict, pickOutcomeIndex, roundTick, round2 };`,
)(0.25, 0.97, 0.03);
const { judgeFill, stopLossVerdict, pickOutcomeIndex, roundTick, round2 } = engineFns;

// 체결 산출은 executeStopLossSellLocked 본문의 네 줄을 원문에서 뽑아 실행한다
function extractLine(src, marker) {
  const line = src.split("\n").find((l) => l.includes(marker));
  if (!line) throw new Error(`engine.js에서 ${marker} 줄을 찾지 못함`);
  return line.trim();
}
const fillMathSrc = [
  extractLine(engineSrc, "const soldShares = Number(resp.makingAmount)"),
  extractLine(engineSrc, "const proceeds = round2(Number(resp.takingAmount)"),
  extractLine(engineSrc, "const exitPrice = soldShares > 0"),
  extractLine(engineSrc, "const realized = round2(proceeds - soldShares * entry)"),
].join("\n");
const fillMath = new Function("round2", "resp", "shares", "current", "entry",
  `${fillMathSrc}\nreturn { soldShares, proceeds, exitPrice, realized };`);

const judgeFillCases = [
  { name: "matched_full", resp: { success: true, status: "matched", makingAmount: "5", takingAmount: "6.66", transactionsHashes: ["0xabc"], tradeIDs: ["t1"], orderID: "o1" } },
  { name: "filled_word", resp: { success: true, status: "filled" } },
  { name: "success_hash_only", resp: { success: true, status: "", transactionsHashes: ["0x1"] } },
  { name: "success_tradeids_only", resp: { success: true, tradeIDs: ["a", "b"] } },
  { name: "success_making_only", resp: { success: true, makingAmount: "2.5" } },
  { name: "success_no_evidence", resp: { success: true, status: "live", makingAmount: "0", takingAmount: "0", transactionsHashes: [], tradeIDs: [] } },
  { name: "success_delayed", resp: { success: true, status: "delayed" } },
  { name: "rejected_flag", resp: { success: false, status: "rejected" } },
  { name: "error_string", resp: { error: "insufficient funds" } },
  { name: "error_zero_string", resp: { error: "0" } },
  { name: "error_empty_string", resp: { error: "" } },
  { name: "error_object", resp: { error: { code: 500 } } },
  { name: "error_msg_field", resp: { errorMsg: "bad tick size" } },
  { name: "error_and_msg", resp: { error: "0", errorMsg: "should not reach" } },
  { name: "empty_object", resp: {} },
  { name: "null_resp", resp: null },
  { name: "string_resp", resp: "weird" },
  { name: "matched_upper", resp: { success: true, status: "MATCHED" } },
  { name: "taking_only_not_fill", resp: { success: true, takingAmount: "9.99" } },
  { name: "error_beats_success", resp: { success: true, status: "matched", error: "post only" } },
].map((c) => ({ ...c, expected: judgeFill(c.resp) }));

const verdictCases = [
  [0.75, 0.50], [0.75, 0.51], [0.75, 0.97], [0.75, 0.03], [0.75, 0.02],
  [0.75, NaN], [NaN, 0.5], [0.30, 0.05], [0.30, 0.049], [0.99, 0.74],
  [0.99, 0.73], [0.26, 0.01], [0.26, 0.009], [0.5, Infinity], [0.5, 0.25],
].map(([entry, current]) => ({
  entry: String(entry), current: String(current),
  expected: stopLossVerdict(entry, current),
}));

const outcomeIndexCases = [
  { outcomes: ["Yes", "No"], row: { outcome: "No" } },
  { outcomes: ["Yes", "No"], row: { outcome: " yes " } },
  { outcomes: ["Yes", "No"], row: { outcome: "Maybe", outcome_index: 1 } },
  { outcomes: ["Yes", "No"], row: { outcome_index: 0 } },
  { outcomes: ["Yes", "No"], row: {} },
  { outcomes: [], row: { outcome_index: 2 } },
  { outcomes: ["Up", "Down"], row: { outcome: "DOWN" } },
  { outcomes: ["Yes", "No"], row: { outcome_index: 1.5 } },
  { outcomes: ["Yes", "No"], row: { outcome_index: -1 } },
  { outcomes: null, row: { outcome_index: 3 } },
].map((c) => ({ ...c, expected: pickOutcomeIndex(c.outcomes, c.row) }));

const roundTickCases = [
  [0.735, 0.01], [0.7349, 0.01], [0.125, 0.001], [0.9995, 0.001],
  [0.5, 0.0001], [0.1234565, 0.0001], [0.07, 0.005], [0.333, 0.0025],
  [0.985, 0.01], [0.015, 0.01], [0.0049, 0.01],
].map(([price, tick]) => ({
  price: String(price), tick: String(tick),
  expected: String(roundTick(price, tick)),
}));

const fillMathCases = [
  { resp: { makingAmount: "6.66", takingAmount: "4.7952" }, shares: 6.66, current: 0.72, entry: 0.95 },
  { resp: { makingAmount: "0", takingAmount: "0" }, shares: 5, current: 0.5, entry: 0.8 },
  { resp: {}, shares: 5, current: 0.5, entry: 0.8 },
  { resp: { makingAmount: "3.333", takingAmount: "1.111" }, shares: 5, current: 0.4, entry: 0.75 },
  { resp: { takingAmount: "2.5" }, shares: 4, current: 0.6, entry: 0.9 },
  { resp: { makingAmount: "10" }, shares: 10, current: 0.33, entry: 0.61 },
].map((c) => ({
  ...c,
  shares: String(c.shares), current: String(c.current), entry: String(c.entry),
  expected: (() => {
    const r = fillMath(round2, c.resp, c.shares, c.current, c.entry);
    return {
      soldShares: String(r.soldShares), proceeds: String(r.proceeds),
      exitPrice: String(r.exitPrice), realized: String(r.realized),
    };
  })(),
}));

// ---------------------------------------------------------------------------
// 4. order-bridge — shaping + 재시도 (가짜 Bridge)
// ---------------------------------------------------------------------------
function fakeBridge(outcomes) {
  let calls = 0;
  return {
    bridge: {
      postSync() {
        const o = outcomes[Math.min(calls, outcomes.length - 1)];
        calls += 1;
        return JSON.stringify(o);
      },
    },
    calls: () => calls,
  };
}

async function shapeCase(name, raw) {
  try {
    const v = shapeNativeResult(raw);
    return { name, raw, expected: { ok: v } };
  } catch (e) {
    return { name, raw, expected: { error: String((e && e.message) || e) } };
  }
}
const bridgeShape = [];
bridgeShape.push(await shapeCase("ok_object", { status: 200, body: '{"success":true,"orderID":"o1"}', error: null }));
bridgeShape.push(await shapeCase("ok_string_body", { status: 200, body: "plain-ok", error: null }));
bridgeShape.push(await shapeCase("transport_passthrough", { status: 0, body: "", error: "네트워크 오류 (연결 실패)" }));
bridgeShape.push(await shapeCase("err_json_error_string", { status: 400, body: '{"error":"bad tick"}', error: null }));
bridgeShape.push(await shapeCase("err_json_error_object", { status: 400, body: '{"error":{"code":7}}', error: null }));
bridgeShape.push(await shapeCase("err_json_errormsg", { status: 422, body: '{"errorMsg":"no match"}', error: null }));
bridgeShape.push(await shapeCase("err_json_no_fields", { status: 400, body: '{"foo":1}', error: null }));
bridgeShape.push(await shapeCase("err_plain_text", { status: 500, body: "server boom", error: null }));
bridgeShape.push(await shapeCase("err_empty_body", { status: 503, body: "", error: null }));

async function retryCase(name, outcomes, retryOnError) {
  const fb = fakeBridge(outcomes);
  try {
    const v = await bridgePost("https://clob.polymarket.com/order", { data: "{}" }, retryOnError, fb.bridge);
    return { name, outcomes, retryOnError, expected: { calls: fb.calls(), ok: v } };
  } catch (e) {
    return { name, outcomes, retryOnError, expected: { calls: fb.calls(), error: String((e && e.message) || e) } };
  }
}
const ok200 = { status: 200, body: '{"success":true,"status":"matched"}', error: null };
const netFail = { status: 0, body: "", error: "연결 시간 초과" };
const srv503 = { status: 503, body: "", error: null };
const bridgeRetry = [];
bridgeRetry.push(await retryCase("net_then_ok", [netFail, ok200], true));
bridgeRetry.push(await retryCase("srv_then_ok", [srv503, ok200], true));
bridgeRetry.push(await retryCase("net_twice", [netFail, netFail], true));
bridgeRetry.push(await retryCase("client_error_no_retry", [{ status: 400, body: '{"error":"bad"}', error: null }, ok200], true));
bridgeRetry.push(await retryCase("no_retry_flag", [srv503, ok200], false));
bridgeRetry.push(await retryCase("ok_first_try", [ok200], true));

// ---------------------------------------------------------------------------
// 5. 리딤 제출 본문 — engine.js 객체 리터럴 순서 재현
// ---------------------------------------------------------------------------
const redeemCalls = [
  { target: "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045", data: "0x1234abcd" },
  { target: "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045", data: "0x" },
];
const redeemBodyObj = {
  type: "WALLET",
  from: EOA,
  to: "0x00000000000Fb5C9ADea0298D729A0CB3823Cc07",
  nonce: "7",
  signature: "0x" + "ab".repeat(65),
  metadata: "Redeem positions for condition 0xcond123",
  depositWalletParams: {
    depositWallet: FUNDER,
    deadline: "1760000600",
    calls: redeemCalls.map((c) => ({ target: c.target, value: "0", data: c.data })),
  },
};
const redeemBody = {
  input: {
    eoa: EOA, funder: FUNDER, nonce: "7",
    signature: "0x" + "ab".repeat(65),
    conditionId: "0xcond123", deadline: "1760000600",
    calls: redeemCalls,
  },
  expected: JSON.stringify(redeemBodyObj),
};

// ---------------------------------------------------------------------------
const golden = {
  meta: {
    generator: "gen_golden4.mjs",
    sdk: "@polymarket/clob-client-v2 (app/node_modules)",
    eoa: EOA,
    funder: FUNDER,
    privateKey: TEST_PRIVATE_KEY,
    secret: TEST_SECRET,
    passphrase: TEST_PASSPHRASE,
  },
  orderBodies,
  l2Headers,
  judgeFill: judgeFillCases,
  stopLossVerdict: verdictCases,
  pickOutcomeIndex: outcomeIndexCases,
  roundTick: roundTickCases,
  fillMath: fillMathCases,
  bridgeShape,
  bridgeRetry,
  redeemBody,
};
writeFileSync(new URL("./golden4.json", import.meta.url), JSON.stringify(golden, null, 2) + "\n");
console.log(
  `orderBodies=${orderBodies.length} l2Headers=${l2Headers.length} judgeFill=${judgeFillCases.length} ` +
  `verdict=${verdictCases.length} outcomeIndex=${outcomeIndexCases.length} roundTick=${roundTickCases.length} ` +
  `fillMath=${fillMathCases.length} bridgeShape=${bridgeShape.length} bridgeRetry=${bridgeRetry.length} redeemBody=1`,
);
