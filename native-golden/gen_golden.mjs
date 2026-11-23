// gen_golden.mjs — boringmech 네이티브(Java) 이관용 골든 데이터 생성기.
//
// 현행 JS SDK(@polymarket/clob-client-v2@1.2.1 + viem@2.57.19)의 서명·금액
// 출력을 고정 입력으로 재생성해 golden.json에 남긴다. Java 구현은 이 파일의
// 입력만 보고 기대 출력을 바이트 단위로 재현해야 한다.
//
// 결정성: 개인키·salt·timestamp·nonce·deadline을 전부 고정값으로 박았고,
// 네트워크 호출은 없다 (지갑 클라이언트의 transport는 죽은 로컬 주소 —
// signTypedData는 로컬 서명이라 RPC를 타지 않는다).
// 재실행하면 동일한 golden.json이 나와야 한다.
//
// 실행: node gen_golden.mjs   (이 디렉토리에서)

import { writeFileSync } from "node:fs";

// --- SDK 내부 (app/node_modules의 dist 파일을 직접 참조) ---
import { ExchangeOrderBuilderV2, ExchangeOrderBuilderV3 } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-utils/exchangeOrderBuilderV2.js";
import { getMarketOrderRawAmounts } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-builder/helpers/getMarketOrderRawAmounts.js";
import { getOrderRawAmounts } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-builder/helpers/getOrderRawAmounts.js";
import { ROUNDING_CONFIG } from "../app/node_modules/@polymarket/clob-client-v2/dist/order-builder/helpers/roundingConfig.js";
import { buildClobEip712Signature } from "../app/node_modules/@polymarket/clob-client-v2/dist/signing/eip712.js";
import { buildPolyHmacSignature } from "../app/node_modules/@polymarket/clob-client-v2/dist/signing/hmac.js";
import { orderToJsonV2 } from "../app/node_modules/@polymarket/clob-client-v2/dist/types/ordersV2.js";
import { getContractConfig, COLLATERAL_TOKEN_DECIMALS } from "../app/node_modules/@polymarket/clob-client-v2/dist/config.js";
import { bytes32Zero } from "../app/node_modules/@polymarket/clob-client-v2/dist/constants.js";

// --- viem (SDK가 해석하는 것과 같은 _esm 빌드) ---
import { privateKeyToAccount } from "../app/node_modules/viem/_esm/accounts/privateKeyToAccount.js";
import { createWalletClient } from "../app/node_modules/viem/_esm/clients/createWalletClient.js";
import { http } from "../app/node_modules/viem/_esm/clients/transports/http.js";
import { polygon } from "../app/node_modules/viem/_esm/chains/definitions/polygon.js";
import { parseAbi } from "../app/node_modules/abitype/dist/esm/human-readable/parseAbi.js"; // viem이 재수출하는 것과 동일
import { encodeFunctionData } from "../app/node_modules/viem/_esm/utils/abi/encodeFunctionData.js";
import { parseUnits } from "../app/node_modules/viem/_esm/utils/unit/parseUnits.js";

// --- 앱 자체 로직 (funder 파생) ---
import { deriveUupsDepositWallet, deriveBeaconDepositWallet } from "../app/src/depositWallet.js";

// ---------------------------------------------------------------------------
// 고정 테스트 값 — 전부 실계정과 무관한 테스트 전용이다.
// ---------------------------------------------------------------------------
const TEST_PRIVATE_KEY = "0x" + "11".repeat(32); // EOA 0x19E7E376E7C213B7E7e7e46cc70A5dD086DAff2A
const CHAIN_ID = 137;
const SIGNATURE_TYPE = 3; // POLY_1271
const FIXED_SALT = "1234567890123456789"; // 빌더 생성자에 주입 (SDK는 원래 무작위)
const FIXED_ORDER_TIMESTAMP = "1760000000000"; // 주문 timestamp (ms 문자열)
const FIXED_L1_TIMESTAMP = 1760000000; // L1/L2 공용 (초)
const FIXED_BATCH_NONCE = "7";
const FIXED_BATCH_DEADLINE = "1760000600"; // 초
const TEST_API_KEY = "11111111-2222-3333-4444-555555555555"; // L2 본문 owner용 가짜 키

const account = privateKeyToAccount(TEST_PRIVATE_KEY);
const EOA = account.address;
// engine.js makeSigner와 같은 구성. transport는 서명에 쓰이지 않는다.
const wallet = createWalletClient({
  account,
  chain: polygon,
  transport: http("http://127.0.0.1:9/unused-rpc"),
});

