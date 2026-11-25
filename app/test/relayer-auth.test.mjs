// relayer 401 래치 가드 — 인증 판정 순수 함수와, runRedeems·키 등록 경로에
// 래치와 EOA 대조가 실제로 배선돼 있는지를 소스 기준으로 대조한다.
// 실행: node test/relayer-auth.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const { readFileSync } = await import("node:fs");
const { isRelayerAuthError, relayerCredsPrint } = await import("../src/engine.js");

let passed = 0;
let failed = 0;
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed += 1;
  } else {
    failed += 1;
    console.error(`FAIL ${label}: expected ${e}, got ${a}`);
  }
}

// ── 인증 실패 판정 ───────────────────────────────────────────────
eq(isRelayerAuthError('relayer 제출 HTTP 401: {"error":"invalid authorization"}'), true, "판정: 제출 401");
eq(isRelayerAuthError("relayer 조회 HTTP 401: {}"), true, "판정: 조회 401");
eq(isRelayerAuthError("relayer 제출 HTTP 403: {}"), false, "판정: 403은 아님");
eq(isRelayerAuthError("relayer 제출 HTTP 500: {}"), false, "판정: 500은 아님");
eq(isRelayerAuthError("HTTP 401 https://data-api.polymarket.com/v2/positions"), false, "판정: data-api 401은 아님");
eq(isRelayerAuthError(null), false, "판정: null 안전");
eq(isRelayerAuthError(undefined), false, "판정: undefined 안전");
eq(isRelayerAuthError(""), false, "판정: 빈 문자열");

// ── 자격증명 지문 ────────────────────────────────────────────────
const c1 = { key: "k1", address: "0xABC" };
eq(relayerCredsPrint(c1) === relayerCredsPrint({ key: "k1", address: "0xABC" }), true, "지문: 같은 쌍은 같은 지문");
eq(relayerCredsPrint(c1) === relayerCredsPrint({ key: "k2", address: "0xABC" }), false, "지문: 키가 바뀌면 다름");
eq(relayerCredsPrint(c1) === relayerCredsPrint({ key: "k1", address: "0xDEF" }), false, "지문: 주소가 바뀌면 다름");
eq(relayerCredsPrint(null), "", "지문: null은 빈 문자열");

// ── 소스 배선 가드 ───────────────────────────────────────────────
const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
const rr = src.slice(src.indexOf("async function runRedeems"));
eq(rr.includes("state.relayerAuthFailPrint"), true, "배선: runRedeems가 래치를 읽고 쓴다");
eq(
  rr.indexOf("credsPrint === state.relayerAuthFailPrint") >= 0
    && rr.indexOf("credsPrint === state.relayerAuthFailPrint") < rr.indexOf("for (const target of plan.targets)"),
  true,
  "배선: 래치 검사가 제출 루프보다 앞",
);
eq(rr.includes("isRelayerAuthError(e?.message ?? e)"), true, "배선: 실패 시 인증 판정 후 래치");
const srk = src.slice(src.indexOf("setRelayerKey(key, address)"));
eq(srk.slice(0, 1200).includes("state.eoa"), true, "배선: 키 등록 시 연동 EOA와 대조");
eq(srk.slice(0, 1200).includes('state.relayerAuthFailPrint = "";'), true, "배선: 키 등록 성공 시 래치 해제");

const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
eq(html.includes("Signer Address (키 생성 시 표시된 주소)"), true, "배선: 주소 입력 안내가 Signer Address로 명시");

console.log(`relayer-auth: ${passed} passed, ${failed} failed`);
if (failed) process.exit(1);
