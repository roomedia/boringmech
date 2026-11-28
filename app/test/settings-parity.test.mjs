// 설정 파서 단일화 패리티 테스트 — 픽스처 전건에 대해 정본 파서
// (parseSettings)와 투영 뷰(parseTradeSettings·parseTempSettings)의
// 결과가 리팩터 전 구 파서 출력(기준선)과 글자까지 같은지 대조한다.
// 기준선은 구 JS 파서로 생성했고, 구 Java 파서와도 514/514 일치를
// 먼저 확인했다 (STATUS-decoupling-1-settings.md).
// 실행: node test/settings-parity.test.mjs
globalThis.window = {};
const {
  parseSettings,
  parseTradeSettings,
  parseTempSettings,
  tradeSettingsView,
  tempSettingsView,
  endWindowMigrationApplies,
  migrateEndWindowSettings,
} = await import("../src/engine.js");
import { readFileSync } from "node:fs";

const fixtures = JSON.parse(
  readFileSync(new URL("./fixtures/settings-parity.json", import.meta.url), "utf8"),
);

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

for (const f of fixtures) {
  eq(parseTradeSettings(f.raw), f.trade, `parity trade: ${f.name}`);
  eq(parseTempSettings(f.raw), f.temp, `parity temp: ${f.name}`);
  const s = parseSettings(f.raw);
  eq(tradeSettingsView(s), f.trade, `parity trade view: ${f.name}`);
  eq(tempSettingsView(s), f.temp, `parity temp view: ${f.name}`);
  eq(s.temp, f.temp, `parity temp namespace: ${f.name}`);
}

// 네임스페이스 분리 — scan/sizing/stoploss 키가 서로 겹치지 않고
// 합치면 매매 평면 형태와 같으며, 정본 결과는 동결이다.
{
  const s = parseSettings(null);
  const keys = [
    ...Object.keys(s.scan),
    ...Object.keys(s.sizing),
    ...Object.keys(s.stoploss),
  ];
  check(new Set(keys).size === keys.length, "namespaces: scan/sizing/stoploss keys disjoint");
  check(Object.isFrozen(s) && Object.isFrozen(s.scan) && Object.isFrozen(s.sizing)
    && Object.isFrozen(s.stoploss) && Object.isFrozen(s.temp),
  "namespaces: canonical result frozen");
  eq(Object.keys(s.scan).sort(), [
    "askAboveSignal", "askDepthMinUsd", "bandHi", "bandLo", "endWindowDays",
    "priorityBandHi", "priorityBandLo", "slippageCap", "snapshotMatchTol",
    "spreadMax", "volumeMin",
  ], "namespaces: scan keys");
  eq(Object.keys(s.sizing).sort(), [
    "betUsd", "cashFloorPct", "maxPositions", "reserveUsd", "verifyCap",
  ], "namespaces: sizing keys");
  eq(Object.keys(s.stoploss).sort(), [
    "decidedHigh", "decidedLow", "stopDrop", "tempStopDrop",
  ], "namespaces: stoploss keys");
  eq(Object.keys(s.temp).sort(), [
    "bandHi", "bandLo", "betUsd", "enabled", "entryHourUtc", "reserveSlots",
  ], "namespaces: temp keys");
}

// 만기 창 기본값 이관 헬퍼 (v0.3.64) — 옛 기본값 45만 이관되고, 표식이
// 있거나 사용자가 바꾼 값은 이관하지 않는다. 이관본은 다른 키를 보존하고
// 멱등하다.
{
  check(endWindowMigrationApplies({ endWindowDays: 45 }), "migration: legacy 45 applies");
  check(!endWindowMigrationApplies({ endWindowDays: 45, endWindowDaysMigrated: true }),
    "migration: marker blocks");
  check(!endWindowMigrationApplies({ endWindowDays: 30 }), "migration: user value untouched");
  check(!endWindowMigrationApplies(null), "migration: null blob");
  const migrated = migrateEndWindowSettings(JSON.stringify({ bandLo: 0.7, endWindowDays: 45 }));
  const mo = JSON.parse(migrated);
  check(mo.endWindowDays === 14 && mo.endWindowDaysMigrated === true && mo.bandLo === 0.7,
    "migration: migrated blob shape");
  check(migrateEndWindowSettings(migrated) === null, "migration: idempotent");
  check(migrateEndWindowSettings({ endWindowDays: 30 }) === null, "migration: noop returns null");
}

console.log(`settings-parity: ${passed} passed, ${failed} failed (${fixtures.length} fixtures)`);
if (failed > 0) process.exit(1);