const FUNDER_UUPS = deriveUupsDepositWallet(EOA);
const FUNDER_BEACON = deriveBeaconDepositWallet(EOA);
const FUNDER = FUNDER_UUPS; // 주문·배치 케이스의 maker/signer/wallet으로 사용

const TOKEN_A = "5217830831767552351603457242192184817666125891744021388456190691134219482513";
const TOKEN_B = "9800787273216002583860617742040128587601631587605531883592612768894865019786";

// ---------------------------------------------------------------------------
// 1. 금액 산출 케이스 — SDK getMarketOrderRawAmounts + parseUnits(…, 6)
//    (buildMarketOrderCreationArgs가 하는 변환과 동일)
// ---------------------------------------------------------------------------
const AMOUNT_CASES = [
  // [이름, side, amount, price, tickSize]
  ["buy_tick0.01_price_on_tick", "BUY", "5", "0.75", "0.01"],
  ["buy_tick0.01_price_rounds_down", "BUY", "5", "0.755", "0.01"],
  ["buy_tick0.01_repeating_taker", "BUY", "5", "0.73", "0.01"],
  ["buy_tick0.1", "BUY", "10", "0.7", "0.1"],
  ["buy_tick0.1_price_rounds_down", "BUY", "10", "0.79", "0.1"],
  ["buy_tick0.005", "BUY", "5", "0.755", "0.005"],
  ["buy_tick0.0025", "BUY", "5", "0.7525", "0.0025"],
  ["buy_tick0.001_near_one", "BUY", "5", "0.999", "0.001"],
  ["buy_tick0.0001", "BUY", "5", "0.1234", "0.0001"],
  ["buy_amount_excess_decimals", "BUY", "5.009", "0.75", "0.01"],
  ["buy_small_amount", "BUY", "2.87", "0.61", "0.01"],
  ["buy_band_low", "BUY", "5", "0.60", "0.01"],
  ["buy_tick0.001_price_rounds_down", "BUY", "5", "0.7499", "0.001"],
  ["sell_tick0.01_exact", "SELL", "6.66", "0.72", "0.01"],
  ["sell_shares_excess_decimals", "SELL", "6.666", "0.73", "0.01"],
  ["sell_tick0.005", "SELL", "13.37", "0.755", "0.005"],
  ["sell_tick0.0025", "SELL", "3.33", "0.7525", "0.0025"],
  ["sell_tick0.1_price_rounds_down", "SELL", "10", "0.79", "0.1"],
  ["sell_tick0.01_high_price", "SELL", "100", "0.99", "0.01"],
  ["sell_tick0.0001", "SELL", "7.77", "0.1234", "0.0001"],
];

function amountsFor(side, amount, price, tickSize) {
  const roundConfig = ROUNDING_CONFIG[tickSize];
  const { rawMakerAmt, rawTakerAmt } = getMarketOrderRawAmounts(
    side,
    Number(amount),
    Number(price),
    roundConfig,
  );
  return {
    roundConfig,
    rawMakerAmt: String(rawMakerAmt),
    rawTakerAmt: String(rawTakerAmt),
    makerAmount: parseUnits(rawMakerAmt.toString(), COLLATERAL_TOKEN_DECIMALS).toString(),
    takerAmount: parseUnits(rawTakerAmt.toString(), COLLATERAL_TOKEN_DECIMALS).toString(),
  };
}

const amounts = AMOUNT_CASES.map(([name, side, amount, price, tickSize]) => ({
  name,
  input: { side, amount, price, tickSize },
  expected: amountsFor(side, amount, price, tickSize),
}));

// ---------------------------------------------------------------------------
// 1b. 지정가 금액 케이스 — SDK getOrderRawAmounts + parseUnits(…, 6)
//     (buildOrderCreationArgs가 하는 변환과 동일. 입력은 금액이 아니라 수량)
// ---------------------------------------------------------------------------
const LIMIT_AMOUNT_CASES = [
  // [이름, side, size, price, tickSize]
  ["buy_test_price_min", "BUY", "5", "0.01", "0.01"],
  ["buy_tick0.005_fractional", "BUY", "5", "0.335", "0.005"],
  ["sell_tick0.005_fractional", "SELL", "12.5", "0.335", "0.005"],
  ["buy_tick0.01_mid", "BUY", "100", "0.48", "0.01"],
  ["buy_size_excess_decimals", "BUY", "5.009", "0.5", "0.01"],
  ["sell_test_price_min", "SELL", "5", "0.01", "0.01"],
];

