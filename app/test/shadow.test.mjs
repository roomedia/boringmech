// 도시 점수 섀도우 기록 테스트 — 도시 파싱·현지 버킷·점수 조회·사이클
// 기록·일별 롤업·보관 상한·점수표 정본 대조·배선 가드를 고정한다.
// 실행: node test/shadow.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const {
  tempCityOfSlug,
  tempLocalBucketOf,
  shadowScoreFor,
  tempEventDayKey,
  buildShadowCycleRecord,
  parseShadowStore,
  appendShadowCycle,
  recordShadowCycle,
  CITY_TIMEZONES,
  CITY_SHADOW_SCORES,
  TEMP_NO_CITIES,
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

const slug = (city, temp) =>
  `highest-temperature-in-${city}-on-october-6-2026-${temp}c`;
const END = "2026-10-06T12:00:00Z";
const cand = (city, temp, price) => ({
  slug: slug(city, temp), price, endDate: END, strategy: "temp_no",
});

// ── 도시 파싱 ────────────────────────────────────────────────
eq(tempCityOfSlug(slug("amsterdam", 16)), "amsterdam", "도시 파싱: 단순");
eq(tempCityOfSlug(slug("buenos-aires", 30)), "buenos-aires", "도시 파싱: 하이픈 도시");
eq(tempCityOfSlug(slug("wellington", 21)), "wellington", "도시 파싱: on을 포함하는 도시명");
eq(tempCityOfSlug("highest-temperature-in-seoul-on-october-6-2026"), "seoul", "도시 파싱: 이벤트 슬러그");
eq(tempCityOfSlug("some-other-market"), null, "도시 파싱: 기온 슬러그 아님");
eq(tempCityOfSlug(""), null, "도시 파싱: 빈 문자열");
eq(tempCityOfSlug(null), null, "도시 파싱: null");
eq(tempCityOfSlug("highest-temperature-in-"), null, "도시 파싱: 꼬리 없음");

// ── 현지 시각 버킷 ───────────────────────────────────────────
const T0 = Date.UTC(2026, 9, 6, 0, 0); // 2026-10-06 00:00 UTC
eq(tempLocalBucketOf("seoul", T0), "06-10", "버킷: 서울 09:00");
eq(tempLocalBucketOf("amsterdam", T0), "00-06", "버킷: 암스테르담 02:00 (CEST)");
eq(tempLocalBucketOf("los-angeles", T0), "14-18", "버킷: LA 전날 17:00 (PDT)");
eq(tempLocalBucketOf("wellington", T0), "10-14", "버킷: 웰링턴 13:00 (NZDT)");
eq(tempLocalBucketOf("nyc", Date.UTC(2026, 0, 15, 12, 0)), "06-10", "버킷: 뉴욕 겨울 07:00 (EST)");
eq(tempLocalBucketOf("amsterdam", Date.UTC(2026, 0, 15, 4, 0)), "00-06", "버킷: 암스테르담 겨울 05:00 (CET)");
eq(tempLocalBucketOf("amsterdam", Date.UTC(2026, 6, 15, 4, 0)), "06-10", "버킷: 암스테르담 여름 06:00 (CEST) — 서머타임 반영");
eq(tempLocalBucketOf("atlantis", T0), null, "버킷: 모르는 도시");
eq(tempLocalBucketOf("seoul", NaN), null, "버킷: NaN 시각");

