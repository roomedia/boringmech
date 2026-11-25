// relayer 연동 확인 가드 — 결과 분류(200/401/기타)가 섞이지 않는지,
// 확인이 제출을 만들지 않는지, 상태 캐시가 자격증명 지문 해시에 묶이는지를
// 대조한다. 실행: node test/relayer-keycheck.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const { readFileSync } = await import("node:fs");
const {
  checkRelayerKey,
  readRelayerCheck,
  classifyRelayerCheckError,
  relayerPrintHash,
  relayerCredsPrint,
} = await import("../src/engine.js");
const BM = globalThis.window.BM;

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

const ADDR = "0x1111111111111111111111111111111111111111";
function setCreds(key, address = ADDR) {
  lsStore.set("bm_relayer", JSON.stringify({ key, address }));
}

// fetch 스텁 — relayerGet의 fetch 경로를 탄다 (브리지 없음)
let fetchBehavior = null;
let lastFetch = null;
globalThis.fetch = async (url, opts) => {
  lastFetch = { url, opts };
  if (fetchBehavior instanceof Error) throw fetchBehavior;
  return fetchBehavior;
};
const resp = (status) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => ({}),
  text: async () => "",
});

// ── 분류 순수 함수 ───────────────────────────────────────────────
eq(classifyRelayerCheckError("relayer 조회 HTTP 401: {}"), "auth", "분류: 조회 401은 인증 실패");
eq(classifyRelayerCheckError("relayer 조회 HTTP 503: {}"), "unknown", "분류: 503은 확인 불가");
eq(classifyRelayerCheckError("relayer 조회 HTTP 500"), "unknown", "분류: 500은 확인 불가");
eq(classifyRelayerCheckError("relayer 조회 실패: timeout"), "unknown", "분류: 전송 오류는 확인 불가");
eq(classifyRelayerCheckError("요청 시간 초과 (30초) https://relayer-v2.polymarket.com/v1/account/transactions"), "unknown", "분류: 시간 초과는 확인 불가");
eq(classifyRelayerCheckError("TypeError: fetch failed"), "unknown", "분류: 네트워크 오류는 확인 불가");
eq(classifyRelayerCheckError(null), "unknown", "분류: null 안전");

// ── 지문 해시 ────────────────────────────────────────────────────
const p1 = relayerCredsPrint({ key: "secret-key-1", address: ADDR });
eq(relayerPrintHash(p1) === relayerPrintHash(relayerCredsPrint({ key: "secret-key-1", address: ADDR })), true, "해시: 같은 쌍은 같은 해시");
eq(relayerPrintHash(p1) === relayerPrintHash(relayerCredsPrint({ key: "secret-key-2", address: ADDR })), false, "해시: 키가 바뀌면 다름");
eq(relayerPrintHash(p1).includes("secret"), false, "해시: 원문 키를 포함하지 않는다");

// ── 확인 동작 (스텁 fetch) ──────────────────────────────────────
eq((await checkRelayerKey()).status, "none", "확인: 키 없음은 none");

setCreds("k-ok");
fetchBehavior = resp(200);
eq((await checkRelayerKey()).status, "ok", "확인: 200은 정상");
eq(lastFetch.url.includes("/v1/account/transactions?address=" + ADDR), true, "확인: 목록 조회 경로");
eq(lastFetch.opts?.headers?.RELAYER_API_KEY, "k-ok", "확인: 저장된 키를 헤더로 보낸다");
eq(lastFetch.opts?.headers?.RELAYER_API_KEY_ADDRESS, ADDR, "확인: 저장된 주소를 헤더로 보낸다");
eq(readRelayerCheck()?.status, "ok", "캐시: 정상 결과가 남는다");

fetchBehavior = resp(401);
eq((await checkRelayerKey()).status, "auth", "확인: 401은 인증 실패");
eq(readRelayerCheck()?.status, "auth", "캐시: 인증 실패가 남는다");

fetchBehavior = resp(503);
eq((await checkRelayerKey()).status, "unknown", "확인: 503은 확인 불가 (인증 실패와 분리)");
eq(readRelayerCheck()?.status, "unknown", "캐시: 확인 불가가 남는다");

fetchBehavior = new TypeError("fetch failed");
eq((await checkRelayerKey()).status, "unknown", "확인: 네트워크 오류는 확인 불가");

setCreds("k-other");
eq(readRelayerCheck(), null, "캐시: 자격증명이 바뀌면 낡은 결과는 무효");

// ── 소스 배선 가드 ───────────────────────────────────────────────
eq(typeof BM?.checkRelayerKey, "function", "배선: BM에 checkRelayerKey 노출");
eq(typeof BM?.readRelayerCheck, "function", "배선: BM에 readRelayerCheck 노출");
const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
const chk = src.slice(src.indexOf("export async function checkRelayerKey"));
const chkBody = chk.slice(0, chk.indexOf("\n}\n") + 3);
eq(chkBody.includes("relayerGet("), true, "배선: 확인은 조회로만 한다");
eq(chkBody.includes("relayerPost"), false, "배선: 확인은 제출을 만들지 않는다");
eq(chkBody.includes("/submit"), false, "배선: 확인은 /submit을 부르지 않는다");
eq(chkBody.includes('state.relayerAuthFailPrint = print'), true, "배선: 확인 401이 래치를 건다");
const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
eq(html.includes('id="btnRelayerCheck"'), true, "배선: 연동 확인 버튼 존재");
eq(html.includes("runRelayerCheck();"), true, "배선: 저장 직후·버튼에서 확인 실행");

console.log(`relayer-keycheck: ${passed} passed, ${failed} failed`);
if (failed) process.exit(1);