function limitAmountsFor(side, size, price, tickSize) {
  const roundConfig = ROUNDING_CONFIG[tickSize];
  const { rawMakerAmt, rawTakerAmt } = getOrderRawAmounts(
    side,
    Number(size),
    Number(price),
    roundConfig,
  );
  return {
    roundConfig,
    rawMakerAmt: String(rawMakerAmt),
    rawTakerAmt: String(rawTakerAmt),
    makerAmount: parseUnits(rawMakerAmt.toString(), COLLATERAL_TOKEN_DECIMALS).toString(),
    takerAmount: parseUnits(rawTakerAmt.toString(), COLLATERAL_TOKEN_DECIMALS).toString(),
  };
}

const limitAmounts = LIMIT_AMOUNT_CASES.map(([name, side, size, price, tickSize]) => ({
  name,
  input: { side, size, price, tickSize },
  expected: limitAmountsFor(side, size, price, tickSize),
}));

// ---------------------------------------------------------------------------
// 2. 주문 서명 케이스 (POLY_1271)
//    buildMarketOrderCreationArgs는 timestamp를 Date.now()로 찍으므로 쓰지
//    않고, 그 내부 변환(금액 산출 + 필드 조립)만 고정 timestamp로 재현한 뒤
//    실제 빌더 클래스(ExchangeOrderBuilderV2/V3)에 salt 생성기를 주입해
//    서명시킨다. 빌더·서명 경로는 SDK 코드 그대로다.
// ---------------------------------------------------------------------------
const contracts = getContractConfig(CHAIN_ID);

async function orderCase({ name, version, negRisk, side, amount, price, tickSize, tokenId }) {
  const amt = amountsFor(side, amount, price, tickSize);
  const exchangeContract =
    version === 3 ? contracts.exchangeV3 : negRisk ? contracts.negRiskExchangeV2 : contracts.exchangeV2;
  const domainVersion = version === 3 ? "3" : "2";
  const orderData = {
    maker: FUNDER,
    tokenId,
    makerAmount: amt.makerAmount,
    takerAmount: amt.takerAmount,
    side,
    signer: FUNDER, // POLY_1271이면 signer = maker (createMarketOrder 내부 규칙)
    signatureType: SIGNATURE_TYPE,
    timestamp: FIXED_ORDER_TIMESTAMP,
    metadata: bytes32Zero,
    builder: bytes32Zero,
  };
  const builder =
    version === 3
      ? new ExchangeOrderBuilderV3(exchangeContract, CHAIN_ID, wallet, () => FIXED_SALT)
      : new ExchangeOrderBuilderV2(exchangeContract, CHAIN_ID, wallet, () => FIXED_SALT);
  const signed = await builder.buildSignedOrder(orderData);
  const { signature, ...order } = signed;
  return {
    name,
    input: {
      userMarketOrder: { tokenID: tokenId, side, amount, price, orderType: "FOK" },
      options: { tickSize, negRisk },
      version,
      chainId: CHAIN_ID,
      exchangeContract,
      domain: {
        name: "Polymarket CTF Exchange",
        version: domainVersion,
        chainId: CHAIN_ID,
        verifyingContract: exchangeContract,
      },
      order,
    },
    expected: { signature },
  };
}

const orderSignatures = [];
orderSignatures.push(await orderCase({ name: "v2_general_buy", version: 2, negRisk: false, side: "BUY", amount: 5, price: 0.75, tickSize: "0.01", tokenId: TOKEN_A }));
orderSignatures.push(await orderCase({ name: "v2_general_sell", version: 2, negRisk: false, side: "SELL", amount: 6.66, price: 0.72, tickSize: "0.01", tokenId: TOKEN_A }));
orderSignatures.push(await orderCase({ name: "v2_negrisk_buy", version: 2, negRisk: true, side: "BUY", amount: 5, price: 0.61, tickSize: "0.01", tokenId: TOKEN_B }));
orderSignatures.push(await orderCase({ name: "v2_negrisk_sell", version: 2, negRisk: true, side: "SELL", amount: 12.5, price: 0.335, tickSize: "0.005", tokenId: TOKEN_B }));
orderSignatures.push(await orderCase({ name: "v3_buy", version: 3, negRisk: false, side: "BUY", amount: 5, price: 0.75, tickSize: "0.01", tokenId: TOKEN_A }));
orderSignatures.push(await orderCase({ name: "v3_sell", version: 3, negRisk: false, side: "SELL", amount: 6.66, price: 0.72, tickSize: "0.01", tokenId: TOKEN_A }));

