// 제보 설정 요약 가드 — 제보 payload에 실효 설정이 실리지 않으면 기온
// 토글·밴드·건당 금액 같은 현재값을 원격으로 대조할 방법이 없다
// (2026-11-24 id 40·41 대조 불가 사고). effectiveSettingsSummary가
// 엔진 해석(parseTradeSettings·parseTempSettings)과 같은 실효값을
// 저장 키 이름으로 내는지, 깨진 입력에서도 던지지 않고 기본값으로
// 떨어지는지, sendReport 배선이 try/catch로 격리돼 제보 본체가
// 설정 때문에 막히지 않는지를 고정한다.
globalThis.window = {};
const { effectiveSettingsSummary, DEFAULT_TRADE_SETTINGS } = await import("../src/engine.js");
const { readFileSync } = await import("node:fs");

let passed = 0;
let failed = 0;
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
  } else {
    failed++;
    console.error(`FAIL ${label}: expected ${e}, got ${a}`);
  }
}
function ok(cond, label) {
  eq(Boolean(cond), true, label);
}

// ── 실효값 반영 — 저장값이 해석을 거쳐 그대로 실린다 ──────────
{
  const s = effectiveSettingsSummary({
    tempNoEnabled: true,
    tempNoBandLo: 0.9,
    tempNoBandHi: 0.995,
    tempEntryHourUtc: 7,
    tempStopDrop: 0.2,
    tempNoReserveSlots: 2,
    tempBetUsd: 4,
    betUsd: 3,
    bandLo: 0.65,
    bandHi: 0.88,
    stopDrop: 0.3,
  });
  eq(s.tempNoEnabled, true, "report: temp toggle on");
  eq(s.tempNoBandLo, 0.9, "report: temp band lo");
  eq(s.tempNoBandHi, 0.995, "report: temp band hi");
  eq(s.tempEntryHourUtc, 7, "report: temp entry hour");
  eq(s.tempStopDrop, 0.2, "report: temp stop drop");
  eq(s.tempNoReserveSlots, 2, "report: temp reserve slots");
  eq(s.betUsd, 3, "report: bet usd");
  eq(s.tempBetUsd, 4, "report: temp bet usd");
  eq(s.bandLo, 0.65, "report: band lo");
  eq(s.bandHi, 0.88, "report: band hi");
  eq(s.stopDrop, 0.3, "report: stop drop");
}

// ── 필수 필드 전수 — 원격 대조에 필요한 키가 빠지지 않는다 ────
{
  const s = effectiveSettingsSummary({});
  for (const k of ["tempNoEnabled", "tempNoBandLo", "tempNoBandHi",
    "betUsd", "tempBetUsd", "tempStopDrop", "tempNoReserveSlots", "tempEntryHourUtc"]) {
    ok(Object.prototype.hasOwnProperty.call(s, k), `report: has key ${k}`);
  }
}

// ── 기본값 폴백 — 설정이 없으면 엔진 기본값과 같다 ────────────
{
  const s = effectiveSettingsSummary(null);
  eq(s.tempNoEnabled, false, "report: default toggle off");
  eq(s.tempNoBandLo, 0.8, "report: default temp band lo");
  eq(s.tempNoBandHi, 0.998, "report: default temp band hi");
  eq(s.tempEntryHourUtc, 6, "report: default entry hour");
  eq(s.tempNoReserveSlots, 1, "report: default reserve slots");
  eq(s.betUsd, DEFAULT_TRADE_SETTINGS.betUsd, "report: default bet usd");
  eq(s.tempBetUsd, 5, "report: default temp bet usd");
  eq(s.tempStopDrop, DEFAULT_TRADE_SETTINGS.tempStopDrop, "report: default temp stop");
  eq(s.bandLo, DEFAULT_TRADE_SETTINGS.bandLo, "report: default band lo");
}

// ── 깨진 입력 — 던지지 않고 기본값으로 떨어진다 ───────────────
{
  for (const raw of ["garbage", 42, [1, 2], { betUsd: -5, tempNoBandLo: 7, tempNoEnabled: "yes" }]) {
    let s = null;
    let threw = false;
    try { s = effectiveSettingsSummary(raw); } catch (e) { threw = true; }
    ok(!threw && s && typeof s === "object", `report: no throw for ${JSON.stringify(raw)}`);
    if (s) {
      eq(s.betUsd, DEFAULT_TRADE_SETTINGS.betUsd, "report: bad betUsd falls back");
      eq(s.tempNoEnabled, false, "report: non-true toggle is off");
    }
  }
}

// ── sendReport 배선 (index.html 정적 대조) ────────────────────
{
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  const start = html.indexOf("async function sendReport");
  const end = html.indexOf("payload.logs = readLogs();", start);
  ok(start >= 0 && end > start, "report: sendReport body located");
  const body = html.slice(start, end);
  ok(body.includes("payload.settings"), "report: payload.settings assigned");
  ok(body.includes("BM.effectiveSettingsSummary"), "report: uses engine summary");
  ok(body.includes("readSettingsObject()"), "report: reads bm_settings source");
  // settings 첨부는 try/catch 안에 있어야 한다 — 파싱 실패가 제보를 막지 않는다.
  const setIdx = body.indexOf("payload.settings");
  const tryIdx = body.lastIndexOf("try {", setIdx);
  const catchIdx = body.indexOf("catch", setIdx);
  ok(tryIdx >= 0 && catchIdx > setIdx, "report: settings attach wrapped in try/catch");
  // 제보 본체(엔드포인트·재시도)는 무변경이어야 한다.
  ok(html.includes("https://phoneauto-relay.k45414254.workers.dev/report"),
    "report: endpoint unchanged");
  ok(html.includes("attempt < 2"), "report: retry loop unchanged");
}

console.log(`report tests: ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
