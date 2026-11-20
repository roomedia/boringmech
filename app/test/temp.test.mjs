// 기온 No 페이드 순수 로직 테스트 — 정수/꼬리 판정, 밴드 경계, 진입 창
// 경계, 토글 OFF 무후보, 수수료 해석, 슬러그 생성, 매수 전략 태그를 대조한다.
// 실행: node test/temp.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const {
  isTempNoSlug,
  isExactTempTitle,
  tempEventSlugFor,
  tempWindowOpen,
  parseTempSettings,
  scanTempCandidates,
  resolveTempFeeRate,
  markBought,
  boughtStrategyOf,
  TEMP_NO_CITIES,
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

// ── 슬러그·제목 판정 ─────────────────────────────────────────
eq(isTempNoSlug("highest-temperature-in-seoul-on-november-18-2026"), true, "temp: event slug");
eq(isTempNoSlug("highest-temperature-in-seoul-on-november-18-2026-7c"), true, "temp: market slug");
eq(isTempNoSlug("will-btc-hit-100k"), false, "temp: other slug");
eq(isTempNoSlug(null), false, "temp: null slug");
eq(isExactTempTitle("3°C"), true, "title: exact C");
eq(isExactTempTitle("72°F"), true, "title: exact F");
eq(isExactTempTitle("-3°C"), true, "title: negative exact");
eq(isExactTempTitle("6°C or below"), false, "title: tail below");
eq(isExactTempTitle("21°C or higher"), false, "title: tail higher");
eq(isExactTempTitle("50-51°F"), false, "title: range bucket");
eq(isExactTempTitle(""), false, "title: empty");
eq(isExactTempTitle(null), false, "title: null");
eq(isExactTempTitle(undefined), false, "title: undefined");

// ── 이벤트 슬러그 생성 ───────────────────────────────────────
eq(
  tempEventSlugFor("seoul", Date.UTC(2026, 10, 18, 7)),
  "highest-temperature-in-seoul-on-november-18-2026",
  "slug: seoul today",
);
eq(TEMP_NO_CITIES.length, 40, "cities: 40 harvested");
eq(TEMP_NO_CITIES.includes("seoul") && TEMP_NO_CITIES.includes("nyc"), true, "cities: seoul+nyc");

// ── 진입 창 경계 (endDate 2026-11-18T12:00:00Z) ─────────────
const END_MS = Date.parse("2026-11-18T12:00:00Z");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 5, 59, 59), END_MS), false, "window: 05:59 closed");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 6, 0, 0), END_MS), true, "window: 06:00 open");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 11, 59, 59), END_MS), true, "window: 11:59 open");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 12, 0, 0), END_MS), false, "window: endDate closed");
eq(tempWindowOpen(NaN, END_MS), false, "window: NaN now");

// ── 설정 해석 ────────────────────────────────────────────────
eq(parseTempSettings(null), { enabled: false, bandLo: 0.8 }, "settings: null default off");
eq(parseTempSettings("not json"), { enabled: false, bandLo: 0.8 }, "settings: bad json");
eq(
  parseTempSettings('{"tempNoEnabled":true,"tempNoBandLo":0.93}'),
  { enabled: true, bandLo: 0.93 },
  "settings: on + custom band",
);
eq(
  parseTempSettings('{"tempNoEnabled":true,"tempNoBandLo":0.999}'),
  { enabled: true, bandLo: 0.8 },
  "settings: band above hi falls back",
);
eq(
  parseTempSettings('{"tempNoEnabled":"true","tempNoBandLo":0.9}'),
  { enabled: false, bandLo: 0.9 },
  "settings: string true is off",
);
eq(
  parseTempSettings({ tempNoEnabled: true, tempNoBandLo: 0.998 }),
  { enabled: true, bandLo: 0.998 },
  "settings: band hi boundary kept",
);

// ── 수수료 해석 ──────────────────────────────────────────────
eq(resolveTempFeeRate({ feeType: "weather_fees" }), 0.05, "fee: weather type");
eq(resolveTempFeeRate({ feeSchedule: { rate: 0.05 } }), 0.05, "fee: schedule fallback");
eq(resolveTempFeeRate({ feeType: "crypto_fees_v2" }), 0.07, "fee: crypto type");
eq(resolveTempFeeRate({}), null, "fee: unknown");

// ── 후보 스캔 ────────────────────────────────────────────────
const EVENT_SLUG = "highest-temperature-in-seoul-on-november-18-2026";
function mkMarket(title, noPrice, extra = {}) {
  return {
    slug: `${EVENT_SLUG}-m${title}`,
    question: "Will the highest temperature in Seoul be X?",
    groupItemTitle: title,
    active: true,
    closed: false,
    acceptingOrders: true,
    endDate: "2026-11-18T12:00:00Z",
    outcomePrices: JSON.stringify([String(1 - Number(noPrice)), noPrice]),
    feeType: "weather_fees",
    feeSchedule: { rate: 0.05 },
    ...extra,
  };
}
function evOf(markets) {
  return [{ slug: EVENT_SLUG, endDate: "2026-11-18T12:00:00Z", markets }];
}
const AT_7AM = Date.UTC(2026, 10, 18, 7);
const ON = { enabled: true, bandLo: 0.8 };
const NONE = new Set();