// ---------------------------------------------------------------------------
// 2b. 지정가 주문 서명 케이스 (POLY_1271, GTC)
//     금액은 getOrderRawAmounts 경로, expiration은 서명에 들어가지 않고
//     POST 본문에만 실리는지까지 Java 서명과 대조해 확정한다.
// ---------------------------------------------------------------------------
async function limitOrderCase({ name, version, negRisk, side, size, price, tickSize, tokenId, expiration }) {
  const amt = limitAmountsFor(side, size, price, tickSize);
  const exchangeContract =
    version === 3 ? contracts.exchangeV3 : negRisk ? contracts.negRiskExchangeV2 : contracts.exchangeV2;
  const domainVersion = version === 3 ? "3" : "2";
  const orderData = {
    maker: FUNDER,
    tokenId,
    makerAmount: amt.makerAmount,
    takerAmount: amt.takerAmount,
    side,
    signer: FUNDER,
    signatureType: SIGNATURE_TYPE,
    timestamp: FIXED_ORDER_TIMESTAMP,
    metadata: bytes32Zero,
    builder: bytes32Zero,
    expiration: expiration ?? "0",
  };
  const builder =
    version === 3
      ? new ExchangeOrderBuilderV3(exchangeContract, CHAIN_ID, wallet, () => FIXED_SALT)
      : new ExchangeOrderBuilderV2(exchangeContract, CHAIN_ID, wallet, () => FIXED_SALT);
  const signed = await builder.buildSignedOrder(orderData);
  const { signature, ...order } = signed;
  return {
    name,
    input: {
      userOrder: { tokenID: tokenId, side, size, price, orderType: "GTC", expiration: expiration ?? "0" },
      options: { tickSize, negRisk },
      version,
      chainId: CHAIN_ID,
      exchangeContract,
      domain: {
        name: "Polymarket CTF Exchange",
        version: domainVersion,
        chainId: CHAIN_ID,
        verifyingContract: exchangeContract,
      },
      order,
    },
    expected: { signature },
  };
}

const limitOrderSignatures = [];
limitOrderSignatures.push(await limitOrderCase({ name: "v2_limit_buy_test_price", version: 2, negRisk: false, side: "BUY", size: 5, price: 0.01, tickSize: "0.01", tokenId: TOKEN_A }));
limitOrderSignatures.push(await limitOrderCase({ name: "v2_limit_sell", version: 2, negRisk: false, side: "SELL", size: 12.5, price: 0.335, tickSize: "0.005", tokenId: TOKEN_B }));
limitOrderSignatures.push(await limitOrderCase({ name: "v3_limit_buy", version: 3, negRisk: false, side: "BUY", size: 100, price: 0.48, tickSize: "0.01", tokenId: TOKEN_A }));
limitOrderSignatures.push(await limitOrderCase({ name: "v2_limit_buy_expiration", version: 2, negRisk: true, side: "BUY", size: 5, price: 0.5, tickSize: "0.01", tokenId: TOKEN_B, expiration: "1760003600" }));

// ---------------------------------------------------------------------------
// 3. L1 서명 (ClobAuth) — buildClobEip712Signature 그대로
// ---------------------------------------------------------------------------
const l1Signature = {
  input: {
    chainId: CHAIN_ID,
    address: EOA,
    timestamp: String(FIXED_L1_TIMESTAMP),
    nonce: 0,
    message: "This message attests that I control the given wallet",
    domain: { name: "ClobAuthDomain", version: "1", chainId: CHAIN_ID },
  },
  expected: {
    signature: await buildClobEip712Signature(wallet, CHAIN_ID, FIXED_L1_TIMESTAMP, 0),
  },
};

// ---------------------------------------------------------------------------
// 4. L2 HMAC — buildPolyHmacSignature 그대로.
//    본문 케이스는 SDK orderToJsonV2로 만든 실제 POST /order 본문 문자열.
// ---------------------------------------------------------------------------
const L2_SECRET = Buffer.from(
  Uint8Array.from({ length: 32 }, (_, i) => (i * 37 + 251) % 256),
).toString("base64url");

