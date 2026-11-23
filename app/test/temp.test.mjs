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
  tempReserveBlocksMain,
  tempReserveLaneAllows,
  tempNearCapRejectNote,
  tempHasUpcomingWindow,
  tempEventSlugsForFetch,
  tempFetchDayKey,
  buyLimitPrice,
  effectiveTempReserveSlots,
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

// ── 조회 범위 슬러그 (오늘+내일) ─────────────────────────────
{
  const slugs = tempEventSlugsForFetch(Date.UTC(2026, 10, 18, 20));
  eq(slugs.length, 80, "fetch slugs: 40 cities x 2 days");
  eq(slugs[0], "highest-temperature-in-amsterdam-on-november-18-2026", "fetch slugs: today first");
  eq(
    slugs.includes("highest-temperature-in-seoul-on-november-18-2026"),
    true,
    "fetch slugs: today seoul included",
  );
  eq(
    slugs.includes("highest-temperature-in-seoul-on-november-19-2026"),
    true,
    "fetch slugs: tomorrow seoul included",
  );
  eq(new Set(slugs).size, 80, "fetch slugs: no duplicates");
}

// ── 진입 창 경계 (endDate 2026-11-18T12:00:00Z) ─────────────
const END_MS = Date.parse("2026-11-18T12:00:00Z");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 5, 59, 59), END_MS), false, "window: 05:59 closed");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 6, 0, 0), END_MS), true, "window: 06:00 open");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 11, 59, 59), END_MS), true, "window: 11:59 open");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 12, 0, 0), END_MS), false, "window: endDate closed");
eq(tempWindowOpen(NaN, END_MS), false, "window: NaN now");

