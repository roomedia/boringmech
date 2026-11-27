// runCycle 단계 실패 격리 테스트 (2026-11-27 동호 결정 — 거동 변경).
// 오케스트레이션(runCycleStages)에 가짜 단계를 주입해 9단계 전부의
// 격리 의미론을 확인하고, 실단계 배선은 소스 구조 가드로 고정한다.
// 실행: node test/cycle-isolation.test.mjs
globalThis.window = {};
const lsStore = new Map();
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const { readFileSync } = await import("node:fs");
const { runCycleStages, recordStageFailure, summarizeCycle } = await import("../src/engine.js");

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

const ALL = ["snapshot", "stoploss", "redeem", "settle", "prescan", "scan", "selection", "shadow", "summary"];

// 가짜 단계 — 호출 순서를 기록하고, failAt에 지정된 단계는 예외를 던진다.
// prescan은 proceed, selection은 aborted를 돌려준다.
function makeStages(calls, { failAt = {}, prescan = true, aborted = false } = {}) {
  const mk = (name, ret) => async () => {
    calls.push(name);
    if (failAt[name]) throw new Error(failAt[name]);
    return ret;
  };
  return {
    snapshot: mk("snapshot"),
    stoploss: mk("stoploss"),
    redeem: mk("redeem"),
    settle: mk("settle"),
    prescan: mk("prescan", prescan),
    scan: mk("scan"),
    selection: mk("selection", aborted),
    shadow: async (ab) => {
      calls.push("shadow");
      if (failAt.shadow) throw new Error(failAt.shadow);
      calls.shadowAborted = ab;
    },
    summary: mk("summary"),
  };
}

async function drive(opts) {
  const calls = [];
  const ctx = { stageFailures: [] };
  const out = await runCycleStages(ctx, makeStages(calls, opts));
  return { calls, ctx, out };
}

// 1. 전부 성공 — 9단계가 순서대로 전부 돈다.
{
  const { calls, ctx, out } = await drive({});
  eq(calls, ALL, "iso: all stages run in order");
  eq(ctx.stageFailures, [], "iso: no failures on success");
  eq(out === ctx, true, "iso: returns ctx");
  eq(calls.shadowAborted, false, "iso: shadow gets aborted=false");
}

// 2. 스냅샷 실패 — 그 사이클은 여기서 끝난다 (뒤 단계 입력 없음).
{
  const { calls, ctx } = await drive({ failAt: { snapshot: "snap boom" } });
  eq(calls, ["snapshot"], "iso: snapshot failure stops the cycle");
  eq(ctx.stageFailures, [{ stage: "snapshot", message: "snap boom" }],
    "iso: snapshot failure recorded");
}

// 3. 손절 실패 — 나머지 단계가 전부 계속 돈다 (동호 결정의 핵심 케이스).
{
  const { calls, ctx } = await drive({ failAt: { stoploss: "sl boom" } });
  eq(calls, ALL, "iso: stoploss failure — scan/selection still run");
  eq(ctx.stageFailures, [{ stage: "stoploss", message: "sl boom" }],
    "iso: stoploss failure recorded");
}

// 4. 리딤 실패 — 계속 돈다.
{
  const { calls, ctx } = await drive({ failAt: { redeem: "redeem boom" } });
  eq(calls, ALL, "iso: redeem failure — later stages run");
  eq(ctx.stageFailures.map((f) => f.stage), ["redeem"], "iso: redeem failure recorded");
}

// 5. 정산 단계 실패 — 계속 돈다.
{
  const { calls, ctx } = await drive({ failAt: { settle: "settle boom" } });
  eq(calls, ALL, "iso: settle failure — later stages run");
  eq(ctx.stageFailures.map((f) => f.stage), ["settle"], "iso: settle failure recorded");
}

// 6. prescan 실패 — 매수 구간을 건너뛴다 (안전 방향).
{
  const { calls, ctx } = await drive({ failAt: { prescan: "pre boom" } });
  eq(calls, ["snapshot", "stoploss", "redeem", "settle", "prescan"],
    "iso: prescan failure skips buying stages");
  eq(ctx.stageFailures.map((f) => f.stage), ["prescan"], "iso: prescan failure recorded");
}

// 7. prescan 정상 false — 실패 기록 없이 건너뜀 (성공 경로 패리티).
{
  const { calls, ctx } = await drive({ prescan: false });
  eq(calls, ["snapshot", "stoploss", "redeem", "settle", "prescan"],
    "iso: prescan false skips buying stages");
  eq(ctx.stageFailures, [], "iso: prescan false is not a failure");
}

// 8. scan 실패 — selection·shadow·summary는 입력이 없어 실행하지 않는다.
{
  const { calls, ctx } = await drive({ failAt: { scan: "markets boom" } });
  eq(calls, ["snapshot", "stoploss", "redeem", "settle", "prescan", "scan"],
    "iso: scan failure skips selection/shadow/summary");
  eq(ctx.stageFailures, [{ stage: "scan", message: "markets boom" }],
    "iso: scan failure recorded");
}

