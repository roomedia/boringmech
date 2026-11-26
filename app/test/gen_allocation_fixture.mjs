// 자금 배분 픽스처 생성기 — 공리적 분리 2단계의 기준선 캡처.
// 현행(리팩터 전) 순수 판정 함수와 드라이버 식을 그대로 실행해 기대값을
// fixtures/allocation-parity.json에 고정한다. 리팩터 뒤에는 이 스크립트를
// 다시 돌리지 않는다 — JSON이 계약이고, JS·Java 양쪽 테스트가 새 배분
// 계층을 이 기대값과 대조한다.
// 실행: node test/gen_allocation_fixture.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const {
  effectiveTempReserveSlots,
  tempReserveBlocksMain,
  tempReserveLaneAllows,
  tempLaneCashBlocked,
  tempCashGuardFloor,
  tempCashGuardBlocks,
} = await import("../src/engine.js");
const { writeFileSync } = await import("node:fs");

// 드라이버 식 (runCycle 현행) — 홀드 판정과 스캔 전 차선 가능성.
function driverHold(inp, eff) {
  return !!(
    inp.tempEnabled && eff > 0
    && (inp.holdFailed || inp.holdIncomplete || inp.holdUpcoming)
  );
}
function driverLaneEntryPossible(inp, eff) {
  return !!(
    inp.tempEnabled && eff > 0
    && inp.openCount < inp.maxPositions
    && inp.tempBetUsd > 0
    && inp.cash >= inp.tempBetUsd + inp.reserveUsd
  );
}

const BASE = {
  slots: 3, bet: 5, cashFloor: 2.87, cash: 100, openCount: 0,
  maxPositions: 70, verifyCap: 60, reserveUsd: 2.87,
  tempEnabled: true, tempReserveSlots: 1, tempBetUsd: 6,
  holdFailed: false, holdIncomplete: false, holdUpcoming: true,
};
const SCENARIOS = [
  ["base", {}],
  ["incident-20261126", { cash: 8.67, openCount: 8, slots: 1 }],
  ["guard-boundary", { cash: 13.87 }],
  ["hold-fetch-failed", { holdFailed: true, holdUpcoming: false }],
  ["hold-fetch-incomplete", { holdIncomplete: true, holdUpcoming: false }],
  ["hold-released", { holdUpcoming: false }],
  ["reserve-0", { tempReserveSlots: 0 }],
  ["reserve-2", { tempReserveSlots: 2 }],
  ["reserve-3-tempbet5", { tempReserveSlots: 3, tempBetUsd: 5 }],
  ["temp-off", { tempEnabled: false }],
  ["reserve-clamped", { maxPositions: 2, tempReserveSlots: 3, tempBetUsd: 5, slots: 2 }],
  ["tempbet-0", { tempBetUsd: 0 }],
  ["lane-base-3-2.5", { tempBetUsd: 3, reserveUsd: 2.5 }],
  ["positions-full", { openCount: 70, slots: 0 }],
  ["cash-below-lane", { cash: 3.57, slots: 0 }],
  ["cash-exact-lane", { cash: 8.87, slots: 0 }],
  ["verifycap-10-reserve-2", { verifyCap: 10, tempReserveSlots: 2, tempBetUsd: 5 }],
  ["reserve-2-failed", { tempReserveSlots: 2, tempBetUsd: 5, holdFailed: true, holdUpcoming: false }],
  ["reserve-2-released", { tempReserveSlots: 2, tempBetUsd: 5, holdUpcoming: false }],
  ["cashfloor-high", { cashFloor: 30, slots: 0, cash: 40 }],
  ["open-69", { openCount: 69, slots: 1 }],
  ["tempbet-5-reserve-1", { tempBetUsd: 5 }],
  ["cash-0", { cash: 0, slots: 0 }],
];