// ── 설정 해석 ────────────────────────────────────────────────
const DEF_TEMP = { enabled: false, bandLo: 0.8, bandHi: 0.998, entryHourUtc: 6, reserveSlots: 1 };
eq(parseTempSettings(null), DEF_TEMP, "settings: null default off");
eq(parseTempSettings("not json"), DEF_TEMP, "settings: bad json");
eq(
  parseTempSettings('{"tempNoEnabled":true,"tempNoBandLo":0.93}'),
  { enabled: true, bandLo: 0.93, bandHi: 0.998, entryHourUtc: 6, reserveSlots: 1 },
  "settings: on + custom band",
);
eq(
  parseTempSettings('{"tempNoEnabled":true,"tempNoBandLo":0.999}'),
  { enabled: true, bandLo: 0.8, bandHi: 0.998, entryHourUtc: 6, reserveSlots: 1 },
  "settings: band above hi falls back",
);
eq(
  parseTempSettings('{"tempNoEnabled":"true","tempNoBandLo":0.9}'),
  { enabled: false, bandLo: 0.9, bandHi: 0.998, entryHourUtc: 6, reserveSlots: 1 },
  "settings: string true is off",
);
// 하한=상한(빈 밴드)은 쌍째로 기본값으로 되돌린다
eq(
  parseTempSettings({ tempNoEnabled: true, tempNoBandLo: 0.998 }),
  { enabled: true, bandLo: 0.8, bandHi: 0.998, entryHourUtc: 6, reserveSlots: 1 },
  "settings: band lo == hi resets pair",
);
eq(
  parseTempSettings('{"tempNoEnabled":true,"tempNoBandLo":0.85,"tempNoBandHi":0.99}'),
  { enabled: true, bandLo: 0.85, bandHi: 0.99, entryHourUtc: 6, reserveSlots: 1 },
  "settings: custom band hi kept",
);
eq(
  parseTempSettings('{"tempNoEnabled":true,"tempNoBandLo":0.95,"tempNoBandHi":0.9}'),
  { enabled: true, bandLo: 0.8, bandHi: 0.998, entryHourUtc: 6, reserveSlots: 1 },
  "settings: band lo >= hi resets pair",
);
eq(
  parseTempSettings('{"tempNoBandHi":1.5}').bandHi,
  0.998,
  "settings: band hi above 1 falls back",
);
eq(
  parseTempSettings('{"tempEntryHourUtc":0}').entryHourUtc,
  0,
  "settings: entry hour 0 kept",
);
eq(
  parseTempSettings('{"tempEntryHourUtc":23}').entryHourUtc,
  23,
  "settings: entry hour 23 kept",
);
eq(
  parseTempSettings('{"tempEntryHourUtc":24}').entryHourUtc,
  6,
  "settings: entry hour 24 falls back",
);
eq(
  parseTempSettings('{"tempEntryHourUtc":6.5}').entryHourUtc,
  6,
  "settings: entry hour fractional falls back",
);
// 진입 창 — 시작 시각 설정
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 0, 0, 0), END_MS, 0), true, "window: hour 0 opens at midnight");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 5, 0, 0), END_MS, 9), false, "window: hour 9 closed at 05:00");
eq(tempWindowOpen(Date.UTC(2026, 10, 18, 9, 0, 0), END_MS, 9), true, "window: hour 9 opens at 09:00");
// 예약 슬롯 수 — 0~70 정수만 받고 그 외는 기본값 1
eq(
  parseTempSettings('{"tempNoReserveSlots":0}').reserveSlots,
  0,
  "settings: reserve 0 kept (no reserve)",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":3}').reserveSlots,
  3,
  "settings: reserve 3 kept",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":70}').reserveSlots,
  70,
  "settings: reserve max kept",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":71}').reserveSlots,
  1,
  "settings: reserve above max falls back",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":-1}').reserveSlots,
  1,
  "settings: reserve negative falls back",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":2.5}').reserveSlots,
  1,
  "settings: reserve fractional falls back",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":"3"}').reserveSlots,
  3,
  "settings: reserve numeric string coerced (Number semantics)",
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
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.998")]), NONE, NONE, { enabled: true, bandLo: 0.8, bandHi: 0.99 }, AT_7AM).length,
  0,
  "scan: custom hi 0.99 excludes 0.998",
);
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.99")]), NONE, NONE, { enabled: true, bandLo: 0.8, bandHi: 0.99 }, AT_7AM).length,
  1,
  "scan: custom hi inclusive",
);
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.9")]), NONE, NONE, { enabled: true, bandLo: 0.8, entryHourUtc: 9 }, AT_7AM).length,
  0,
  "scan: entry hour 9 closed at 07:00",
);
eq(
  scanTempCandidates(evOf([mkMarket("7°C", "0.9")]), NONE, NONE, { enabled: true, bandLo: 0.8, entryHourUtc: 9 }, Date.UTC(2026, 10, 18, 9)).length,
  1,
  "scan: entry hour 9 open at 09:00",
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

// ── 매수 전략 태그 (기온 손절 폭 판정) ──────────────────────
// 기온 전용 손절 폭(-0.15) 조건은 슬러그 패턴만이 아니라 매수 기록의
// temp_no 태그까지다. 태그가 없으면 기온 슬러그여도 메인 폭(-0.25)이다.
{
  const TEMP_SLUG = "highest-temperature-in-seoul-on-november-18-2026-7c";
  const exempt = (slug) => isTempNoSlug(slug) && boughtStrategyOf(slug) === "temp_no";
  lsStore.clear();
  eq(boughtStrategyOf(TEMP_SLUG), null, "tag: no record is main");
  eq(exempt(TEMP_SLUG), false, "tag: no record not temp");
  markBought(TEMP_SLUG, "temp_no");
  eq(boughtStrategyOf(TEMP_SLUG), "temp_no", "tag: temp buy tagged");
  eq(exempt(TEMP_SLUG), true, "tag: tagged temp is temp");
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
    "tag: untagged temp slug not temp",
  );
  eq(
    JSON.parse(lsStore.get("bm_bought"))[1],
    "highest-temperature-in-seoul-on-november-18-2026-8c",
    "tag: main buy stored as plain string",
  );
  // 태그가 붙어도 기온 슬러그가 아니면 기온 폭 판정과 무관하다
  markBought("will-btc-hit-100k", "temp_no");
  eq(exempt("will-btc-hit-100k"), false, "tag: tagged non-temp not temp");
  // 기존 혼합 형태(문자열 + 태그 없는 객체)도 태그 없음으로 읽힌다
  lsStore.set("bm_bought", JSON.stringify(["a-mkt", { slug: "b-mkt" }, { slug: "c-mkt", strategy: "other" }]));
  eq(boughtStrategyOf("a-mkt"), null, "tag: legacy string untagged");
  eq(boughtStrategyOf("b-mkt"), null, "tag: object without strategy untagged");
  eq(boughtStrategyOf("c-mkt"), null, "tag: other strategy untagged");
  eq(boughtStrategyOf("never-bought"), null, "tag: unknown slug null");
  lsStore.clear();
}

