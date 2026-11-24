// 손절 폴백 순수 로직 테스트 — 스냅샷 캐시 장부에서 폴백 행을 구성하는
// 필터·제외 규칙, 무효 토큰 집합의 재확인 창, 판정 임계 동일성을 대조한다.
// 실행: node test/stoploss-fallback.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const {
  fallbackRowsFromCache,
  parseFallbackDead,
  fallbackDeadActive,
  withFallbackDead,
  stopLossVerdict,
  FALLBACK_DEAD_RECHECK_MS,
} = await import("../src/engine.js");

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

// ── 폴백 행 구성 ─────────────────────────────────────────────
const cache = {
  positions: [
    { slug: "ok", token_id: "1", outcome: "No", avg_price: 0.9, current_size: 10 },
    { slug: "zero-entry", token_id: "2", outcome: "No", avg_price: 0, current_size: 10 },
    { slug: "zero-size", token_id: "3", outcome: "No", avg_price: 0.9, current_size: 0 },
    { slug: "no-token", outcome: "No", avg_price: 0.9, current_size: 10 },
    { slug: "sold", token_id: "4", outcome: "No", avg_price: 0.9, current_size: 10 },
    { slug: "dead", token_id: "5", outcome: "No", avg_price: 0.9, current_size: 10 },
    { slug: "nan-entry", token_id: "6", outcome: "No", avg_price: NaN, current_size: 10 },
    "garbage",
    null,
  ],
};
const rows = fallbackRowsFromCache(cache, new Set(["4"]), new Set(["5"]));
eq(rows.length, 1, "rows: 유효 행만 1건");
eq(rows[0]?.slug, "ok", "rows: slug 보존");
eq(rows[0]?.token_id, "1", "rows: token 보존");
eq(rows[0]?.avg_price, 0.9, "rows: 진입가 보존");
eq(rows[0]?.current_size, 10, "rows: 수량 보존");
eq(rows[0]?.outcome, "No", "rows: 보유 쪽 보존");
eq(rows[0]?.fallback, true, "rows: fallback 표기");
eq(fallbackRowsFromCache(null, new Set(), new Set()), [], "rows: 캐시 없음은 빈 목록");
eq(fallbackRowsFromCache({}, new Set(), new Set()), [], "rows: positions 없음은 빈 목록");

// ── 임계 동일성 ──────────────────────────────────────────────
// 폴백 행의 판정 입력(진입가)을 같은 판정 함수에 넣으면 API 행과 같은 결과
eq(
  stopLossVerdict(rows[0].avg_price, 0.25, 0.25, 0.97, 0.03),
  "triggered",
  "verdict: 발화선 아래는 triggered",
);
eq(
  stopLossVerdict(rows[0].avg_price, 0.7, 0.25, 0.97, 0.03),
  "holding",
  "verdict: 발화선 위는 holding",
);
eq(
  stopLossVerdict(rows[0].avg_price, 0.25, 0.15, 0.97, 0.03),
  "triggered",
  "verdict: 기온 폭도 같은 함수로 판정",
);
eq(
  stopLossVerdict(rows[0].avg_price, 0.98, 0.25, 0.97, 0.03),
  "decided",
  "verdict: 결정 구간 제외도 동일",
);

// ── 무효 토큰 집합 ───────────────────────────────────────────
eq(parseFallbackDead(null), [], "dead: null은 빈 목록");
eq(parseFallbackDead("{oops"), [], "dead: 깨진 원문은 빈 목록");
eq(parseFallbackDead('[{"t":"9","ts":1000},{"t":"","ts":1},{"x":1}]'),
  [{ t: "9", ts: 1000 }], "dead: 유효 항목만");
eq(fallbackDeadActive([{ t: "9", ts: 1000 }], "9", 1000 + FALLBACK_DEAD_RECHECK_MS - 1),
  true, "dead: 창 안은 활성");
eq(fallbackDeadActive([{ t: "9", ts: 1000 }], "9", 1000 + FALLBACK_DEAD_RECHECK_MS),
  false, "dead: 창이 지나면 재확인");
eq(fallbackDeadActive([{ t: "9", ts: 1000 }], "8", 1000), false, "dead: 다른 토큰은 비활성");
eq(withFallbackDead([{ t: "9", ts: 1000 }], "9", 2000),
  [{ t: "9", ts: 2000 }], "dead: 재표시는 시각 교체");
eq(withFallbackDead([], "9", 2000), [{ t: "9", ts: 2000 }], "dead: 신규 표시");

console.log(`stoploss-fallback.test: ${passed} passed, ${failed} failed`);
if (failed) process.exit(1);
