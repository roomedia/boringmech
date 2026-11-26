// 손절 attempted 장부 인터페이스 고정 — 공리적 분리 6단계 (감사 결합 6).
// 세 경로(자동 손절·폴백 손절·수동 매도)가 공유하는 attempted 장부의
// 의미론을 픽스처로 고정하고, 키 접근이 소유자 구간 안에만 있는지
// 구조 가드로 확인한다.
//   기록: recordStopLossAttempt(tokenId, slug) — 자동·폴백이 공유하는
//         executeStopLossSell이 결과 불명일 때만 부른다.
//   조회: stopLossAttemptedTokens · stopLossAttemptedSlugs ·
//         manualSellAttemptedTokens (수동 attempted는 네이티브가 쓰고
//         JS는 읽기만 한다).
// 실행: node test/stoploss-ledger-owner.test.mjs
globalThis.window = {};
const ls = new Map();
globalThis.localStorage = {
  getItem: (k) => (ls.has(k) ? ls.get(k) : null),
  setItem: (k, v) => ls.set(k, String(v)),
  removeItem: (k) => ls.delete(k),
};
const {
  recordStopLossAttempt,
  stopLossAttemptedTokens,
  stopLossAttemptedSlugs,
  manualSellAttemptedTokens,
} = await import("../src/engine.js");
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
const TOKENS = "bm_stoploss_attempted";
const SLUGS = "bm_stoploss_attempted_slugs";
const MTOKENS = "bm_manual_sell_attempted";
const MTOKENS_ARC = "bm_manual_sell_attempted_archive";
function reset() {
  ls.clear();
}
const sortedSet = (s) => [...s].sort();

// ── 교차 경로 픽스처 ─────────────────────────────────────────────
// 1) 자동·폴백 공통 실행부가 남긴 시도 — 토큰과 slug가 함께 기록된다.
reset();
recordStopLossAttempt("tok1", "slug1");
eq(sortedSet(stopLossAttemptedTokens()), ["tok1"], "attempt: token recorded");
eq(sortedSet(stopLossAttemptedSlugs()), ["slug1"], "attempt: slug recorded");
eq(ls.get(TOKENS), '["tok1"]', "attempt: token storage form");
eq(ls.get(SLUGS), '["slug1"]', "attempt: slug storage form");

// 2) 중복 기록은 무동작 — 저장 문자열이 그대로다 (다시 쓰지 않는다).
recordStopLossAttempt("tok1", "slug1");
eq(ls.get(TOKENS), '["tok1"]', "attempt: duplicate token is a no-op");
eq(ls.get(SLUGS), '["slug1"]', "attempt: duplicate slug is a no-op");

// 3) 토큰만·slug만·둘 다 없음.
reset();
recordStopLossAttempt("tok2", null);
eq(sortedSet(stopLossAttemptedTokens()), ["tok2"], "attempt: token only");
eq(ls.has(SLUGS), false, "attempt: token only writes no slugs");
recordStopLossAttempt("", "slug2");
eq(sortedSet(stopLossAttemptedSlugs()), ["slug2"], "attempt: slug only");
eq(ls.get(TOKENS), '["tok2"]', "attempt: slug only leaves tokens");
recordStopLossAttempt(null, null);
eq(ls.get(TOKENS), '["tok2"]', "attempt: empty record leaves tokens");
eq(ls.get(SLUGS), '["slug2"]', "attempt: empty record leaves slugs");

// 4) 토큰 캡 200 — 꼬리 유지, 이미 있는 토큰은 캡과 무관하게 무동작.
reset();
const seed200 = Array.from({ length: 200 }, (_, i) => `t${i}`);
ls.set(TOKENS, JSON.stringify(seed200));
recordStopLossAttempt("t200", null);
const afterCap = JSON.parse(ls.get(TOKENS));
eq(afterCap.length, 200, "attempt: token cap keeps 200");
eq(afterCap[0], "t1", "attempt: token cap drops the oldest");
eq(afterCap[199], "t200", "attempt: token cap keeps the newest");
const cappedRaw = ls.get(TOKENS);
recordStopLossAttempt("t150", null);
eq(ls.get(TOKENS), cappedRaw, "attempt: existing token at cap is a no-op");

// 5) slug 캡 10,000 — 꼬리 유지.
reset();
const seedSlugs = Array.from({ length: 10_000 }, (_, i) => `s${i}`);
ls.set(SLUGS, JSON.stringify(seedSlugs));
recordStopLossAttempt(null, "s10000");
const afterSlugCap = JSON.parse(ls.get(SLUGS));
eq(afterSlugCap.length, 10_000, "attempt: slug cap keeps 10000");
eq(afterSlugCap[0], "s1", "attempt: slug cap drops the oldest");
eq(afterSlugCap[9999], "s10000", "attempt: slug cap keeps the newest");

// 6) 손상·비배열 저장값은 빈 집합으로 읽고, 기록은 새 배열로 덮는다.
for (const bad of ["{broken", '{"a":1}', "42", '"str"']) {
  reset();
  ls.set(TOKENS, bad);
  ls.set(SLUGS, bad);
  eq(sortedSet(stopLossAttemptedTokens()), [], `attempt: unreadable tokens (${bad}) read empty`);
  eq(sortedSet(stopLossAttemptedSlugs()), [], `attempt: unreadable slugs (${bad}) read empty`);
  recordStopLossAttempt("fresh", "fresh-slug");
  eq(ls.get(TOKENS), '["fresh"]', `attempt: record over unreadable tokens (${bad})`);
  eq(ls.get(SLUGS), '["fresh-slug"]', `attempt: record over unreadable slugs (${bad})`);
}