// ── 슬롯 예약 판정 (네이티브 TempNo.reserveBlocksMain과 같은 경계) ──
{
  const CAP = 60; // VERIFY_CAP
  eq(tempReserveBlocksMain(undefined, 2, 0), false, "reserve: main, slots 2, verified 0 → no block");
  eq(tempReserveBlocksMain(undefined, 1, 0), true, "reserve: main, slots 1 → block");
  eq(tempReserveBlocksMain(undefined, 0, 0), true, "reserve: main, slots 0 → block");
  eq(tempReserveBlocksMain(undefined, 5, CAP - 2), false, "reserve: main, verified cap-2 → no block");
  eq(tempReserveBlocksMain(undefined, 5, CAP - 1), true, "reserve: main, verified cap-1 → block");
  eq(tempReserveBlocksMain("temp_no", 1, CAP - 1), false, "reserve: temp is never blocked");
  eq(tempReserveBlocksMain("something_else", 1, 0), true, "reserve: other strategy counts as main");
  // 예약 0개 — 아무것도 차단하지 않는다
  eq(tempReserveBlocksMain(undefined, 0, 0, 0), false, "reserve 0: slots 0 → no block");
  eq(tempReserveBlocksMain(undefined, 1, CAP - 1, 0), false, "reserve 0: at cap → no block");
  // 예약 3개 — 슬롯·검증 모두 3개 지점부터 차단한다
  eq(tempReserveBlocksMain(undefined, 4, 0, 3), false, "reserve 3: slots 4 → no block");
  eq(tempReserveBlocksMain(undefined, 3, 0, 3), true, "reserve 3: slots 3 → block");
  eq(tempReserveBlocksMain(undefined, 9, CAP - 4, 3), false, "reserve 3: verified cap-4 → no block");
  eq(tempReserveBlocksMain(undefined, 9, CAP - 3, 3), true, "reserve 3: verified cap-3 → block");
  eq(tempReserveBlocksMain("temp_no", 3, CAP - 3, 3), false, "reserve 3: temp is never blocked");

  // 창 대기 판정 (네이티브 TempNo.hasUpcomingWindow와 같은 경계) —
  // 이벤트 endDate 2026-11-15T12:00Z, 진입 창은 당일 06:00~12:00 UTC.
  // v0.3.39 감사 D부터는 markets 중 후보 가능 시장이 있을 때만 센다.
  const MKT7 = {
    slug: "highest-temperature-in-seoul-on-november-15-2026-7c",
    groupItemTitle: "7°C", active: true, closed: false, acceptingOrders: true,
  };
  const evUpcoming = [{
    slug: "highest-temperature-in-seoul-on-november-15-2026",
    endDate: "2026-11-15T12:00:00Z",
    markets: [MKT7],
  }];
  const T_PRE = Date.parse("2026-11-15T03:00:00Z");
  const T_OPEN = Date.parse("2026-11-15T08:00:00Z");
  const T_PAST = Date.parse("2026-11-15T13:00:00Z");
  eq(tempHasUpcomingWindow(evUpcoming, T_PRE), true, "upcoming: pre-window event → true");
  eq(tempHasUpcomingWindow(evUpcoming, T_OPEN), true, "upcoming: window open → true");
  eq(tempHasUpcomingWindow(evUpcoming, T_PAST), false, "upcoming: past event only → false");
  // 내일 이벤트는 전날 저녁부터 보인다 — 홀드 구간이 전날 저녁까지 닿는다
  const evTomorrow = [{
    slug: "highest-temperature-in-seoul-on-november-16-2026",
    endDate: "2026-11-16T12:00:00Z",
    markets: [{ ...MKT7, slug: "highest-temperature-in-seoul-on-november-16-2026-7c" }],
  }];
  eq(
    tempHasUpcomingWindow(evTomorrow, Date.parse("2026-11-15T20:00:00Z")),
    true,
    "upcoming: tomorrow event seen the evening before → true",
  );
  eq(
    tempHasUpcomingWindow(evTomorrow, Date.parse("2026-11-16T13:00:00Z")),
    false,
    "upcoming: tomorrow event after its endDate → false",
  );
  eq(tempHasUpcomingWindow([], T_PRE), false, "upcoming: empty events → false");
  eq(tempHasUpcomingWindow(null, T_PRE), false, "upcoming: null events → false");
  eq(
    tempHasUpcomingWindow([{ slug: "some-other-event", endDate: "2026-11-15T12:00:00Z", markets: [MKT7] }], T_PRE),
    false,
    "upcoming: non-temp slug → false",
  );
  eq(
    tempHasUpcomingWindow([{ slug: "highest-temperature-in-seoul-on-november-15-2026", endDate: "not-a-date", markets: [MKT7] }], T_PRE),
    false,
    "upcoming: bad endDate → false",
  );
  // 감사 D — 후보 가능 시장이 없으면 창이 남아 있어도 upcoming이 아니다
  eq(
    tempHasUpcomingWindow(evUpcoming, T_PRE, 6, new Set([MKT7.slug]), new Set()),
    false,
    "auditD: all markets held → false",
  );
  eq(
    tempHasUpcomingWindow(evUpcoming, T_PRE, 6, new Set(), new Set([MKT7.slug])),
    false,
    "auditD: all markets bought → false",
  );
  eq(
    tempHasUpcomingWindow(
      [{ ...evUpcoming[0], markets: [{ ...MKT7, closed: true }] }], T_PRE),
    false,
    "auditD: all markets closed → false",
  );
  eq(
    tempHasUpcomingWindow(
      [{ ...evUpcoming[0], markets: [{ ...MKT7, groupItemTitle: "6°C or below" }] }], T_PRE),
    false,
    "auditD: tail bucket only → false",
  );
  eq(
    tempHasUpcomingWindow([{ ...evUpcoming[0], markets: [] }], T_PRE),
    false,
    "auditD: no markets → false",
  );
  eq(
    tempHasUpcomingWindow(
      [{ ...evUpcoming[0], markets: [{ ...MKT7, slug: "m-held" }, { ...MKT7, slug: "m-free" }] }],
      T_PRE, 6, new Set(["m-held"]), new Set()),
    true,
    "auditD: one candidate market among held → true",
  );

  // 구조 가드 — runCycle 드라이버가 홀드 판정으로 메인을 차단하고
  // 차단한 메인을 다시 태우지 않는지(재주행이 있으면 빈 슬롯이 사라진다)
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const cycleSrc = src.slice(src.indexOf("async function runCycle"));
  eq(
    cycleSrc.includes("tempReserveBlocksMain(cand.strategy, slotsLeft, verified, tempReserveSlots, tradeCfg.verifyCap)"),
    true,
    "hold guard: driver blocks via predicate with clamped reserve",
  );
  eq(
    cycleSrc.includes("effectiveTempReserveSlots(tempSettings.reserveSlots, tradeCfg.maxPositions)"),
    true,
    "hold guard: driver clamps reserve by maxPositions",
  );
  eq(
    cycleSrc.includes("const tempHold = tempSettings.enabled"),
    true,
    "hold guard: driver computes hold activation",
  );
  eq(
    cycleSrc.includes("&& tempReserveSlots > 0"),
    true,
    "hold guard: reserve 0 means no hold",
  );
  eq(
    cycleSrc.includes("deferredMain"),
    false,
    "hold guard: no deferred-main second pass (held slots stay empty)",
  );
  eq(
    cycleSrc.includes("|| tempHasUpcomingWindow(tempEventsList, Date.now(), tempSettings.entryHourUtc, snap.heldSlugs, boughtAll))"),
    true,
    "hold guard: hold also activates on upcoming window (markets-aware)",
  );
}