const uniq = (arr) => [...new Set(arr)];
function buildScenario([name, over]) {
  const inp = { ...BASE, ...over };
  const eff = effectiveTempReserveSlots(inp.tempReserveSlots, inp.maxPositions);
  const hold = driverHold(inp, eff);
  const floor = tempCashGuardFloor({
    enabled: inp.tempEnabled, reserveSlots: eff,
    tempBetUsd: inp.tempBetUsd, reserveUsd: inp.reserveUsd,
  });
  const expect = {
    reserveSlotsEff: eff,
    tempHold: hold,
    laneSlots: hold ? eff : 0,
    cashGuardFloor: floor,
    laneEntryPossible: driverLaneEntryPossible(inp, eff),
  };
  // 차단 판정 probes — (전략, 잔여 슬롯, 검증 수) 경계 전수.
  const blockSteps = [];
  for (const strategy of [null, "temp_no"]) {
    for (const slotsLeft of uniq([0, eff, eff + 1, 3])) {
      for (const verified of uniq([0, inp.verifyCap - eff - 1, inp.verifyCap - eff, inp.verifyCap - 1, inp.verifyCap])) {
        blockSteps.push({
          strategy, slotsLeft, verified,
          expect: hold && tempReserveBlocksMain(strategy ?? undefined, slotsLeft, verified, eff, inp.verifyCap),
        });
      }
    }
  }
  // 차선 판정 probes — (전략, 차선 잔여, 포지션 사용, 현금) 경계 전수.
  const laneCashProbes = uniq([
    0,
    inp.tempBetUsd + inp.reserveUsd - 0.01,
    inp.tempBetUsd + inp.reserveUsd,
    inp.cash,
    100,
  ]).filter((v) => Number.isFinite(v) && v >= 0);
  const laneSteps = [];
  for (const strategy of [null, "temp_no"]) {
    for (const laneLeft of uniq([0, 1, eff])) {
      for (const positionsUsed of uniq([inp.openCount, inp.maxPositions - 1, inp.maxPositions])) {
        for (const cashLeft of laneCashProbes) {
          const args = {
            strategy: strategy ?? undefined, tempHold: hold, laneLeft, positionsUsed,
            maxPositions: inp.maxPositions, cashLeft, bet: inp.tempBetUsd, reserveUsd: inp.reserveUsd,
          };
          laneSteps.push({
            strategy, laneLeft, positionsUsed, cashLeft,
            expectAllows: tempReserveLaneAllows(args),
            expectCashBlocked: tempLaneCashBlocked(args),
          });
        }
      }
    }
  }
  // 현금 가드 probes — (전략, 현금) 경계 전수. bet은 후보 금액(메인 5,
  // 기온은 기온 금액이지만 전략 판정으로 항상 false).
  const guardCashProbes = uniq([
    0, 8.67, inp.cash, 100,
    ...(floor === null ? [] : [floor + inp.bet - 0.01, floor + inp.bet, floor + inp.bet + 0.01]),
  ]).filter((v) => Number.isFinite(v) && v >= 0);
  const guardSteps = [];
  for (const strategy of [null, "temp_no"]) {
    const bet = strategy === "temp_no" ? inp.tempBetUsd : inp.bet;
    for (const cashLeft of guardCashProbes) {
      guardSteps.push({
        strategy, cashLeft, bet,
        expect: tempCashGuardBlocks({
          strategy: strategy ?? undefined, enabled: inp.tempEnabled, reserveSlots: eff,
          tempBetUsd: inp.tempBetUsd, reserveUsd: inp.reserveUsd, cashLeft, bet,
        }),
      });
    }
  }
  return { name, input: inp, expect, blockSteps, laneSteps, guardSteps };
}

const scenarios = SCENARIOS.map(buildScenario);
const total = scenarios.reduce(
  (n, s) => n + 1 + s.blockSteps.length + s.laneSteps.length + s.guardSteps.length, 0,
);
const out = {
  version: 1,
  note: "Captured from pre-refactor v0.3.57 pure predicates + driver formulas (공리적 분리 2단계 기준선). Do not regenerate after the refactor.",
  totalChecks: total,
  scenarios,
};
writeFileSync(
  new URL("./fixtures/allocation-parity.json", import.meta.url),
  JSON.stringify(out, null, 1) + "\n",
);
console.log(`allocation fixture: ${scenarios.length} scenarios, ${total} checks`);