// 7) 문자열이 아닌 항목은 조회에서 걸러진다.
reset();
ls.set(TOKENS, '["a", 1, null, "b", true]');
ls.set(SLUGS, '["x", {}, "y"]');
eq(sortedSet(stopLossAttemptedTokens()), ["a", "b"], "attempt: non-string tokens filtered");
eq(sortedSet(stopLossAttemptedSlugs()), ["x", "y"], "attempt: non-string slugs filtered");

// 8) 수동 attempted — 네이티브가 쓴 아카이브+최근을 합쳐 읽는다.
//    아카이브가 손상돼도 최근분은 살린다 (키별 독립 실패).
reset();
ls.set(MTOKENS_ARC, '["atok"]');
ls.set(MTOKENS, '["rtok", "atok"]');
eq(sortedSet(manualSellAttemptedTokens()), ["atok", "rtok"], "manual attempt: archive+recent merged");
reset();
ls.set(MTOKENS_ARC, "{broken");
ls.set(MTOKENS, '["rtok", 7, null]');
eq(sortedSet(manualSellAttemptedTokens()), ["rtok"], "manual attempt: broken archive tolerated");
reset();
eq(sortedSet(manualSellAttemptedTokens()), [], "manual attempt: missing keys read empty");

// 9) 저장소 분리 — 손절 attempted 기록이 수동 attempted를 건드리지 않고,
//    그 반대 조회도 섞이지 않는다.
reset();
recordStopLossAttempt("tok9", "slug9");
eq(ls.has(MTOKENS), false, "separation: stop-loss attempt writes no manual tokens");
eq(ls.has(MTOKENS_ARC), false, "separation: stop-loss attempt writes no manual archive");
eq(sortedSet(manualSellAttemptedTokens()), [], "separation: manual query unaffected");
ls.set(MTOKENS, '["mtok"]');
eq(sortedSet(stopLossAttemptedTokens()), ["tok9"], "separation: stop-loss query unaffected");

// 10) 리셋 없음 — attempted는 세대·날짜 경계에서 지워지지 않는다.
//     지우는 경로는 구조 가드(구간 밖 쓰기 0건)가 고정하고, 여기서는
//     조회가 저장 원문을 그대로 반영함을 확인한다.
reset();
ls.set(TOKENS, '["old-token"]');
ls.set(SLUGS, '["old-slug"]');
eq(sortedSet(stopLossAttemptedTokens()), ["old-token"], "no-reset: stored tokens persist");
eq(sortedSet(stopLossAttemptedSlugs()), ["old-slug"], "no-reset: stored slugs persist");

// ── 구조 가드 ────────────────────────────────────────────────────
// 키 접근은 소유자 구간 안에만 있고, 세 경로는 고정 인터페이스만 쓴다.
const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
const begin = src.indexOf("손절 장부 소유자 구간 시작");
const end = src.indexOf("손절 장부 소유자 구간 끝");
check(begin >= 0 && end > begin, "guard: owner region markers exist");

const inRegion = (idx) => idx > begin && idx < end;
for (const name of [
  "recordStopLossAttempt",
  "stopLossAttemptedTokens",
  "stopLossAttemptedSlugs",
  "manualSellAttemptedTokens",
]) {
  check(inRegion(src.indexOf(`function ${name}`)), `guard: ${name} defined in owner region`);
}
const keyTokens = [
  /LS\.stopLossAttempted(?![A-Za-z])/g,
  /LS\.stopLossAttemptedSlugs(?![A-Za-z])/g,
  /LS\.manualSellAttempted(?![A-Za-z])/g,
  /LS\.manualSellAttemptedArchive(?![A-Za-z])/g,
  /LS\.stopLoss(?![A-Za-z])/g,
  /LS\.stopLossArchive(?![A-Za-z])/g,
  /LS\.manualSell(?![A-Za-z])/g,
  /LS\.manualSellArchive(?![A-Za-z])/g,
];
for (const re of keyTokens) {
  const hits = [...src.matchAll(re)].map((m) => m.index);
  check(hits.length > 0 && hits.every(inRegion), `guard: ${re.source} only in owner region (${hits.length} hits)`);
}

// attempted 원문 키는 LS 표에 한 번씩만 있고, 뷰어는 전혀 닿지 않는다.
const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
for (const [re, label] of [
  [/bm_stoploss_attempted(?!_slugs)/g, "bm_stoploss_attempted"],
  [/bm_stoploss_attempted_slugs/g, "bm_stoploss_attempted_slugs"],
  [/bm_manual_sell_attempted(?!_archive)/g, "bm_manual_sell_attempted"],
  [/bm_manual_sell_attempted_archive/g, "bm_manual_sell_attempted_archive"],
]) {
  eq((src.match(re) || []).length, 1, `guard: ${label} literal once in engine.js`);
  eq((html.match(re) || []).length, 0, `guard: ${label} absent from index.html`);
}

// 기록 호출 지점은 정의 1곳 + executeStopLossSellLocked 안 2곳뿐이다
// (자동·폴백이 공유하는 실행부 — 결과 불명일 때만 남긴다).
const callHits = [...src.matchAll(/recordStopLossAttempt\(/g)].map((m) => m.index);
eq(callHits.length, 3, "guard: recordStopLossAttempt has definition + 2 call sites");
const lockedStart = src.indexOf("async function executeStopLossSellLocked");
const lockedEnd = src.indexOf("\nasync function ", lockedStart + 1);
const outside = callHits.filter((i) => !inRegion(i));
check(
  outside.length === 2 && outside.every((i) => i > lockedStart && i < lockedEnd),
  "guard: both call sites are inside executeStopLossSellLocked",
);

console.log(`stoploss-ledger-owner: ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