// ── 예약 차선 판정 (네이티브 TempNo.reserveLaneAllows와 같은 경계) ──
{
  // 기준: bet $3, 예비금 $2.5 → 현금이 5.5 이상이면 차선 허용
  const base = {
    strategy: "temp_no", tempHold: true, laneLeft: 1,
    positionsUsed: 0, maxPositions: 70, cashLeft: 10, bet: 3, reserveUsd: 2.5,
  };
  eq(tempReserveLaneAllows(base), true, "lane: temp + hold + cash enough → allow");
  eq(tempReserveLaneAllows({ ...base, cashLeft: 5.5 }), true, "lane: cash exactly bet+reserve → allow");
  eq(tempReserveLaneAllows({ ...base, cashLeft: 5.49 }), false, "lane: cash below bet+reserve → deny");
  eq(tempReserveLaneAllows({ ...base, strategy: undefined }), false, "lane: main strategy → deny");
  eq(tempReserveLaneAllows({ ...base, tempHold: false }), false, "lane: hold off → deny");
  eq(tempReserveLaneAllows({ ...base, laneLeft: 0 }), false, "lane: lane exhausted → deny");
  eq(tempReserveLaneAllows({ ...base, positionsUsed: 70 }), false, "lane: positions full → deny");
  eq(tempReserveLaneAllows({ ...base, bet: 0, cashLeft: 100 }), false, "lane: bet 0 → deny (sizing bet=0 가드 우회 차단)");

  // 구조 가드 — 드라이버가 슬롯 소진 뒤 차선으로 기온만 더 시도하고,
  // 슬롯 0 조기 반환도 차선 가능성을 먼저 보는지 확인한다.
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const cycleSrc = src.slice(src.indexOf("async function runCycle"));
  eq(
    cycleSrc.includes("tempReserveLaneAllows({"),
    true,
    "lane guard: driver attempts lane after slots exhausted",
  );
  eq(
    cycleSrc.includes("기온 예약 차선으로 진입을 시도합니다"),
    true,
    "lane guard: slots-0 early return yields to lane possibility",
  );
  eq(
    cycleSrc.includes("laneLeft = tempHold ? tempReserveSlots : 0;"),
    true,
    "lane guard: lane budget is the reserve count while hold is on",
  );
  // 최소 수량 검사 — 기온만 제외되고 메인은 기존대로 걸린다
  const verifySrc = src.slice(src.indexOf("async function verifyCandidate"), src.indexOf("export function summarizeCycle"));
  eq(
    verifySrc.includes('if (!isTemp && estShares < minSize) return reject("below_min_order_size");'),
    true,
    "lane guard: min order size skips temp only",
  );
}