function orderBody(caseName) {
  const c = orderSignatures.find((o) => o.name === caseName);
  const signedOrder = { ...c.input.order, signature: c.expected.signature };
  return JSON.stringify(orderToJsonV2(signedOrder, TEST_API_KEY, "FOK"));
}

const l2Hmac = [];
l2Hmac.push({
  name: "post_order_v2_body",
  input: { secret: L2_SECRET, timestamp: FIXED_L1_TIMESTAMP, method: "POST", requestPath: "/order", body: orderBody("v2_general_buy") },
  expected: { signature: await buildPolyHmacSignature(L2_SECRET, FIXED_L1_TIMESTAMP, "POST", "/order", orderBody("v2_general_buy")) },
});
l2Hmac.push({
  name: "get_balance_allowance_no_body",
  input: { secret: L2_SECRET, timestamp: FIXED_L1_TIMESTAMP, method: "GET", requestPath: "/balance-allowance", body: null },
  expected: { signature: await buildPolyHmacSignature(L2_SECRET, FIXED_L1_TIMESTAMP, "GET", "/balance-allowance", undefined) },
});
l2Hmac.push({
  name: "post_order_v3_body",
  input: { secret: L2_SECRET, timestamp: FIXED_L1_TIMESTAMP + 123, method: "POST", requestPath: "/order", body: orderBody("v3_buy") },
  expected: { signature: await buildPolyHmacSignature(L2_SECRET, FIXED_L1_TIMESTAMP + 123, "POST", "/order", orderBody("v3_buy")) },
});

// ---------------------------------------------------------------------------
// 5. 리딤 콜 데이터 — engine.js의 buildCtfRedeemCall/buildV2RedeemCall 재현
//    (상수·ABI 문자열은 engine.js와 동일)
// ---------------------------------------------------------------------------
const PUSD = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB";
const COLLATERAL_ADAPTER = "0xAdA100Db00Ca00073811820692005400218FcE1f";
const NEG_RISK_COLLATERAL_ADAPTER = "0xadA2005600Dec949baf300f4C6120000bDB6eAab";
const PROTOCOL_V2_ROUTER = "0x12121212006e4CD160D18e3f00711DA5c3372600";
const ZERO_BYTES32 = "0x" + "0".repeat(64);

const CID_END00 = "0x" + "ab".repeat(31) + "00";
const CID_END01 = "0x" + "cd".repeat(31) + "01";

const CTF_REDEEM_ABI = parseAbi([
  "function redeemPositions(address collateralToken, bytes32 parentCollectionId, bytes32 conditionId, uint256[] indexSets)",
]);
const ROUTER_REDEEM_ABI = parseAbi([
  "function redeem(bytes31 conditionId, uint256 outcomeIndex, uint256 amount)",
]);

function toBytes31(conditionId) {
  let hex = String(conditionId).toLowerCase();
  if (hex.startsWith("0x")) hex = hex.slice(2);
  if (hex.length === 64 && (hex.endsWith("00") || hex.endsWith("01"))) hex = hex.slice(0, 62);
  return `0x${hex}`;
}

function ctfRedeemCall(conditionId, negRisk) {
  return {
    target: negRisk ? NEG_RISK_COLLATERAL_ADAPTER : COLLATERAL_ADAPTER,
    value: "0",
    data: encodeFunctionData({
      abi: CTF_REDEEM_ABI,
      functionName: "redeemPositions",
      args: [PUSD, ZERO_BYTES32, conditionId, [1n, 2n]],
    }),
  };
}

function v2RedeemCall(conditionId, outcomeIndex, amount) {
  return {
    target: PROTOCOL_V2_ROUTER,
    value: "0",
    data: encodeFunctionData({
      abi: ROUTER_REDEEM_ABI,
      functionName: "redeem",
      args: [toBytes31(conditionId), BigInt(outcomeIndex), BigInt(amount)],
    }),
  };
}

const redeemCalldata = [
  {
    name: "ctf_redeem_positions",
    input: { kind: "ctf", conditionId: CID_END00, negRisk: false, collateralToken: PUSD, parentCollectionId: ZERO_BYTES32, indexSets: ["1", "2"] },
    expected: ctfRedeemCall(CID_END00, false),
  },
  {
    name: "ctf_redeem_positions_negrisk",
    input: { kind: "ctf", conditionId: CID_END00, negRisk: true, collateralToken: PUSD, parentCollectionId: ZERO_BYTES32, indexSets: ["1", "2"] },
    expected: ctfRedeemCall(CID_END00, true),
  },
  {
    name: "v2_router_redeem_outcome0",
    input: { kind: "v2router", conditionId: CID_END01, bytes31: toBytes31(CID_END01), outcomeIndex: "0", amount: "5000000" },
    expected: v2RedeemCall(CID_END01, 0, "5000000"),
  },
  {
    name: "v2_router_redeem_outcome1",
    input: { kind: "v2router", conditionId: CID_END00, bytes31: toBytes31(CID_END00), outcomeIndex: "1", amount: "1234567" },
    expected: v2RedeemCall(CID_END00, 1, "1234567"),
  },
];