eq(scanTempCandidates(evOf([mkMarket("7°C", "0.9")]), NONE, NONE, { enabled: false, bandLo: 0.8 }, AT_7AM).length, 0, "scan: toggle off no candidates");
eq(scanTempCandidates(evOf([mkMarket("7°C", "0.8")]), NONE, NONE, ON, AT_7AM).length, 1, "scan: band lo inclusive");
eq(scanTempCandidates(evOf([mkMarket("7°C", "0.998")]), NONE, NONE, ON, AT_7AM).length, 1, "scan: band hi 0.998 inclusive");
eq(scanTempCandidates(evOf([mkMarket("7°C", "0.999")]), NONE, NONE, ON, AT_7AM).length, 0, "scan: 0.999 excluded");
eq(scanTempCandidates(evOf([mkMarket("7°C", "0.799")]), NONE, NONE, ON, AT_7AM).length, 0, "scan: below lo excluded");
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.92")]), NONE, NONE, { enabled: true, bandLo: 0.93 }, AT_7AM).length,
  0,
  "scan: custom lo excludes 0.92",
);
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.93")]), NONE, NONE, { enabled: true, bandLo: 0.93 }, AT_7AM).length,
  1,
  "scan: custom lo inclusive",
);
eq(scanTempCandidates(evOf([mkMarket("6°C or below", "0.9")]), NONE, NONE, ON, AT_7AM).length, 0, "scan: tail excluded");
eq(scanTempCandidates(evOf([mkMarket("50-51°F", "0.9")]), NONE, NONE, ON, AT_7AM).length, 0, "scan: range excluded");
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.9")]), NONE, NONE, ON, Date.UTC(2026, 10, 18, 5, 59)).length,
  0,
  "scan: before window no candidates",
);
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.9")]), NONE, NONE, ON, Date.UTC(2026, 10, 18, 12, 0)).length,
  0,
  "scan: after endDate no candidates",
);
{
  const m = mkMarket("7°C", "0.9");
  eq(scanTempCandidates(evOf([m]), new Set([m.slug]), NONE, ON, AT_7AM).length, 0, "scan: held excluded");
  eq(scanTempCandidates(evOf([m]), NONE, new Set([m.slug]), ON, AT_7AM).length, 0, "scan: bought excluded");
  eq(scanTempCandidates(evOf([m]), NONE, NONE, ON, AT_7AM, null, new Set([m.slug])).length, 0, "scan: main-slug excluded");
}
{
  const cands = scanTempCandidates(evOf([mkMarket("7°C", "0.9")]), NONE, NONE, ON, AT_7AM);
  eq(cands[0].side, "no", "scan: side is no");
  eq(cands[0].strategy, "temp_no", "scan: strategy tag");
  eq(cands[0].bandLo, 0.8, "scan: candidate bandLo");
  eq(cands[0].bandHi, 0.998, "scan: candidate bandHi");
  eq(cands[0].feeRate, 0.05, "scan: candidate feeRate");
  eq(cands[0].price, 0.9, "scan: candidate price is No price");
}

// ── 매수 전략 태그 (손절 제외 판정) ──────────────────────────
// 제외 조건은 슬러그 패턴만이 아니라 매수 기록의 temp_no 태그까지다.
{
  const TEMP_SLUG = "highest-temperature-in-seoul-on-november-18-2026-7c";
  const exempt = (slug) => isTempNoSlug(slug) && boughtStrategyOf(slug) === "temp_no";
  lsStore.clear();
  eq(boughtStrategyOf(TEMP_SLUG), null, "tag: no record is main");
  eq(exempt(TEMP_SLUG), false, "tag: no record not exempt");
  markBought(TEMP_SLUG, "temp_no");
  eq(boughtStrategyOf(TEMP_SLUG), "temp_no", "tag: temp buy tagged");
  eq(exempt(TEMP_SLUG), true, "tag: tagged temp exempt");
  eq(
    JSON.parse(lsStore.get("bm_bought")),
    [{ slug: TEMP_SLUG, strategy: "temp_no" }],
    "tag: stored as tagged object",
  );
  // 메인 매수(태그 없음)는 문자열 형태로 남고 기온 슬러그여도 제외되지 않는다
  markBought("highest-temperature-in-seoul-on-november-18-2026-8c", undefined);
  eq(
    boughtStrategyOf("highest-temperature-in-seoul-on-november-18-2026-8c"),
    null,
    "tag: main buy untagged",
  );
  eq(
    exempt("highest-temperature-in-seoul-on-november-18-2026-8c"),
    false,
    "tag: untagged temp slug not exempt",
  );
  eq(
    JSON.parse(lsStore.get("bm_bought"))[1],
    "highest-temperature-in-seoul-on-november-18-2026-8c",
    "tag: main buy stored as plain string",
  );
  // 태그가 붙어도 기온 슬러그가 아니면 제외 판정과 무관하다
  markBought("will-btc-hit-100k", "temp_no");
  eq(exempt("will-btc-hit-100k"), false, "tag: tagged non-temp not exempt");
  // 기존 혼합 형태(문자열 + 태그 없는 객체)도 태그 없음으로 읽힌다
  lsStore.set("bm_bought", JSON.stringify(["a-mkt", { slug: "b-mkt" }, { slug: "c-mkt", strategy: "other" }]));
  eq(boughtStrategyOf("a-mkt"), null, "tag: legacy string untagged");
  eq(boughtStrategyOf("b-mkt"), null, "tag: object without strategy untagged");
  eq(boughtStrategyOf("c-mkt"), null, "tag: other strategy untagged");
  eq(boughtStrategyOf("never-bought"), null, "tag: unknown slug null");
  lsStore.clear();
}

console.log(`temp tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