// ── 상한 근처 탈락 메모 (네이티브 TempNo.nearCapRejectNote와 같은 경계) ──
{
  const base = { strategy: "temp_no", slug: "mkt-7c", snapshot: 0.998, bestAsk: 0.999, bandHi: 0.998 };
  eq(
    tempNearCapRejectNote(base),
    "기온 상한 근처 탈락 mkt-7c · 스냅샷 0.998 · bestAsk 0.999 · 밴드 상한 0.998",
    "nearcap: ask 1틱 위 → 메모",
  );
  eq(
    tempNearCapRejectNote({ ...base, bestAsk: 0.91, bandHi: 0.9 }),
    "기온 상한 근처 탈락 mkt-7c · 스냅샷 0.998 · bestAsk 0.91 · 밴드 상한 0.9",
    "nearcap: 정확히 0.01 위 → 메모",
  );
  eq(tempNearCapRejectNote({ ...base, bestAsk: 0.95, bandHi: 0.9 }), null, "nearcap: 0.05 위 → 무메모");
  eq(tempNearCapRejectNote({ ...base, bestAsk: 0.79 }), null, "nearcap: 하한 아래 탈락 → 무메모");
  eq(tempNearCapRejectNote({ ...base, bestAsk: 0.998 }), null, "nearcap: 밴드 안 (탈락 아님) → 무메모");
  eq(tempNearCapRejectNote({ ...base, strategy: undefined }), null, "nearcap: 메인 후보 → 무메모");
  eq(tempNearCapRejectNote({ ...base, bestAsk: NaN }), null, "nearcap: NaN → 무메모");

  // 구조 가드 — 검증기가 메모를 붙이고 호출자가 로그로 남기는 배선
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const verifySrc = src.slice(src.indexOf("async function verifyCandidate"), src.indexOf("export function summarizeCycle"));
  eq(
    verifySrc.includes("tempNearCapRejectNote({"),
    true,
    "nearcap guard: verifier attaches note",
  );
  eq(verifySrc.includes("if (note) r.note = note;"), true, "nearcap guard: note only when present");
  const cycleSrc = src.slice(src.indexOf("async function runCycle"));
  eq(
    cycleSrc.includes("if (detail.note) log(detail.note);"),
    true,
    "nearcap guard: driver logs note",
  );
}