// 9. selection 실패 — 종전 중단 거동과 같이 shadow·summary를 생략한다.
{
  const { calls, ctx } = await drive({ failAt: { selection: "sel boom" } });
  eq(calls, ["snapshot", "stoploss", "redeem", "settle", "prescan", "scan", "selection"],
    "iso: selection failure skips shadow/summary");
  eq(ctx.stageFailures.map((f) => f.stage), ["selection"], "iso: selection failure recorded");
}

// 10. selection 중단(aborted) — shadow는 aborted로 불리고 summary는 생략.
{
  const { calls, ctx } = await drive({ aborted: true });
  eq(calls, ["snapshot", "stoploss", "redeem", "settle", "prescan", "scan", "selection", "shadow"],
    "iso: aborted selection — shadow runs, summary skipped");
  eq(calls.shadowAborted, true, "iso: shadow gets aborted=true");
  eq(ctx.stageFailures, [], "iso: aborted is not a failure");
}

// 11. 섀도우 실패 — 기록되고 summary는 계속 돈다 (종전 무음 삼킴에서 변경).
{
  const { calls, ctx } = await drive({ failAt: { shadow: "shadow boom" } });
  eq(calls, ALL, "iso: shadow failure — summary still runs");
  eq(ctx.stageFailures, [{ stage: "shadow", message: "shadow boom" }],
    "iso: shadow failure recorded, not swallowed");
}

// 12. summary 실패 — 기록되고 ctx가 돌아온다 (루프는 다음 사이클로 산다).
{
  const { calls, ctx, out } = await drive({ failAt: { summary: "sum boom" } });
  eq(calls, ALL, "iso: summary failure — all stages attempted");
  eq(ctx.stageFailures.map((f) => f.stage), ["summary"], "iso: summary failure recorded");
  eq(out === ctx, true, "iso: summary failure still returns ctx");
}

// 13. 다중 실패 — 순서대로 누적된다.
{
  const { calls, ctx } = await drive({
    failAt: { stoploss: "a", redeem: "b", shadow: "c" },
  });
  eq(calls, ALL, "iso: multiple failures — cycle completes");
  eq(ctx.stageFailures.map((f) => f.stage), ["stoploss", "redeem", "shadow"],
    "iso: failures accumulate in stage order");
}

// 14. recordStageFailure 형태 — Error·문자열·절단.
{
  const list = [];
  const e1 = recordStageFailure(list, "scan", new Error("msg one"));
  eq(e1, { stage: "scan", message: "msg one" }, "record: Error message");
  recordStageFailure(list, "redeem", "plain string");
  eq(list[1], { stage: "redeem", message: "plain string" }, "record: string error");
  const e3 = recordStageFailure(list, "snapshot", new Error("x".repeat(200)));
  eq(e3.message.length, 120, "record: message sliced to 120");
  eq(list.length, 3, "record: appends");
}

// 15. 요약 패리티 — 실패가 없으면 종전 문자열과 같고, 있으면 구간이 붙는다.
{
  const base = { candidates: 0, verified: 0, rejects: {}, filled: 0, unfilled: 0, failed: 0 };
  eq(summarizeCycle(base), "사이클 요약 · 후보 0 · 검증 0 · 제출 0",
    "summary: unchanged without failures");
  eq(summarizeCycle({ ...base, stageFailures: [] }), "사이클 요약 · 후보 0 · 검증 0 · 제출 0",
    "summary: unchanged with empty failures");
  eq(summarizeCycle({ ...base, stageFailures: [{ stage: "stoploss", message: "x" }] }),
    "사이클 요약 · 후보 0 · 검증 0 · 제출 0 · 단계 실패 stoploss",
    "summary: failure segment appended");
  eq(summarizeCycle({
    ...base,
    stageFailures: [{ stage: "stoploss", message: "x" }, { stage: "shadow", message: "y" }],
    durations: "소요 스냅샷 1ms",
  }), "사이클 요약 · 후보 0 · 검증 0 · 제출 0 · 단계 실패 stoploss·shadow · 소요 스냅샷 1ms",
    "summary: failure segment before durations");
}

// 16. 구조 가드 — 실단계 배선과 루프 연결.
{
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  eq(src.includes("return runCycleStages(ctx, {"), true, "guard: runCycle delegates to orchestration");
  for (const wire of [
    "snapshot: () => cycleSnapshotStage(ctx)",
    "stoploss: () => cycleStopLossStage(ctx)",
    "redeem: () => cycleRedeemStage(ctx)",
    "settle: () => cycleSettleStage(ctx)",
    "prescan: () => cyclePreScanStage(ctx)",
    "scan: () => cycleScanStage(ctx)",
    "selection: () => cycleSelectionStage(ctx)",
    "shadow: (aborted) => cycleShadowStage(ctx, aborted)",
    "summary: () => cycleSummaryStage(ctx)",
  ]) {
    eq(src.includes(wire), true, `guard: wiring ${wire.slice(0, 24)}`);
  }
  eq(src.includes("ctx.stageFailures[0].message"), true,
    "guard: loop counts isolated failures as cycle errors");
  const shadowSrc = src.slice(src.indexOf("function cycleShadowStage"), src.indexOf("function cycleSummaryStage"));
  eq(shadowSrc.includes("catch"), false, "guard: shadow stage no longer swallows");
  eq(src.includes("stageFailures: ctx.stageFailures"), true,
    "guard: summary receives stage failures");
}

console.log(`cycle-isolation: ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