// ── 점수 조회 ────────────────────────────────────────────────
eq(shadowScoreFor("seoul", "06-10"), -0.0485, "점수: 정확 셀");
eq(shadowScoreFor("paris", "14-18"), -0.1539, "점수: 정확 셀 (파리)");
eq(shadowScoreFor("austin", "10-14"), 0.0091, "점수: 버킷 없음 → 도시 평균");
eq(shadowScoreFor("london", "14-18"), 0.0465, "점수: 버킷 없음 → 도시 평균 (런던)");
eq(shadowScoreFor("los-angeles", "06-10"), 0.1166, "점수: 버킷 없음 → 도시 평균 (LA)");
eq(shadowScoreFor("atlantis", "06-10"), null, "점수: 모르는 도시 → 무점수");
eq(shadowScoreFor("seoul", "06-10", null), null, "점수: 표 없음 → 무점수");
{
  const synth = { x: { "00-06": 1, "06-10": 3 }, y: {} };
  eq(shadowScoreFor("x", "00-06", synth), 1, "점수: 합성표 정확 셀");
  eq(shadowScoreFor("x", "10-14", synth), 2, "점수: 합성표 도시 평균");
  eq(shadowScoreFor("y", "00-06", synth), null, "점수: 빈 도시 → 무점수");
}

// ── 이벤트 날짜 키 ───────────────────────────────────────────
eq(tempEventDayKey(END), "2026-10-06", "날짜 키: ISO");
eq(tempEventDayKey("2026-10-06"), "2026-10-06", "날짜 키: 날짜만");
eq(tempEventDayKey(""), null, "날짜 키: 빈 값");
eq(tempEventDayKey("garbage"), null, "날짜 키: 깨진 값");

// ── 사이클 기록 ──────────────────────────────────────────────
{
  const dallas = cand("dallas", 30, 0.95);
  const paris = cand("paris", 18, 0.99);
  const atlantis = {
    slug: "highest-temperature-in-atlantis-on-october-6-2026-20c",
    price: 0.97, endDate: END, strategy: "temp_no",
  };
  const rec = buildShadowCycleRecord({
    ts: T0, cycle: 7, cash: 42.5, slots: 3, openCount: 2,
    cands: [paris, dallas, atlantis],
    enteredSlugs: new Set([dallas.slug]),
  });
  eq(rec.cycle, 7, "기록: 사이클 번호");
  eq(rec.cash, 42.5, "기록: 현금");
  eq(rec.slots, 3, "기록: 슬롯");
  eq(rec.openCount, 2, "기록: 보유 수");
  eq(rec.cands.length, 3, "기록: 후보 수");
  const bySlug = Object.fromEntries(rec.cands.map((r) => [r.slug, r]));
  eq(bySlug[dallas.slug].rank, 1, "기록: 최고 점수가 1위");
  eq(bySlug[dallas.slug].score, 0.1278, "기록: 댈러스 점수");
  eq(bySlug[dallas.slug].entered, true, "기록: 진입 표시");
  eq(bySlug[paris.slug].rank, 2, "기록: 파리 2위");
  eq(bySlug[paris.slug].entered, false, "기록: 미진입 표시");
  eq(bySlug[atlantis.slug].score, null, "기록: 무점수 도시는 score null");
  eq(bySlug[atlantis.slug].bucket, null, "기록: 무점수 도시는 bucket null");
  eq(bySlug[atlantis.slug].rank, 3, "기록: 무점수는 순위 뒤");
  eq(bySlug[dallas.slug].date, "2026-10-06", "기록: 이벤트 날짜");
  eq(bySlug[dallas.slug].city, "dallas", "기록: 도시");
}
{
  // 동률(같은 도시 두 시장)은 평가 순서를 유지한다.
  const a = cand("seoul", 28, 0.95);
  const b = cand("seoul", 29, 0.96);
  const rec = buildShadowCycleRecord({
    ts: T0, cycle: 1, cash: 10, slots: 1, openCount: 0,
    cands: [a, b], enteredSlugs: new Set(),
  });
  eq(rec.cands[0].rank, 1, "기록: 동률은 평가 순서 1");
  eq(rec.cands[1].rank, 2, "기록: 동률은 평가 순서 2");
}

