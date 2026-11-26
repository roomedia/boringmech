// 자금 배분 계층 테스트 — 공리적 분리 2단계.
// 1) 픽스처 대조: 리팩터 전(v0.3.57) 순수 판정 + 드라이버 식으로 캡처한
//    fixtures/allocation-parity.json의 기대값과 새 배분 계층 출력을
//    전수 대조한다 (Java CycleAllocationTest도 같은 픽스처를 쓴다).
// 2) 라이브 교차 대조: 배분 판정과 기존 순수 판정을 결정적 격자로
//    직접 맞붙여 어긋남 0을 확인한다.
// 실행: node test/allocation.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const {
  computeCycleAllocation,
  allocationLaneEntryPossible,
  allocationBlocksMain,
  allocationLaneAllows,
  allocationLaneCashBlocked,
  allocationCashGuardBlocks,
  effectiveTempReserveSlots,
  tempReserveBlocksMain,
  tempReserveLaneAllows,
  tempLaneCashBlocked,
  tempCashGuardBlocks,
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
    if (failed <= 20) console.error(`FAIL ${label}: expected ${e}, got ${a}`);
  }
}

const fixture = JSON.parse(
  readFileSync(new URL("./fixtures/allocation-parity.json", import.meta.url), "utf8"),
);

function allocOf(inp) {
  return computeCycleAllocation({
    slots: inp.slots, bet: inp.bet, cashFloor: inp.cashFloor,
    cash: inp.cash, openCount: inp.openCount,
    maxPositions: inp.maxPositions, verifyCap: inp.verifyCap,
    reserveUsd: inp.reserveUsd,
    tempEnabled: inp.tempEnabled, tempReserveSlots: inp.tempReserveSlots,
    tempBetUsd: inp.tempBetUsd,
    tempHoldSignal: inp.holdFailed || inp.holdIncomplete || inp.holdUpcoming,
  });
}

for (const sc of fixture.scenarios) {
  const inp = sc.input;
  const alloc = allocOf(inp);
  eq(alloc.reserveSlotsEff, sc.expect.reserveSlotsEff, `${sc.name}: reserveSlotsEff`);
  eq(alloc.tempHold, sc.expect.tempHold, `${sc.name}: tempHold`);
  eq(alloc.laneSlots, sc.expect.laneSlots, `${sc.name}: laneSlots`);
  eq(alloc.cashGuardFloor, sc.expect.cashGuardFloor, `${sc.name}: cashGuardFloor`);
  eq(alloc.mainSlots, inp.slots, `${sc.name}: mainSlots passthrough`);
  eq(alloc.mainBet, inp.bet, `${sc.name}: mainBet passthrough`);
  eq(
    allocationLaneEntryPossible({
      enabled: inp.tempEnabled, reserveSlots: inp.tempReserveSlots,
      maxPositions: inp.maxPositions, openCount: inp.openCount,
      tempBetUsd: inp.tempBetUsd, cash: inp.cash, reserveUsd: inp.reserveUsd,
    }),
    sc.expect.laneEntryPossible,
    `${sc.name}: laneEntryPossible`,
  );
  for (const st of sc.blockSteps) {
    eq(
      allocationBlocksMain(alloc, st.strategy ?? undefined, st.slotsLeft, st.verified),
      st.expect,
      `${sc.name}: blocksMain ${JSON.stringify(st)}`,
    );
  }
  for (const st of sc.laneSteps) {
    eq(
      allocationLaneAllows(alloc, st.strategy ?? undefined, st.laneLeft, st.positionsUsed, st.cashLeft),
      st.expectAllows,
      `${sc.name}: laneAllows ${JSON.stringify(st)}`,
    );
    eq(
      allocationLaneCashBlocked(alloc, st.strategy ?? undefined, st.laneLeft, st.positionsUsed, st.cashLeft),
      st.expectCashBlocked,
      `${sc.name}: laneCashBlocked ${JSON.stringify(st)}`,
    );
  }
  for (const st of sc.guardSteps) {
    eq(
      allocationCashGuardBlocks(alloc, st.strategy ?? undefined, st.cashLeft, st.bet),
      st.expect,
      `${sc.name}: cashGuardBlocks ${JSON.stringify(st)}`,
    );
  }
}