// ── 기온 전용 손절 배선 (구조 가드) ──────────────────────────
// checkStopLossPosition이 태그 판정으로 폭을 고르고, 두 번의 판정 모두에
// 그 폭을 넘기는지 소스로 확인한다 (판정 자체의 경계는 book 테스트가 본다).
{
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const slSrc = src.slice(
    src.indexOf("async function checkStopLossPosition"),
    src.indexOf("async function runStopLoss"),
  );
  eq(
    slSrc.includes('boughtStrategyOf(slug) === "temp_no"') && slSrc.includes("? cfg.tempStopDrop"),
    true,
    "temp stoploss guard: tagged temp picks cfg.tempStopDrop",
  );
  eq(
    slSrc.includes("stopLossVerdict(entry, first.prices[idx], stopDrop, cfg.decidedHigh, cfg.decidedLow)"),
    true,
    "temp stoploss guard: first verdict uses picked drop",
  );
  eq(
    slSrc.includes("stopLossVerdict(entry, current, stopDrop, cfg.decidedHigh, cfg.decidedLow)"),
    true,
    "temp stoploss guard: recheck verdict uses picked drop",
  );
}

// ── v0.3.38 감사 수리 가드 ────────────────────────────────────
// 설정 숫자 필드의 타입 엄격화 — null·불리언은 강제변환 대신 기본값으로
eq(
  parseTempSettings('{"tempNoReserveSlots":null}').reserveSlots,
  1,
  "audit1: reserve null falls back",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":true}').reserveSlots,
  1,
  "audit1: reserve true falls back",
);
eq(
  parseTempSettings('{"tempNoBandLo":true}').bandLo,
  0.8,
  "audit1: bandLo true falls back",
);
eq(
  parseTempSettings('{"tempEntryHourUtc":null}').entryHourUtc,
  6,
  "audit1: entry hour null falls back",
);
eq(
  parseTempSettings('{"tempNoReserveSlots":""}').reserveSlots,
  1,
  "audit1: reserve empty string falls back",
);
// 예약 슬롯 교차 클램프 — 실효 예약은 maxPositions를 넘지 않는다
eq(effectiveTempReserveSlots(70, 2), 2, "audit6: reserve clamped to maxPositions");
eq(effectiveTempReserveSlots(1, 70), 1, "audit6: small reserve kept");
eq(effectiveTempReserveSlots(0, 70), 0, "audit6: zero reserve kept");
// strategyOf 경화 — 중복 항목 뒤에 숨은 temp_no 태그도 찾는다
{
  lsStore.set("bm_bought", JSON.stringify(["dup-mkt", { slug: "dup-mkt", strategy: "temp_no" }]));
  eq(boughtStrategyOf("dup-mkt"), "temp_no", "audit9: tag behind plain entry found");
  lsStore.set("bm_bought", JSON.stringify([{ slug: "dup-mkt", strategy: "temp_no" }, "dup-mkt"]));
  eq(boughtStrategyOf("dup-mkt"), "temp_no", "audit9: tag ahead of plain entry found");
  lsStore.set("bm_bought", JSON.stringify(["dup-mkt", { slug: "dup-mkt", strategy: "other" }]));
  eq(boughtStrategyOf("dup-mkt"), null, "audit9: duplicates without tag stay main");
  lsStore.clear();
}