// ---------------------------------------------------------------------------
// 6. Batch 서명 (DepositWallet) — engine.js submitRedeemBatch의 signTypedData
// ---------------------------------------------------------------------------
const BATCH_TYPES = {
  Batch: [
    { name: "wallet", type: "address" },
    { name: "nonce", type: "uint256" },
    { name: "deadline", type: "uint256" },
    { name: "calls", type: "Call[]" },
  ],
  Call: [
    { name: "target", type: "address" },
    { name: "value", type: "uint256" },
    { name: "data", type: "bytes" },
  ],
};
const BATCH_DOMAIN = { name: "DepositWallet", version: "1", chainId: CHAIN_ID, verifyingContract: FUNDER };

async function batchCase(name, calls) {
  const message = {
    wallet: FUNDER,
    nonce: BigInt(FIXED_BATCH_NONCE),
    deadline: BigInt(FIXED_BATCH_DEADLINE),
    calls: calls.map((c) => ({ target: c.target, value: 0n, data: c.data })),
  };
  const signature = await account.signTypedData({
    domain: BATCH_DOMAIN,
    types: BATCH_TYPES,
    primaryType: "Batch",
    message,
  });
  return {
    name,
    input: {
      domain: BATCH_DOMAIN,
      types: BATCH_TYPES,
      primaryType: "Batch",
      message: {
        wallet: FUNDER,
        nonce: FIXED_BATCH_NONCE,
        deadline: FIXED_BATCH_DEADLINE,
        calls: calls.map((c) => ({ target: c.target, value: "0", data: c.data })),
      },
    },
    expected: { signature },
  };
}

const batchSignatures = [];
batchSignatures.push(await batchCase("batch_ctf_redeem", [ctfRedeemCall(CID_END00, false)]));
batchSignatures.push(await batchCase("batch_v2_router_redeem", [v2RedeemCall(CID_END01, 0, "5000000")]));
batchSignatures.push(await batchCase("batch_two_calls", [ctfRedeemCall(CID_END00, false), v2RedeemCall(CID_END01, 0, "5000000")]));

// ---------------------------------------------------------------------------
// 출력
// ---------------------------------------------------------------------------
const golden = {
  meta: {
    description: "boringmech 네이티브 이관 골든 데이터. JS SDK 출력이 정본이며, Java 구현은 input만으로 expected를 재현해야 한다.",
    generatedBy: "native-golden/gen_golden.mjs",
    sdk: { "@polymarket/clob-client-v2": "1.2.1", viem: "2.57.19" },
    testPrivateKey: TEST_PRIVATE_KEY,
    eoa: EOA,
    funderUsed: FUNDER,
    fixedSalt: FIXED_SALT,
    fixedOrderTimestamp: FIXED_ORDER_TIMESTAMP,
    note: "테스트 키(0x1111…)는 실계정이 아니다. funderUsed는 이 EOA의 UUPS 파생 주소다.",
  },
  funderDerivation: {
    input: { eoa: EOA },
    expected: { uups: FUNDER_UUPS, beacon: FUNDER_BEACON },
  },
  amounts,
  limitAmounts,
  orderSignatures,
  limitOrderSignatures,
  l1Signature,
  l2Hmac,
  redeemCalldata,
  batchSignatures,
};

const out = JSON.stringify(golden, null, 2) + "\n";
writeFileSync(new URL("./golden.json", import.meta.url), out);
console.log(`golden.json written (${out.length} bytes)`);
console.log(
  `cases: amounts=${amounts.length}, limitAmounts=${limitAmounts.length}, ` +
    `orderSignatures=${orderSignatures.length}, limitOrderSignatures=${limitOrderSignatures.length}, ` +
    `l1=1, l2Hmac=${l2Hmac.length}, redeemCalldata=${redeemCalldata.length}, ` +
    `batchSignatures=${batchSignatures.length}, funderDerivation=1`,
);