// ── 라이브 교차 대조 — 배분 판정 vs 기존 순수 판정 ─────────────
// 픽스처에 없는 입력 조합(비유한 값 포함)에서도 재배치가 원형과
// 어긋나지 않는지 결정적 격자로 확인한다.
{
  const cashProbes = [0, 3.57, 5.49, 5.5, 8.67, 8.87, 13.86, 13.87, 20, 100, NaN, Infinity];
  let cross = 0;
  for (const tempEnabled of [true, false]) {
    for (const reserveSlots of [0, 1, 2, 3]) {
      for (const tempBetUsd of [0, 3, 5, 6, NaN]) {
        for (const signal of [true, false]) {
          const inp = {
            slots: 3, bet: 5, cashFloor: 2.87, cash: 50, openCount: 4,
            maxPositions: 70, verifyCap: 60, reserveUsd: 2.87,
            tempEnabled, tempReserveSlots: reserveSlots, tempBetUsd,
            holdFailed: signal, holdIncomplete: false, holdUpcoming: false,
          };
          const alloc = allocOf(inp);
          const eff = effectiveTempReserveSlots(reserveSlots, 70);
          const hold = tempEnabled && eff > 0 && signal;
          for (const strategy of [undefined, "temp_no", "other"]) {
            for (const slotsLeft of [0, 1, 2, 4]) {
              for (const verified of [0, 58, 59, 60]) {
                eq(
                  allocationBlocksMain(alloc, strategy, slotsLeft, verified),
                  hold && tempReserveBlocksMain(strategy, slotsLeft, verified, eff, 60),
                  "cross blocksMain",
                );
                cross += 1;
              }
            }
            for (const laneLeft of [0, 1, 2]) {
              for (const positionsUsed of [0, 69, 70]) {
                for (const cashLeft of cashProbes) {
                  eq(
                    allocationLaneAllows(alloc, strategy, laneLeft, positionsUsed, cashLeft),
                    tempReserveLaneAllows({
                      strategy, tempHold: hold, laneLeft, positionsUsed,
                      maxPositions: 70, cashLeft, bet: tempBetUsd, reserveUsd: 2.87,
                    }),
                    "cross laneAllows",
                  );
                  eq(
                    allocationLaneCashBlocked(alloc, strategy, laneLeft, positionsUsed, cashLeft),
                    tempLaneCashBlocked({
                      strategy, tempHold: hold, laneLeft, positionsUsed,
                      maxPositions: 70, cashLeft, bet: tempBetUsd, reserveUsd: 2.87,
                    }),
                    "cross laneCashBlocked",
                  );
                  eq(
                    allocationCashGuardBlocks(alloc, strategy, cashLeft, 5),
                    tempCashGuardBlocks({
                      strategy, enabled: tempEnabled, reserveSlots: eff,
                      tempBetUsd, reserveUsd: 2.87, cashLeft, bet: 5,
                    }),
                    "cross cashGuardBlocks",
                  );
                  cross += 3;
                }
              }
            }
          }
        }
      }
    }
  }
  console.log(`allocation cross-checks: ${cross}`);
}

// ── 구조 가드 — 드라이버가 배분 결과만 소비하는지 ─────────────
{
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const cycleSrc = src.slice(src.indexOf("async function runCycle"));
  eq(cycleSrc.includes("alloc = computeCycleAllocation({"), true,
    "alloc guard: driver computes allocation once");
  eq(cycleSrc.includes("allocationBlocksMain(alloc, cand.strategy, slotsLeft, verified)"), true,
    "alloc guard: driver blocks main via allocation");
  eq(cycleSrc.includes("allocationLaneAllows(alloc, cand.strategy, laneLeft, positionsUsed, cashLeft)"), true,
    "alloc guard: driver attempts lane via allocation");
  eq(cycleSrc.includes("allocationCashGuardBlocks(alloc, cand.strategy, cashLeft, bet)"), true,
    "alloc guard: attempt applies cash guard via allocation");
  eq(cycleSrc.includes("laneLeft = alloc.laneSlots;"), true,
    "alloc guard: lane budget comes from allocation");
  // 전략 간 내부 상태 직접 참조가 드라이버에 남아 있지 않은지.
  for (const direct of [
    "tempReserveBlocksMain(", "tempReserveLaneAllows(", "tempLaneCashBlocked(",
    "tempCashGuardBlocks(", "tempCashGuardFloor(", "effectiveTempReserveSlots(",
  ]) {
    eq(cycleSrc.includes(direct), false, `alloc guard: no direct ${direct} in driver`);
  }
}

console.log(`allocation: ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