// ── v0.3.39 감사 A — 매수 지정가 틱 유효성 ───────────────────
// bandHi 0.998은 틱 0.01·0.005의 배수가 아니라, 종전 식은 0.998을
// 그대로 내 priceValid 상한(1 - tick)을 넘어 서명이 전건 실패했다.
{
  const priceValid = (p, tick) => p >= tick && p <= 1 - tick;
  const isTickMultiple = (p, tick) => Math.abs(p / tick - Math.round(p / tick)) < 1e-9;
  const lp1 = buyLimitPrice(0.99, 0.015, 0.01, 0.998);
  eq(lp1, 0.99, "auditA: tick 0.01 bandHi 0.998 ask 0.99 → 0.99");
  eq(isTickMultiple(lp1, 0.01), true, "auditA: tick 0.01 result is tick multiple");
  eq(priceValid(lp1, 0.01), true, "auditA: tick 0.01 result passes priceValid");
  const lp2 = buyLimitPrice(0.99, 0.015, 0.005, 0.998);
  eq(lp2, 0.995, "auditA: tick 0.005 bandHi 0.998 ask 0.99 → 0.995");
  eq(isTickMultiple(lp2, 0.005), true, "auditA: tick 0.005 result is tick multiple");
  eq(priceValid(lp2, 0.005), true, "auditA: tick 0.005 result passes priceValid");
  eq(buyLimitPrice(0.97, 0.015, 0.001, 0.998), 0.985, "auditA: fine tick unchanged");
  eq(buyLimitPrice(0.75, 0.015, 0.01, 0.9), 0.77, "auditA: main band unchanged");
  eq(buyLimitPrice(0.895, 0.015, 0.01, 0.9), 0.9, "auditA: capped at tick-multiple bandHi");
}

// ── v0.3.39 감사 E — 기온 조회 전수 실패 시 예약 fail-open 차단 ──
// fetchTempEvents·runCycle은 비공개라 배선을 소스 구조로 확인한다
// (네이티브 쪽은 NativeCycleTest가 동작으로 검증한다).
{
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const fetchSrc = src.slice(src.indexOf("async function fetchTempEvents"), src.indexOf("function roundTick"));
  eq(
    fetchSrc.includes("if (failures >= slugs.length)"),
    true,
    "auditE: fetchTempEvents distinguishes total failure",
  );
  eq(
    fetchSrc.includes("tempEventSlugsForFetch(nowMs)"),
    true,
    "hold guard: fetch range covers today and tomorrow slugs",
  );
  eq(
    fetchSrc.includes("return null;"),
    true,
    "auditE: total failure returns null, not empty list",
  );
  const cycleSrc = src.slice(src.indexOf("async function runCycle"));
  eq(
    cycleSrc.includes("tempFetchFailed = true;"),
    true,
    "auditE: driver flags total failure",
  );
  eq(
    cycleSrc.includes("기온 이벤트 조회 전수 실패 — 이번 사이클은 기온 예약을 유지합니다"),
    true,
    "auditE: total failure is logged",
  );
  eq(
    cycleSrc.includes("&& (tempFetchFailed"),
    true,
    "auditE: total failure keeps the reserve",
  );
}

// ── Q6 — 기온 조회 캐시 자정 경계 ────────────────────────────
// 캐시는 기준 UTC 날짜가 같은 동안만 유효하다. 날짜가 바뀌면 15분이
// 안 지났어도 전날 캐시가 새 날짜 집합(오늘+내일)을 덮지 않는다.
{
  eq(tempFetchDayKey(Date.UTC(2026, 10, 15, 23, 55)), "2026-11-15", "daykey: before midnight");
  eq(tempFetchDayKey(Date.UTC(2026, 10, 16, 0, 5)), "2026-11-16", "daykey: after midnight");
  eq(tempFetchDayKey(Date.UTC(2026, 10, 16, 0, 0)), "2026-11-16", "daykey: midnight belongs to new day");
  eq(tempFetchDayKey(Date.UTC(2026, 10, 15, 12, 0)), "2026-11-15", "daykey: midday");
  // fetchTempEvents는 비공개라 배선을 소스 구조로 확인한다
  // (네이티브 쪽은 NativeCycleTest가 동작으로 검증한다).
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const fetchSrc = src.slice(src.indexOf("async function fetchTempEvents"), src.indexOf("function roundTick"));
  eq(
    fetchSrc.includes("const dayKey = tempFetchDayKey(nowMs);"),
    true,
    "midnight guard: fetch computes the day key",
  );
  eq(
    fetchSrc.includes("tempEventsCache.dayKey === dayKey"),
    true,
    "midnight guard: cache hit requires same day key",
  );
  eq(
    fetchSrc.includes("tempEventsCache = { atMs: nowMs, dayKey, events };"),
    true,
    "midnight guard: cache store records the day key",
  );
}

console.log(`temp tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
