// 섀도우 입력 계약 테스트 (공리적 분리 7단계 — 결합 7) — 스캔 단계가
// 계약 형태로 변환해 넘기고 섀도우 기록은 계약만 의존함을 고정한다.
// 기준선(fixtures/shadow-contract-baseline.json)은 리팩터 전 코드가
// 스캔 형태 후보 + snap 입력으로 만든 레코드·저장소 직렬화이고, 이
// 테스트는 새 경로(계약 변환 + 평면 헤더)로 같은 바이트가 나오는지
// 대조한다. 실행: node test/shadow-contract.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const {
  toShadowCandInput,
  toShadowCandInputs,
  buildShadowCycleRecord,
  recordShadowCycle,
} = await import("../src/engine.js");
const { readFileSync } = await import("node:fs");

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
function ok(cond, label) {
  eq(Boolean(cond), true, label);
}

const T0 = Date.UTC(2026, 9, 6, 0, 0);
const END = "2026-10-06T12:00:00Z";
const slug = (city, temp) =>
  `highest-temperature-in-${city}-on-october-6-2026-${temp}c`;
// 스캔 산출물 전체 형태 (scanTempCandidates가 만드는 필드 전부)
const scanCand = (city, temp, price, endDate = END) => ({
  slug: slug(city, temp),
  question: `Highest temperature in ${city}?`,
  side: "no",
  price,
  endDate,
  strategy: "temp_no",
  bandLo: 0.9,
  bandHi: 0.998,
  feeRate: null,
});

// ── 변환: 계약 필드만, 값 복사만 ─────────────────────────────
{
  const input = toShadowCandInput(scanCand("dallas", 30, 0.95));
  eq(Object.keys(input), ["slug", "price", "endDate"], "계약: 필드는 3개뿐");
  eq(input, { slug: slug("dallas", 30), price: 0.95, endDate: END }, "계약: 값 복사");
  const bare = toShadowCandInput({ slug: "x" });
  ok("endDate" in bare && bare.endDate === undefined, "계약: 없는 필드는 기본값 없이 undefined");
  eq(toShadowCandInputs(null), [], "계약: null → 빈 배열");
  eq(toShadowCandInputs([scanCand("seoul", 28, 0.96)]).length, 1, "계약: 배열 변환");
}

// ── 독극물 가드: 계약 밖 필드가 섞여도 기록은 같다 ───────────
{
  const clean = toShadowCandInputs([scanCand("dallas", 30, 0.95), scanCand("paris", 18, 0.99)]);
  const poisoned = clean.map((c) => ({
    ...c,
    city: "atlantis", bucket: "10-14", date: "1999-01-01",
    score: 999, rank: 42, question: "?", strategy: "main",
  }));
  const header = { ts: T0, cycle: 7, cash: 42.5, slots: 3, openCount: 2 };
  const a = buildShadowCycleRecord({ ...header, cands: clean, enteredSlugs: new Set() });
  const b = buildShadowCycleRecord({ ...header, cands: poisoned, enteredSlugs: new Set() });
  eq(JSON.stringify(b), JSON.stringify(a), "독극물: 계약 밖 필드는 기록에 영향 없음");
}

// ── 기준선 바이트 대조 (리팩터 전 코드 산출) ─────────────────
{
  const baseline = JSON.parse(readFileSync(
    new URL("./fixtures/shadow-contract-baseline.json", import.meta.url), "utf8"));
  const scenarios = [
    {
      ts: T0, cycle: 7, snap: { cash: 42.5, slots: 3, openCount: 2 },
      cands: [scanCand("paris", 18, 0.99), scanCand("dallas", 30, 0.95),
        scanCand("atlantis", 20, 0.97)],
      entered: [slug("dallas", 30)],
    },
    {
      ts: T0 + 60_000, cycle: 8, snap: { cash: 45, slots: 4, openCount: 1 },
      cands: [scanCand("dallas", 30, 0.95), scanCand("seoul", 28, 0.96)],
      entered: [slug("seoul", 28), slug("dallas", 30)],
    },
    {
      ts: T0 + 120_000, cycle: 9, snap: { cash: 0, slots: 0, openCount: 0 },
      cands: [scanCand("seoul", 28, 0.95, ""), scanCand("seoul", 29, 0.96)],
      entered: [],
    },
    {
      ts: T0 + 180_000, cycle: 10, snap: { cash: 12.34, slots: 2, openCount: 5 },
      cands: [scanCand("wellington", 21, 0.93, "2026-10-07T12:00:00Z"),
        scanCand("dallas", 30, 0.95)],
      entered: [slug("wellington", 21)],
    },
    {
      ts: T0 + 240_000, cycle: 11, snap: null,
      cands: [scanCand("seoul", 28, 0.96)],
      entered: [],
    },
  ];
  eq(baseline.records.length, scenarios.length, "기준선: 시나리오 수 일치");
  scenarios.forEach((s, i) => {
    const inputs = toShadowCandInputs(s.cands);
    const header = {
      ts: s.ts, cycle: s.cycle,
      cash: s.snap ? s.snap.cash : null,
      slots: s.snap ? s.snap.slots : null,
      openCount: s.snap ? s.snap.openCount : null,
    };
    const rec = buildShadowCycleRecord({
      ...header, cands: inputs, enteredSlugs: new Set(s.entered),
    });
    eq(JSON.stringify(rec), baseline.records[i], `기준선: 레코드 ${i + 1} 바이트 동일`);
    recordShadowCycle({ ...header, cands: inputs, enteredSlugs: new Set(s.entered) });
  });
  eq(lsStore.get("bm_shadow"), baseline.store, "기준선: 저장소 직렬화 바이트 동일");
}

// ── 구조 가드 ────────────────────────────────────────────────
{
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  ok(src.includes("shadowInputs = toShadowCandInputs(tempCands)"), "가드: 스캔 단계가 계약으로 변환");
  ok(src.includes("cands: ctx.shadowInputs"), "가드: 섀도우 단계는 계약만 소비");
  ok(!src.includes("tempCandsSeen"), "가드: 스캔 원형 전달 필드 제거");
  ok(src.includes("export function recordShadowCycle({ ts, cycle, cash, slots, openCount, cands, enteredSlugs })"),
    "가드: 드라이버는 snap을 받지 않음");
}

console.log(`shadow-contract: ${passed} passed, ${failed} failed`);
if (failed) process.exit(1);