// ── 저장소 파싱 ──────────────────────────────────────────────
eq(parseShadowStore(null), { v: 1, cycles: [], days: [] }, "저장소: null → 빈 저장소");
eq(parseShadowStore("garbage"), { v: 1, cycles: [], days: [] }, "저장소: 깨진 원문 → 빈 저장소");
eq(parseShadowStore("[]"), { v: 1, cycles: [], days: [] }, "저장소: 배열 → 빈 저장소");
eq(parseShadowStore('{"cycles":[1],"days":"x"}'), { v: 1, cycles: [], days: [] }, "저장소: 부분 손상 관용 (객체 아닌 항목 제거)");
{
  const mixed = parseShadowStore('{"cycles":[null,{"a":1}],"days":[null,{"date":"2026-10-06","cands":[],"entered":[]}]}');
  eq(mixed.cycles.length, 1, "저장소: null 항목은 cycles에서 걸러짐");
  eq(mixed.days.length, 1, "저장소: null 항목은 days에서 걸러짐");
  const withNullDay = parseShadowStore('{"days":[null]}');
  appendShadowCycle(withNullDay, buildShadowCycleRecord({
    ts: T0, cycle: 1, cash: 1, slots: 1, openCount: 0,
    cands: [cand("seoul", 28, 0.95)], enteredSlugs: new Set(),
  }));
  eq(withNullDay.days.length, 1, "저장소: null 날짜 항목이 있어도 붙이기 성공");
}

// ── 일별 롤업과 섀도우 픽 ────────────────────────────────────
{
  const store = parseShadowStore(null);
  const dallas = cand("dallas", 30, 0.95);
  const seoul = cand("seoul", 28, 0.96);
  const paris = cand("paris", 18, 0.99);
  appendShadowCycle(store, buildShadowCycleRecord({
    ts: T0, cycle: 1, cash: 50, slots: 5, openCount: 0,
    cands: [paris, seoul, dallas], enteredSlugs: new Set([seoul.slug]),
  }));
  appendShadowCycle(store, buildShadowCycleRecord({
    ts: T0 + 60_000, cycle: 2, cash: 45, slots: 4, openCount: 1,
    cands: [dallas, seoul], enteredSlugs: new Set([seoul.slug, dallas.slug]),
  }));
  eq(store.cycles.length, 2, "롤업: 사이클 2건 보관");
  eq(store.days.length, 1, "롤업: 하루로 합쳐짐");
  const day = store.days[0];
  eq(day.date, "2026-10-06", "롤업: 날짜");
  eq(day.cands.length, 3, "롤업: 후보는 slug로 중복 제거");
  const dallasDay = day.cands.find((c) => c.slug === dallas.slug);
  eq(dallasDay.price, 0.95, "롤업: 첫 관측 가격 유지");
  eq(dallasDay.firstTs, T0, "롤업: 첫 관측 시각");
  eq(day.entered, [seoul.slug, dallas.slug], "롤업: 진입은 순서대로 한 번만");
  eq(day.shadow["1"], [dallas.slug], "섀도우 픽: 상한 1");
  eq(day.shadow["2"], [dallas.slug, seoul.slug], "섀도우 픽: 상한 2");
  eq(day.shadow["3"], [dallas.slug, seoul.slug, paris.slug], "섀도우 픽: 상한 3");
}
{
  // 날짜 없는 후보는 사이클 기록에만 남고 롤업에 안 들어간다.
  const store = parseShadowStore(null);
  const noDate = { slug: slug("seoul", 28), price: 0.95, endDate: "", strategy: "temp_no" };
  appendShadowCycle(store, buildShadowCycleRecord({
    ts: T0, cycle: 1, cash: 1, slots: 1, openCount: 0,
    cands: [noDate], enteredSlugs: new Set(),
  }));
  eq(store.cycles.length, 1, "롤업: 날짜 없는 후보도 사이클 기록에는 남음");
  eq(store.days.length, 0, "롤업: 날짜 없는 후보는 롤업 제외");
}

