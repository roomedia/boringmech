// 장부·스냅샷 단일 소유자 가드 — 공리적 분리 4단계 (감사 결합 4).
// 1) 거동 대조: 소유자 원문 읽기(readBoughtRaw·readSnapCacheRaw)가
//    종전 뷰어 식(localStorage 직접 파싱)과 모든 형태에서 같은 값을
//    내고, 깨진 JSON에서는 같이 던지는지 대조한다.
// 2) 구조 가드: index.html에 장부·스냅샷 키 문자열이 없고, engine.js의
//    LS.bought·LS.snap 출현이 소유자 구간 안에만 있는지 고정한다.
// 실행: node test/ledger-owner.test.mjs
globalThis.window = {};
const ls = new Map();
globalThis.localStorage = {
  getItem: (k) => (ls.has(k) ? ls.get(k) : null),
  setItem: (k, v) => ls.set(k, String(v)),
  removeItem: (k) => ls.delete(k),
};
const { readBoughtRaw, readSnapCacheRaw } = await import("../src/engine.js");
import { readFileSync } from "node:fs";

let passed = 0;
let failed = 0;
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed += 1;
  } else {
    failed += 1;
    console.error(`FAIL ${label}\n  actual:   ${a}\n  expected: ${e}`);
  }
}
function check(cond, label) {
  if (cond) {
    passed += 1;
  } else {
    failed += 1;
    console.error(`FAIL ${label}`);
  }
}
function throws(fn) {
  try {
    fn();
    return false;
  } catch {
    return true;
  }
}

// 종전 뷰어 식 (index.html이 직접 쓰던 형태 그대로)
const oldBought = () => JSON.parse(localStorage.getItem("bm_bought") || "[]");
const oldSnap = () => JSON.parse(localStorage.getItem("bm_snap") || "null");

const boughtCases = [
  ["missing", null],
  ["empty array", "[]"],
  ["string entries", '["a","b"]'],
  ["tagged entries", '["a",{"slug":"t","strategy":"temp_no"}]'],
  ["non-array object", '{"a":1}'],
  ["number", "42"],
];
for (const [label, raw] of boughtCases) {
  ls.clear();
  if (raw !== null) ls.set("bm_bought", raw);
  eq(readBoughtRaw(), oldBought(), `bought raw: ${label}`);
}
ls.clear();
ls.set("bm_bought", "{broken");
check(throws(readBoughtRaw) && throws(oldBought), "bought raw: broken json throws in both");

const snapCases = [
  ["missing", null],
  ["null literal", "null"],
  ["cache object", '{"cash":12.5,"equity":30,"heldSlugs":["a"]}'],
  ["array", "[1,2]"],
];
for (const [label, raw] of snapCases) {
  ls.clear();
  if (raw !== null) ls.set("bm_snap", raw);
  eq(readSnapCacheRaw(), oldSnap(), `snap raw: ${label}`);
}
ls.clear();
ls.set("bm_snap", "{broken");
check(throws(readSnapCacheRaw) && throws(oldSnap), "snap raw: broken json throws in both");

// BM 노출 — 뷰어가 실제로 닿는 표면
check(typeof globalThis.window.BM?.readBoughtRaw === "function", "BM exposes readBoughtRaw");
check(typeof globalThis.window.BM?.readSnapCacheRaw === "function", "BM exposes readSnapCacheRaw");

// 구조 가드 — index.html은 키를 직접 만지지 않는다
const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
check(!html.includes("bm_bought"), "guard: index.html has no bm_bought");
check(!html.includes("bm_snap"), "guard: index.html has no bm_snap");
check(html.includes("payload.bought = BM.readBoughtRaw()"), "guard: report bought via BM owner");
check(html.includes("payload.snap = BM.readSnapCacheRaw()"), "guard: report snap fallback via BM owner");

// 구조 가드 — engine.js의 키 접근은 소유자 구간 안에만 있다
const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
const begin = src.indexOf("장부·스냅샷 소유자 구간 시작");
const end = src.indexOf("장부·스냅샷 소유자 구간 끝");
check(begin >= 0 && end > begin, "guard: owner region markers exist");
for (const token of ["LS.bought", "LS.snap"]) {
  let idx = src.indexOf(token);
  let count = 0;
  while (idx >= 0) {
    count += 1;
    check(idx > begin && idx < end, `guard: ${token} occurrence ${count} inside owner region`);
    idx = src.indexOf(token, idx + 1);
  }
  check(count > 0, `guard: ${token} still accessed by owner`);
}

console.log(`ledger-owner tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