// ── 보관 상한 ────────────────────────────────────────────────
{
  const store = parseShadowStore(null);
  const c = cand("seoul", 28, 0.95);
  for (let i = 0; i < 5; i++) {
    appendShadowCycle(store, buildShadowCycleRecord({
      ts: T0 + i * 1000, cycle: i, cash: 1, slots: 1, openCount: 0,
      cands: [c], enteredSlugs: new Set(),
    }), { maxCycles: 2 });
  }
  eq(store.cycles.length, 2, "상한: 사이클 수 상한");
  eq(store.cycles[0].cycle, 3, "상한: 오래된 사이클부터 버림");
}
{
  const store = parseShadowStore(null);
  const c = cand("seoul", 28, 0.95);
  const rec = buildShadowCycleRecord({
    ts: T0, cycle: 1, cash: 1, slots: 1, openCount: 0,
    cands: [c], enteredSlugs: new Set(),
  });
  appendShadowCycle(store, rec, { maxBytes: 10 });
  eq(store.cycles.length, 0, "상한: 바이트 상한이 사이클을 비움");
  eq(store.days.length, 1, "상한: 마지막 하루는 남김");
}
{
  const store = parseShadowStore(null);
  for (let d = 1; d <= 5; d++) {
    const end = `2026-10-0${d}T12:00:00Z`;
    const c = { slug: slug("seoul", 28), price: 0.95, endDate: end, strategy: "temp_no" };
    appendShadowCycle(store, buildShadowCycleRecord({
      ts: T0, cycle: d, cash: 1, slots: 1, openCount: 0,
      cands: [c], enteredSlugs: new Set(),
    }), { maxDays: 3 });
  }
  eq(store.days.map((x) => x.date), ["2026-10-03", "2026-10-04", "2026-10-05"], "상한: 최근 날짜만 보관");
}

// ── 기록 드라이버 (durable 왕복) ─────────────────────────────
{
  lsStore.clear();
  lsStore.set("bm_shadow", "garbage{{{");
  recordShadowCycle({
    ts: T0, cycle: 3,
    cash: 20, slots: 2, openCount: 1,
    cands: [cand("dallas", 30, 0.95)],
    enteredSlugs: new Set(),
  });
  const stored = parseShadowStore(lsStore.get("bm_shadow"));
  eq(stored.cycles.length, 1, "드라이버: 깨진 저장소를 딛고 기록");
  eq(stored.days.length, 1, "드라이버: 롤업 생성");
  recordShadowCycle({ ts: T0, cycle: 4, cash: 1, slots: 1, openCount: 0, cands: [], enteredSlugs: new Set() });
  eq(parseShadowStore(lsStore.get("bm_shadow")).cycles.length, 1, "드라이버: 후보 없으면 기록 안 함");
}

// ── 점수표 정본 대조 ─────────────────────────────────────────
{
  const keys = JSON.parse(readFileSync(
    new URL("../../queue-20261124/citytime_sort_keys.json", import.meta.url), "utf8"));
  eq(CITY_SHADOW_SCORES, keys.scores, "점수표: 연구 산출물과 정확히 일치");
  eq(Object.keys(CITY_TIMEZONES).sort(), [...TEMP_NO_CITIES].sort(), "시간대 표: 앱 도시 전체를 덮음");
  eq(Object.keys(CITY_SHADOW_SCORES).sort(), [...TEMP_NO_CITIES].sort(), "점수표: 앱 도시 전체를 덮음");
}

// ── 배선 가드 ────────────────────────────────────────────────
{
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  ok(src.includes('shadow: "bm_shadow"'), "배선: LS.shadow 키");
  ok(src.includes("recordShadowCycle({"), "배선: runCycle 기록 호출");
  ok(src.includes("boughtBeforeBuy"), "배선: 진입 판정 기준 캡처");
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  ok(html.includes("payload.shadow"), "배선: 제보 payload에 shadow");
  ok(html.includes("getDurable('bm_shadow')"), "배선: 제보가 네이티브 durable에서 읽음");
  const nativeCycle = readFileSync(
    new URL("../../android/src/com/roomedia/boringmech/engine/NativeCycle.java", import.meta.url), "utf8");
  ok(nativeCycle.includes("ShadowLog.recordCycle"), "배선: 네이티브 사이클 기록 호출");
}

console.log(`shadow: ${passed} passed, ${failed} failed`);
if (failed) process.exit(1);
