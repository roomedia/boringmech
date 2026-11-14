// summarizeBook / judgeClosedRow 픽스처 테스트 — node_modules가 있는 사본에서 실행:
//   node test/book.test.mjs
// engine.js는 모듈 최상위에서 window.BM을 대입하므로 스텁을 먼저 깐다.
globalThis.window = {};
const {
  summarizeBook,
  summarizeCycle,
  judgeClosedRow,
  sizing,
  bootView,
  snapshotCacheOf,
  snapshotFromCache,
  resolveHostMode,
  shouldAutoStart,
  isHeartbeatStale,
  HEARTBEAT_STALE_MS,
  stopLossVerdict,
  pickOutcomeIndex,
  tryAcquireSubmitLock,
  releaseSubmitLock,
  isSubmitLocked,
  storeSecret,
  readSecret,
  migrateSecrets,
  planRedeems,
  verifyRecovery,
  buildCtfRedeemCall,
  buildV2RedeemCall,
  toBytes31,
  scanCandidates,
  reconcileBought,
  HEARTBEAT_INTERVAL_MS,
  NO_HEARTBEAT_GRACE_MS,
  FETCH_TIMEOUT_MS,
  POSITIONS_PAGE_CAP,
  SUBMIT_GUARD_MAX_MS,
  retryAsync,
  fetchPositions,
  isEngineUnresponsive,
  withClientTimeout,
  beginSubmit,
  endSubmit,
  submitInFlight,
  parseSettledBaseline,
  settledNoticeFor,
  loadSettledBaseline,
  saveSettledBaseline,
  normalizeConditionId,
  redeemBalanceGate,
  durableWrite,
  durableRead,
  stopLossRecords,
  recordStopLoss,
  STOPLOSS_RECENT_CAP,
} = await import("../src/engine.js");
const { decodeFunctionData, parseAbi } = await import("viem");
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
function settled(book) {
  return [book.settledCount, book.settledWins, book.settledLosses];
}

const row = (over) => ({
  token_id: "T1",
  condition_id: "0xc1",
  outcome_index: 0,
  status: "OPEN",
  slug: "m1",
  current_size: 10,
  avg_price: 0.7,
  current_value: 7,
  ...over,
});

// 우리 지갑 CLOSED 실응답 (2026-11-14, will-bitcoin-dip-to-67pt5k 승 건)
const closedWinLive = row({
  token_id: "15542710001866766202110323156169725417182779903002226439100570924289697216169",
  condition_id: "0x144fe8b30008b8d7c4452623473fb9df7645ac00b05e5ee5d5e404a56411fad9",
  status: "CLOSED",
  slug: "will-bitcoin-dip-to-67pt5k-in-november-2026",
  current_size: 0,
  avg_price: 0.8779,
  current_price: 1.0,
  current_value: 0,
  realized_pnl: 0.7016,
});

// 1. 기존 집계 회귀: REDEEMABLE=승, REDEEMABLE_LOST=패, CLOSED 없음
eq(
  settled(summarizeBook([], [row({ token_id: "W1" }), row({ token_id: "W2" })], [row({ token_id: "L1" })], [])),
  [3, 2, 1],
  "redeemable-only regression",
);

// 2. 우리 지갑 실측 예상값: 정산 완료 1건 · 1승 0패
eq(settled(summarizeBook([], [], [], [closedWinLive])), [1, 1, 0], "live wallet closed win");

// 3. CLOSED 패 — current_price 0
eq(
  settled(summarizeBook([], [], [], [row({ status: "CLOSED", current_price: 0, realized_pnl: -5 })])),
  [1, 0, 1],
  "closed loss by final price",
);

// 4·5. CLOSED 폴백 — current_price가 0/1이 아니면 realized_pnl 부호
eq(
  settled(summarizeBook([], [], [], [row({ status: "CLOSED", current_price: 0.5, realized_pnl: 2.5 })])),
  [1, 1, 0],
  "closed win by realized pnl fallback",
);
eq(
  settled(summarizeBook([], [], [], [row({ status: "CLOSED", current_price: 0.5, realized_pnl: -2.5 })])),
  [1, 0, 1],
  "closed loss by realized pnl fallback",
);

// 6. CLOSED 판정 불가 — 건수에만 더한다
eq(
  settled(summarizeBook([], [], [], [row({ status: "CLOSED", realized_pnl: 0 })])),
  [1, 0, 0],
  "closed unjudgeable counts only",
);

// 7. dedupe — 상태 전이로 같은 토큰이 REDEEMABLE과 CLOSED에 동시 존재
eq(
  settled(summarizeBook([], [closedWinLive], [], [closedWinLive])),
  [1, 1, 0],
  "dedupe across redeemable and closed",
);
eq(
  settled(summarizeBook([], [], [], [closedWinLive, closedWinLive])),
  [1, 1, 0],
  "dedupe within closed",
);

// 8. dedupe 폴백 키 — token_id 없으면 condition_id+outcome_index, 조회 판정 우선
eq(
  settled(
    summarizeBook(
      [],
      [],
      [row({ token_id: undefined, status: "REDEEMABLE" })],
      [row({ token_id: undefined, status: "CLOSED", current_price: 1, realized_pnl: 3 })],
    ),
  ),
  [1, 0, 1],
  "dedupe by condition fallback, query verdict wins",
);

// 9. 정산 미상 전파 — 어느 목록이든 못 읽으면 settled* 전부 null
eq(settled(summarizeBook([], [], [], null)), [null, null, null], "closed null propagates");
eq(settled(summarizeBook([], null, [], [])), [null, null, null], "win null propagates");
eq(settled(summarizeBook([], [], null, [])), [null, null, null], "lost null propagates");

// 10. OPEN 집계 회귀 — CLOSED 행이 섞여도 보유 통계는 OPEN만
{
  const book = summarizeBook(
    [
      row({ token_id: "O1", slug: "o1", current_size: 10, avg_price: 0.5, current_value: 6 }),
      row({ token_id: "O2", slug: "o2", current_size: 10, avg_price: 0.5, current_value: 4 }),
      closedWinLive,
    ],
    [],
    [],
    [closedWinLive],
  );
  eq(book.openCount, 2, "open count excludes closed");
  eq(book.positionsPnlPct, 0, "open pnl pct excludes closed");
  eq([...book.heldSlugs].sort(), ["o1", "o2"], "held slugs are open only");
}

// 11. judgeClosedRow 경계값
eq(judgeClosedRow({ current_price: 1 }), "win", "judge cp=1");
eq(judgeClosedRow({ current_price: 0 }), "loss", "judge cp=0");
eq(judgeClosedRow({ current_price: 0.99, realized_pnl: 1 }), "win", "judge fallback win");
eq(judgeClosedRow({}), null, "judge empty row");
eq(judgeClosedRow(null), null, "judge null row");

// 12. 사이징 스모크 (무변경 확인 — 0.3.9 실측 화면과 동일 조건)
{
  const s = sizing(18.83, 53.41, 5);
  eq(s.bet, 5, "sizing bet floor");
  eq(s.slots, 0, "sizing slots blocked by cash floor");
}

// 13. 부팅 화면 판정 — 키가 있으면 연동 화면은 어떤 경우에도 안 뜬다
eq(bootView(false, false), "link", "boot: no key shows link");
eq(bootView(false, true), "link", "boot: intent without key still link");
eq(bootView(true, false), "stopped", "boot: key without intent shows main stopped");
eq(bootView(true, true), "running", "boot: key with intent restores running");

// 14. 스냅샷 캐시 라운드트립 — 즉시 표시용 숫자가 그대로 복원된다
{
  const snap = {
    cash: 18.83,
    positionsValue: 34.58,
    equity: 53.41,
    openCount: 5,
    settledCount: 1,
    settledWins: 1,
    settledLosses: 0,
    settledRealizedPnl: 0.7,
    positionsPnlPct: -1.9,
    heldSlugs: new Set(["a-market", "b-market"]),
    cashFloor: 16.02,
    available: 2.81,
    bet: 5,
    slots: 0,
  };
  const restored = snapshotFromCache(JSON.stringify(snapshotCacheOf(snap)));
  eq(restored.cash, 18.83, "cache: cash");
  eq(restored.equity, 53.41, "cache: equity");
  eq(restored.positionsValue, 34.58, "cache: positions value");
  eq(restored.openCount, 5, "cache: open count");
  eq(restored.settledCount, 1, "cache: settled count");
  eq(restored.settledWins, 1, "cache: settled wins");
  eq(restored.settledRealizedPnl, 0.7, "cache: settled realized pnl");
  eq(restored.positionsPnlPct, -1.9, "cache: pnl pct");
  eq([...restored.heldSlugs].sort(), ["a-market", "b-market"], "cache: held slugs as set");
  eq(restored.slots, 0, "cache: slots");
  eq(snapshotCacheOf(null), null, "cache: null snapshot");
}

// 15. 깨진 캐시는 버린다 — 그럴듯한 가짜 숫자보다 자리표시가 낫다
eq(snapshotFromCache(null), null, "cache: null raw");
eq(snapshotFromCache(""), null, "cache: empty raw");
eq(snapshotFromCache("{not json"), null, "cache: broken json");
eq(snapshotFromCache('{"cash":"18.83","equity":53.41}'), null, "cache: non-numeric cash rejected");
eq(snapshotFromCache('{"cash":18.83}'), null, "cache: missing equity rejected");

// 16. 정산 미상(null)은 캐시를 거쳐도 미상으로 남는다
{
  const restored = snapshotFromCache(
    JSON.stringify(
      snapshotCacheOf({
        cash: 10,
        positionsValue: 0,
        equity: 10,
        openCount: 0,
        settledCount: null,
        settledWins: null,
        settledLosses: null,
        settledRealizedPnl: null,
        positionsPnlPct: null,
        heldSlugs: new Set(),
        cashFloor: 3,
        available: 7,
        bet: 5,
        slots: 1,
      }),
    ),
  );
  eq(
    [restored.settledCount, restored.settledWins, restored.settledLosses, restored.positionsPnlPct],
    [null, null, null, null],
    "cache: unknown settled stays unknown",
  );
  eq(restored.settledRealizedPnl, null, "cache: unknown realized stays unknown");
}

// 17. 실현수익 합산 — 건수·승패와 같은 dedupe 집합 기준
{
  // 승 +2.5 (지급 7.5 − 원가 5), 패 −5 (원가 5), CLOSED +0.7016 → −1.7984 → −1.8
  const book = summarizeBook(
    [],
    [row({ token_id: "RW", entry_cost_usdc: 5, current_value: 7.5 })],
    [row({ token_id: "RL", entry_cost_usdc: 5, current_value: 0 })],
    [closedWinLive],
  );
  eq(settled(book), [3, 2, 1], "realized: mixed counts");
  eq(book.settledRealizedPnl, -1.8, "realized: mixed sum");
  eq(book.settledRealizedExcluded, 0, "realized: none excluded");
}
{
  // 우리 지갑 실측: CLOSED 승 1건의 realized_pnl 0.7016 → 0.7
  eq(
    summarizeBook([], [], [], [closedWinLive]).settledRealizedPnl,
    0.7,
    "realized: live wallet closed win",
  );
}
{
  // dedupe — 같은 토큰이 승 목록과 CLOSED에 있으면 살아남은 쪽(승)의 공식을 한 번만
  const asWin = row({
    token_id: "DUP",
    status: "REDEEMABLE",
    entry_cost_usdc: 5,
    current_value: 7.5,
  });
  const asClosed = row({
    token_id: "DUP",
    status: "CLOSED",
    current_price: 1,
    realized_pnl: 99,
    entry_cost_usdc: 5,
    current_value: 0,
  });
  const book = summarizeBook([], [asWin], [], [asClosed]);
  eq(settled(book), [1, 1, 0], "realized dedupe: counted once");
  eq(book.settledRealizedPnl, 2.5, "realized dedupe: surviving list formula wins");
}
{
  // 승패 판정 불가(건수만) 행도 realized_pnl이 있으면 합산한다 — 0도 값이다
  const book = summarizeBook(
    [],
    [],
    [],
    [row({ status: "CLOSED", current_price: 0.5, realized_pnl: 0 })],
  );
  eq(settled(book), [1, 0, 0], "realized: unjudgeable counts only");
  eq(book.settledRealizedPnl, 0, "realized: zero pnl still sums");
  eq(book.settledRealizedExcluded, 0, "realized: zero pnl not excluded");
}
{
  // 실현을 계산할 필드가 없는 행은 합산에서 빼고 excluded로 센다 — 0으로 만들지 않는다
  const noPnl = row({ status: "CLOSED", current_price: undefined, realized_pnl: undefined });
  const book = summarizeBook([], [], [], [noPnl]);
  eq(settled(book), [1, 0, 0], "realized excluded: still counted");
  eq(book.settledRealizedPnl, null, "realized excluded: no fabricated number");
  eq(book.settledRealizedExcluded, 1, "realized excluded: counted as excluded");
}
{
  // 일부만 계산 가능하면 가능한 행만 합산하고 나머지는 excluded
  const book = summarizeBook(
    [],
    [row({ token_id: "GW", entry_cost_usdc: 4, current_value: 6 })],
    [],
    [row({ token_id: "BX", status: "CLOSED" })],
  );
  eq(settled(book), [2, 1, 0], "realized partial: counts");
  eq(book.settledRealizedPnl, 2, "realized partial: sums computable only");
  eq(book.settledRealizedExcluded, 1, "realized partial: excluded counted");
}
// 정산 미상이면 실현도 미상, 정산 0건이면 실현 0이 사실이다
eq(summarizeBook([], [], [], null).settledRealizedPnl, null, "realized: unknown propagates");
eq(summarizeBook([], [], [], []).settledRealizedPnl, 0, "realized: empty settled is zero");

// 18. 호스트 모드 판정 — "engine"만 엔진, 나머지는 전부 뷰어(안전한 쪽)
eq(resolveHostMode("engine"), "engine", "host: engine");
eq(resolveHostMode("viewer"), "viewer", "host: viewer");
eq(resolveHostMode(undefined), "viewer", "host: undefined is viewer");
eq(resolveHostMode(""), "viewer", "host: empty is viewer");
eq(resolveHostMode("ENGINE"), "viewer", "host: unknown value is viewer");

// 19. 자동 재개 판정 — 엔진 호스트는 네이티브 미러가 정본
eq(shouldAutoStart("engine", false, true), true, "start: engine mirror on without LS flag");
eq(shouldAutoStart("engine", true, true), true, "start: engine mirror on");
eq(shouldAutoStart("engine", true, false), false, "start: engine mirror off beats LS flag");
eq(shouldAutoStart("engine", false, false), false, "start: engine mirror off");
eq(shouldAutoStart("engine", true, null), false, "start: engine without bridge never auto-starts");
eq(shouldAutoStart("viewer", true, null), true, "start: viewer legacy saved");
eq(shouldAutoStart("viewer", true, false), false, "start: viewer mirror explicitly off");
eq(shouldAutoStart("viewer", true, true), true, "start: viewer saved and mirror on");
eq(shouldAutoStart("viewer", false, true), false, "start: viewer not saved");

// 20. 워치독 stale 판정 — 사이클 3개 + 여유가 기준, 무하트비트는 stale 아님
eq(HEARTBEAT_STALE_MS, 200000, "stale: threshold is 3 cycles + slack");
eq(isHeartbeatStale(1000, 1000 + 200000), false, "stale: exactly at threshold is fresh");
eq(isHeartbeatStale(1000, 1000 + 200001), true, "stale: past threshold");
eq(isHeartbeatStale(0, 999999999), false, "stale: no heartbeat is not stale");
eq(isHeartbeatStale(-5, 999999999), false, "stale: negative is not stale");
eq(isHeartbeatStale(NaN, 1000), false, "stale: NaN is not stale");

// 21. 손절 판정 — 발화 경계·결정 구간 제외·결측 스킵
eq(stopLossVerdict(0.75, 0.5), "triggered", "stoploss: boundary triggers");
eq(stopLossVerdict(0.75, 0.49), "triggered", "stoploss: below line triggers");
eq(stopLossVerdict(0.75, 0.51), "holding", "stoploss: above line holds");
eq(stopLossVerdict(0.9, 0.97), "decided", "stoploss: decided high excluded");
eq(stopLossVerdict(0.9, 0.99), "decided", "stoploss: near-resolved excluded");
eq(stopLossVerdict(0.28, 0.03), "decided", "stoploss: decided low excluded even below line");
eq(stopLossVerdict(0.28, 0.02), "decided", "stoploss: crashed to zero excluded");
eq(stopLossVerdict(0.75, NaN), "skip", "stoploss: missing price skips");
eq(stopLossVerdict(NaN, 0.4), "skip", "stoploss: missing entry skips");
eq(stopLossVerdict(0.62, 0.37), "triggered", "stoploss: low band triggers");

// 22. outcome 인덱스 — Gamma 이름 대조 1순위, outcome_index 폴백
eq(pickOutcomeIndex(["Yes", "No"], { outcome: "No" }), 1, "index: name match");
eq(pickOutcomeIndex(["Yes", "No"], { outcome: "yes" }), 0, "index: name case-insensitive");
eq(pickOutcomeIndex(["Yes", "No"], { outcome: "Maybe", outcome_index: 1 }), 1, "index: fallback to outcome_index");
eq(pickOutcomeIndex(["Yes", "No"], { outcome_index: 0 }), 0, "index: outcome_index only");
eq(pickOutcomeIndex(["Yes", "No"], {}), -1, "index: nothing usable");
eq(pickOutcomeIndex([], { outcome_index: 2 }), 2, "index: fallback without outcomes list");
eq(pickOutcomeIndex(["Yes", "No"], { outcome_index: 1.5 }), -1, "index: non-integer rejected");
eq(pickOutcomeIndex(["Yes", "No"], { outcome_index: -1 }), -1, "index: negative rejected");

// 23. 손절 기록의 정산 분류 — 현재가가 아닌 매도 실현손익으로 가른다
{
  // 손절로 판 토큰이 나중에 승으로 정산돼 CLOSED current_price가 1이어도
  // 판정은 기록의 실현손익(-30 → 패)을 따라야 한다
  const closedWouldBeWin = row({
    token_id: "SL1",
    status: "CLOSED",
    current_price: 1,
    realized_pnl: 55,
    entry_cost_usdc: 100,
    current_value: 0,
  });
  const book = summarizeBook([], [], [], [closedWouldBeWin], [
    { token_id: "SL1", realized_pnl: -30 },
  ]);
  eq(settled(book), [1, 0, 1], "stoploss settled: record verdict beats final price");
  eq(book.settledRealizedPnl, -30, "stoploss settled: record pnl beats row pnl");
  eq(book.settledStopLoss, 1, "stoploss settled: counted as stoploss");
}
{
  // CLOSED 행이 아직 없어도 손절 기록만으로 정산에 잡힌다
  const book = summarizeBook([], [], [], [], [{ token_id: "SL2", realized_pnl: -12.5 }]);
  eq(settled(book), [1, 0, 1], "stoploss settled: record alone counts");
  eq(book.settledRealizedPnl, -12.5, "stoploss settled: record alone pnl");
  eq(book.settledStopLoss, 1, "stoploss settled: record alone flagged");
}
{
  // 손절이 이긴 경우(실현 양수)도 부호로 판정한다
  const book = summarizeBook([], [], [], [], [{ token_id: "SL3", realized_pnl: 4 }]);
  eq(settled(book), [1, 1, 0], "stoploss settled: positive record is a win");
}
{
  // 기록이 없으면 기존 집계와 동일하고 손절 건수는 0이다
  const book = summarizeBook([], [row({ token_id: "W1" })], [], [], []);
  eq(settled(book), [1, 1, 0], "stoploss settled: no records regression");
  eq(book.settledStopLoss, 0, "stoploss settled: zero without records");
  eq(summarizeBook([], [], [], []).settledStopLoss, 0, "stoploss settled: omitted arg is zero");
}
{
  // 깨진 기록은 건너뛴다 — 토큰 없는 기록이 집계를 오염시키지 않는다
  const book = summarizeBook([], [], [], [], [
    { realized_pnl: -5 },
    { token_id: "OK1", realized_pnl: -5 },
    null,
  ]);
  eq(settled(book), [1, 0, 1], "stoploss settled: malformed records skipped");
  eq(book.settledStopLoss, 1, "stoploss settled: only valid record flagged");
}
{
  // 정산 미상이면 손절 건수도 미상이다
  eq(summarizeBook([], null, [], [], []).settledStopLoss, null, "stoploss settled: unknown propagates");
}

// 24. 페이지 구조 가드 — 뷰어 블록은 루프를 시작하지 않고,
// 엔진 호스트 블록은 DOM을 만지지 않는다
{
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  const marker = "뷰어 부팅 (액티비티 WebView)";
  const cut = html.indexOf(marker);
  eq(cut > 0, true, "guard: viewer boot block exists");
  const enginePart = html.slice(0, cut);
  const viewerPart = html.slice(cut);
  eq(enginePart.includes("BM.restore("), true, "guard: engine host restores");
  eq(enginePart.includes("getElementById"), false, "guard: engine host touches no DOM");
  eq(viewerPart.includes("BM.start("), false, "guard: viewer never starts loop");
  eq(viewerPart.includes("BM.restore("), false, "guard: viewer never restores engine");
  eq(viewerPart.includes("BM.stop("), false, "guard: viewer never stops loop directly");
  eq(viewerPart.includes("setRunIntent(true)"), true, "guard: viewer starts via run intent");
}

// 25. 제출 잠금 — 중복 체결 방지 (2026-11-14 실계좌 결함 수정)
// 같은 slug는 제출 시작부터 결과 확정까지 한 번만 임계 구간에 들어간다.
{
  eq(tryAcquireSubmitLock("lock-a"), true, "lock: first acquire succeeds");
  eq(tryAcquireSubmitLock("lock-a"), false, "lock: second acquire blocked");
  eq(isSubmitLocked("lock-a"), true, "lock: held state visible");
  eq(tryAcquireSubmitLock("lock-b"), true, "lock: other slug unaffected");
  releaseSubmitLock("lock-a");
  eq(isSubmitLocked("lock-a"), false, "lock: released");
  eq(tryAcquireSubmitLock("lock-a"), true, "lock: re-acquire after release");
  releaseSubmitLock("lock-a");
  releaseSubmitLock("lock-b");

  // 동시 제출 시뮬레이션 — 두 워커가 같은 slug로 겹쳐 들어와도 진입은 1회
  const entries = [];
  async function worker(slug) {
    if (!tryAcquireSubmitLock(slug)) return "blocked";
    try {
      entries.push(slug);
      await new Promise((r) => setTimeout(r, 5));
      return "entered";
    } finally {
      releaseSubmitLock(slug);
    }
  }
  const [r1, r2] = await Promise.all([worker("race-slug"), worker("race-slug")]);
  eq([r1, r2].sort(), ["blocked", "entered"], "lock: concurrent submit — exactly one enters");
  eq(entries.length, 1, "lock: critical section entered once");
  eq(isSubmitLocked("race-slug"), false, "lock: released after race");

  // 구조 가드 — 매수 경로는 잠금 획득 뒤에만 서명하고, 제출 직전 bought를 재확인한다
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const cycleSrc = src.slice(src.indexOf("async function runCycle"));
  const lockIdx = cycleSrc.indexOf("tryAcquireSubmitLock(cand.slug)");
  const signIdx = cycleSrc.indexOf("createMarketOrder(");
  eq(lockIdx > 0 && signIdx > lockIdx, true, "guard: buy path locks before signing");
  eq(
    cycleSrc.slice(lockIdx, signIdx).includes("boughtSlugs().has(cand.slug)"),
    true,
    "guard: bought re-checked inside the lock, right before submit",
  );
  eq(src.includes("if (!state.client || state.running) return;"), true, "guard: start() refuses double start");
}

// 26. 비밀 저장 — Keystore 암호화 저장·마이그레이션·복호화 실패 낙하
{
  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  // 가짜 브리지 — 접두사를 붙였다 떼는 가역 스텁 (실제 AES는 네이티브 담당)
  globalThis.window.AndroidBridge = {
    encryptSecret: (plain) => `stub:${Buffer.from(plain, "utf8").toString("base64")}`,
    decryptSecret: (enc) =>
      enc.startsWith("stub:")
        ? Buffer.from(enc.slice(5), "base64").toString("utf8")
        : null,
  };
  eq(storeSecret("bm_pk", "0xdeadbeef"), true, "secret: store ok");
  eq(store.get("bm_pk").startsWith("enc1:stub:"), true, "secret: stored as ciphertext only");
  eq(store.get("bm_pk").includes("deadbeef"), false, "secret: no plaintext in storage");
  eq(readSecret("bm_pk"), "0xdeadbeef", "secret: roundtrip decrypts");

  // 레거시 평문은 그대로 읽히고, 마이그레이션이 암호문으로 덮는다
  store.set("bm_creds", JSON.stringify({ api_key: "k" }));
  eq(readSecret("bm_creds"), JSON.stringify({ api_key: "k" }), "secret: legacy plaintext readable");
  migrateSecrets();
  eq(store.get("bm_creds").startsWith("enc1:"), true, "secret: migration encrypts plaintext");
  eq(readSecret("bm_creds"), JSON.stringify({ api_key: "k" }), "secret: migrated value still readable");
  eq(store.get("bm_pk").startsWith("enc1:"), true, "secret: migration leaves ciphertext alone");

  // 복호화 실패는 null — 호출자가 연동 화면으로 안전하게 떨어지는 근거
  store.set("bm_pk", "enc1:garbage-not-stub");
  eq(readSecret("bm_pk"), null, "secret: decrypt failure returns null");

  // 브리지가 있는데 암호화가 실패하면 평문으로 저장하지 않는다
  globalThis.window.AndroidBridge = { encryptSecret: () => null, decryptSecret: () => null };
  eq(storeSecret("bm_pk", "0xbeef"), false, "secret: encrypt failure refuses store");
  eq(store.get("bm_pk"), "enc1:garbage-not-stub", "secret: failed store writes nothing");
  delete globalThis.window.AndroidBridge;
}

// 27. 리딤 — 계획·검증·콜 구성 (live_executor 방식 이식)
{
  const cidA = `0x${"aa".repeat(32)}`;
  const cidB = `0x${"bb".repeat(32)}`;
  const plan = planRedeems([
    { condition_id: cidA, token_id: "t1", status: "REDEEMABLE", current_value: 12.5 },
    { condition_id: cidA, token_id: "t2", status: "REDEEMABLE_LOST", current_value: 0 },
    { condition_id: cidB, token_id: "t3", status: "REDEEMABLE_LOST", current_value: 0 },
    { condition_id: "not-a-cid", token_id: "t4", status: "REDEEMABLE", current_value: 3 },
  ]);
  eq(plan.targets.length, 1, "redeem: one recoverable target");
  eq(plan.targets[0].condition_id, cidA, "redeem: target grouped by condition");
  eq(plan.targets[0].expected_usd, 12.5, "redeem: expected is win value sum");
  eq(plan.worthless.length, 1, "redeem: loss-only condition is worthless");
  eq(plan.worthless[0].condition_id, cidB, "redeem: worthless condition id");
  eq(plan.errors.length, 1, "redeem: malformed condition id reported");

  eq(verifyRecovery(100, 105, 5).status, "verified", "redeem: exact recovery verified");
  eq(verifyRecovery(100, 103, 5).status, "mismatch", "redeem: short recovery mismatched");
  eq(verifyRecovery(0, 1015, 1000).status, "verified", "redeem: 2% relative tolerance");
  eq(verifyRecovery(0, 1030, 1000).status, "mismatch", "redeem: beyond tolerance mismatched");

  const ctfAbi = parseAbi(["function redeemPositions(address,bytes32,bytes32,uint256[])"]);
  const call = buildCtfRedeemCall(cidA, false);
  eq(call.target.toLowerCase(), "0xada100db00ca00073811820692005400218fce1f", "redeem: ctf adapter target");
  const dec = decodeFunctionData({ abi: ctfAbi, data: call.data });
  eq(dec.args[1], `0x${"0".repeat(64)}`, "redeem: parent collection is zero bytes32");
  eq(dec.args[2], cidA, "redeem: condition id in call");
  eq(dec.args[3].map(String), ["1", "2"], "redeem: binary index sets");
  eq(
    buildCtfRedeemCall(cidA, true).target.toLowerCase(),
    "0xada2005600dec949baf300f4c6120000bdb6eaab",
    "redeem: neg-risk adapter target",
  );

  // v2 bytes31 — 끝 바이트(00/01)를 떼고, 이미 31바이트면 그대로
  eq(toBytes31(`0x${"ab".repeat(31)}01`), `0x${"ab".repeat(31)}`, "redeem: bytes31 strips outcome byte");
  eq(toBytes31(`0x${"ab".repeat(31)}`), `0x${"ab".repeat(31)}`, "redeem: bytes31 passthrough");
  let threw = false;
  try {
    toBytes31("0x1234");
  } catch {
    threw = true;
  }
  eq(threw, true, "redeem: bytes31 rejects malformed id");
  const v2Abi = parseAbi(["function redeem(bytes31,uint256,uint256)"]);
  const v2 = buildV2RedeemCall(`0x${"ab".repeat(31)}01`, 1, 12345n);
  eq(v2.target.toLowerCase(), "0x12121212006e4cd160d18e3f00711da5c3372600", "redeem: v2 router target");
  const v2dec = decodeFunctionData({ abi: v2Abi, data: v2.data });
  eq(v2dec.args[0], `0x${"ab".repeat(31)}`, "redeem: v2 call carries bytes31");
  eq(String(v2dec.args[1]), "1", "redeem: v2 outcome index");
  eq(String(v2dec.args[2]), "12345", "redeem: v2 amount is measured balance");
}

// 28. 후보 정렬 — 구간(0.70–0.80) 우선 유지 + 그룹 내 만기 가까운 순
{
  const day = 86400_000;
  const isoIn = (days) => new Date(Date.now() + days * day).toISOString();
  const mkt = (slug, price, days) => ({
    active: true,
    closed: false,
    slug,
    endDate: isoIn(days),
    volume: 20000,
    outcomePrices: JSON.stringify([price, 1 - price]),
  });
  const out = scanCandidates(
    [mkt("sort-far-band", 0.75, 10), mkt("sort-near-band", 0.75, 3), mkt("sort-near-out", 0.65, 2), mkt("sort-far-out", 0.85, 5)],
    new Set(),
    new Set(),
  );
  eq(
    out.map((c) => c.slug),
    ["sort-near-band", "sort-far-band", "sort-near-out", "sort-far-out"],
    "sort: band group first, nearest endDate inside group",
  );
}

// 29. F1 — 하트비트 주기·타임아웃·페이지 상한 상수 정합
eq(HEARTBEAT_INTERVAL_MS, 30000, "f1: heartbeat interval");
eq(HEARTBEAT_INTERVAL_MS < HEARTBEAT_STALE_MS, true, "f1: interval beats well before stale");
eq(FETCH_TIMEOUT_MS, 20000, "f1: fetch timeout");
eq(POSITIONS_PAGE_CAP, 50, "f1: positions page cap");
eq(SUBMIT_GUARD_MAX_MS, 600000, "f1: submit guard max");

// 30. F1 — 제출 진행 가드: 카운터가 0↔1일 때만 네이티브 플래그가 바뀐다
{
  const flags = [];
  globalThis.window.AndroidBridge = { setSubmitInFlight: (on) => flags.push(on) };
  eq(submitInFlight(), false, "f1 guard: initially idle");
  beginSubmit();
  beginSubmit();
  eq(submitInFlight(), true, "f1 guard: in flight");
  endSubmit();
  eq(submitInFlight(), true, "f1 guard: nested submit still in flight");
  endSubmit();
  eq(submitInFlight(), false, "f1 guard: drained");
  endSubmit(); // 과잉 해제는 음수로 내려가지 않는다
  eq(submitInFlight(), false, "f1 guard: over-release clamped");
  eq(flags, [true, false], "f1 guard: native flag toggles only at edges");
  delete globalThis.window.AndroidBridge;
}

// 31. 부수2 — fetchPositions 커서 순환은 상한에서 끊기고, 정상 종료는 모은다
{
  const realFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return {
      ok: true,
      json: async () => ({ data: [{ token_id: "x" }], pagination: { next_cursor: "loop" } }),
    };
  };
  let threw = false;
  try {
    await fetchPositions("0xfunder");
  } catch {
    threw = true;
  }
  eq(threw, true, "cursor: cycling cursor throws");
  eq(calls, POSITIONS_PAGE_CAP, "cursor: stops exactly at cap");
  let n = 0;
  globalThis.fetch = async () => {
    n += 1;
    return {
      ok: true,
      json: async () => ({
        data: [{ token_id: `t${n}` }],
        pagination: { next_cursor: n < 2 ? "c2" : null },
      }),
    };
  };
  const rows = await fetchPositions("0xfunder");
  eq(rows.length, 2, "cursor: pages accumulate until cursor ends");
  globalThis.fetch = realFetch;
}

// 32. F2 — retryAsync: 성공까지 재시도하고, 소진하면 마지막 오류를 던진다
{
  const nap = async () => {};
  let tries = 0;
  const v = await retryAsync(async () => {
    tries += 1;
    if (tries < 3) throw new Error("net down");
    return "ok";
  }, 3, 1, nap);
  eq(v, "ok", "retry: eventual success returned");
  eq(tries, 3, "retry: attempted until success");
  let tries2 = 0;
  let threw = false;
  try {
    await retryAsync(async () => {
      tries2 += 1;
      throw new Error("still down");
    }, 2, 1, nap);
  } catch {
    threw = true;
  }
  eq(threw, true, "retry: exhaustion throws");
  eq(tries2, 2, "retry: attempts respected");
}

// 33. F2 — 뷰어 무응답 판정: 무하트비트도 유예 뒤에는 무응답이다
{
  const now = 1_000_000;
  eq(isEngineUnresponsive(now - 1000, now, 0), false, "f2 chip: fresh heartbeat responsive");
  eq(isEngineUnresponsive(now - 200001, now, 0), true, "f2 chip: stale heartbeat unresponsive");
  eq(isEngineUnresponsive(0, now, now - 1000), false, "f2 chip: no heartbeat within grace");
  eq(
    isEngineUnresponsive(0, now, now - NO_HEARTBEAT_GRACE_MS - 1),
    true,
    "f2 chip: no heartbeat past grace is unresponsive",
  );
  eq(isEngineUnresponsive(0, now, NaN), false, "f2 chip: unknown observation start is not unresponsive");
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  eq(
    html.includes("BM.isEngineUnresponsive(heartbeatMs"),
    true,
    "f2 chip: viewer uses shared unresponsive verdict",
  );
}

// 34. F5 — 정산 기준선 파싱·알림 판정·영속 라운드트립
{
  eq(parseSettledBaseline(null), null, "f5: null raw");
  eq(parseSettledBaseline("{broken"), null, "f5: broken json");
  eq(parseSettledBaseline('{"count":3,"realized":12.5}'), { count: 3, realized: 12.5 }, "f5: valid baseline");
  eq(parseSettledBaseline('{"count":null,"realized":null}'), { count: null, realized: null }, "f5: null fields kept");
  eq(parseSettledBaseline('{"count":"3","realized":1}'), null, "f5: string count rejected");
  eq(parseSettledBaseline('{"count":3}'), null, "f5: missing field rejected");

  const snap = { settledCount: 5, settledRealizedPnl: 20 };
  eq(settledNoticeFor(null, snap), null, "f5: no baseline no notice");
  eq(
    settledNoticeFor({ count: 3, realized: 12.5 }, snap),
    { count: 2, realizedDelta: 7.5 },
    "f5: downtime settlements surface as notice",
  );
  eq(settledNoticeFor({ count: 5, realized: 20 }, snap), null, "f5: no new settlements no notice");
  eq(
    settledNoticeFor({ count: 3, realized: null }, snap),
    null,
    "f5: unknown realized in baseline blocks notice",
  );
  eq(
    settledNoticeFor({ count: 3, realized: 1 }, { settledCount: null, settledRealizedPnl: null }),
    null,
    "f5: unknown snapshot blocks notice",
  );

  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  saveSettledBaseline({ count: 7, realized: -3.25 });
  eq(loadSettledBaseline(), { count: 7, realized: -3.25 }, "f5: persisted baseline roundtrip");
}

// 35. F3 — 이미 상환됨 판정 게이트와 condition_id 정규화
{
  eq(redeemBalanceGate([0n, 0n]), "already", "f3: all zero balances already redeemed");
  eq(redeemBalanceGate([0n, 5n]), "submit", "f3: remaining balance submits");
  eq(redeemBalanceGate([]), "unknown", "f3: no balances is unknown");
  eq(redeemBalanceGate(null), "unknown", "f3: null balances is unknown");

  const lower = "ab".repeat(32);
  eq(normalizeConditionId(`0x${lower.toUpperCase()}`), lower, "f3: cid normalized to lowercase");
  eq(normalizeConditionId(lower), lower, "f3: cid without prefix");
  eq(normalizeConditionId("0x1234"), null, "f3: short cid rejected");
  eq(normalizeConditionId(null), null, "f3: null cid rejected");
  eq(normalizeConditionId(`0x${"zz".repeat(32)}`), null, "f3: non-hex cid rejected");

  // 대문자 condition_id도 계획에서 같은 condition으로 묶인다 (정규화 회귀)
  const plan = planRedeems([
    { condition_id: `0x${"CD".repeat(32)}`, token_id: "t9", status: "REDEEMABLE", current_value: 4 },
  ]);
  eq(plan.targets.length, 1, "f3: uppercase cid still plans");
  eq(plan.targets[0].condition_id, `0x${"cd".repeat(32)}`, "f3: plan key normalized");
}

// 36. 부수1 — 영속 미러: localStorage가 비어도 미러에서 되살아난다
{
  const ls = new Map();
  const mirror = new Map();
  globalThis.localStorage = {
    getItem: (k) => (ls.has(k) ? ls.get(k) : null),
    setItem: (k, v) => ls.set(k, String(v)),
    removeItem: (k) => ls.delete(k),
  };
  globalThis.window.AndroidBridge = {
    putDurable: (k, v) => mirror.set(k, v),
    getDurable: (k) => (mirror.has(k) ? mirror.get(k) : null),
    removeDurable: (k) => mirror.delete(k),
  };
  durableWrite("bm_bought", '["m1","m2"]');
  eq(ls.get("bm_bought"), '["m1","m2"]', "durable: written to localStorage");
  eq(mirror.get("bm_bought"), '["m1","m2"]', "durable: written to mirror");
  ls.clear(); // 프로세스 급사로 localStorage 반영이 사라진 상황
  eq(durableRead("bm_bought"), '["m1","m2"]', "durable: mirror revives lost write");
  eq(ls.get("bm_bought"), '["m1","m2"]', "durable: revival restores localStorage");
  eq(durableRead("bm_missing"), null, "durable: missing key stays missing");
  delete globalThis.window.AndroidBridge;
}

// 37. 부수4 — 손절 원장 아카이브: 캡을 넘어도 정산 분류가 유지된다
{
  const ls = new Map();
  globalThis.localStorage = {
    getItem: (k) => (ls.has(k) ? ls.get(k) : null),
    setItem: (k, v) => ls.set(k, String(v)),
    removeItem: (k) => ls.delete(k),
  };
  const total = STOPLOSS_RECENT_CAP + 5;
  for (let i = 0; i < total; i += 1) {
    recordStopLoss({ token_id: `SLT${i}`, realized_pnl: -1 });
  }
  const recs = stopLossRecords();
  eq(recs.length, total, "archive: records beyond cap survive");
  const book = summarizeBook([], [], [], [], recs);
  eq(book.settledCount, total, "archive: settled count keeps archived records");
  eq(book.settledStopLoss, total, "archive: all classified as stoploss");
  eq(book.settledRealizedPnl, -total, "archive: realized sum keeps archived records");
}

// 37. SDK 타임아웃 래퍼 — 느린 메서드는 시간 초과로 끊기고, 빠른 메서드는
// 값·this를 그대로 돌려준다 (사이클 루프 영구 정지 방지)
{
  const fast = withClientTimeout({ v: 7, async get() { return this.v; } }, 50);
  eq(await fast.get(), 7, "clientTimeout: fast call passes value and this");
  eq(fast.v, 7, "clientTimeout: non-function props pass through");
  const slow = withClientTimeout({ async get() { return new Promise(() => {}); } }, 30);
  let threw = null;
  try {
    await slow.get();
  } catch (e) {
    threw = e;
  }
  eq(/SDK 호출 시간 초과/.test(String(threw?.message ?? "")), true, "clientTimeout: hung call is cut by timeout");
}

// 37.5 스캔 통계 — scanCandidates가 단계별 탈락을 세고, summarizeCycle이
// 조회 수와 제외 사유를 요약에 싣는다 (후보 0 원인 특정용 계측)
{
  const mk = (over) => ({
    active: true, closed: false, acceptingOrders: true, archived: false,
    slug: "mkt-x", sportsMarketType: "", endDate: "2099-01-01T00:00:00Z",
    volume: 50000, outcomePrices: "[0.75, 0.25]", ...over,
  });
  const stats = {};
  const soon = new Date(Date.now() + 10 * 86400_000).toISOString();
  const out = scanCandidates([
    mk({ slug: "ok-1", endDate: soon }),
    mk({ slug: "sport-1", endDate: soon, sportsMarketType: "moneyline" }),
    mk({ slug: "far-1", endDate: "2099-01-01T00:00:00Z" }),
    mk({ slug: "band-1", endDate: soon, outcomePrices: "[0.95, 0.05]" }),
    mk({ slug: "ok-1", endDate: soon }),
  ], new Set(["ok-1"]), new Set(), stats);
  eq(out.length, 0, "scanStats: held market excluded from candidates");
  eq(stats.total, 5, "scanStats: total counts all fetched markets");
  eq(stats.sports, 1, "scanStats: sports counted");
  eq(stats.end_out, 1, "scanStats: end_out counted");
  eq(stats.band_out, 1, "scanStats: band_out counted");
  eq(stats.owned, 2, "scanStats: owned counted");
  const line = summarizeCycle({ candidates: 0, verified: 0, rejects: {}, filled: 0, unfilled: 0, failed: 0, scan: stats });
  eq(line.includes("조회 5"), true, "scanStats: summary carries fetched count");
  eq(line.includes("스포츠 1"), true, "scanStats: summary carries drop reasons");
}

// 38. 구조 가드 — F1/F3/F4/F6 수정이 소스에 실제로 박혀 있다
{
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  eq(src.includes("setInterval(beatHeartbeat, HEARTBEAT_INTERVAL_MS)"), true, "guard: heartbeat interval runs while engine runs");
  eq(src.includes("new AbortController()"), true, "guard: fetches carry timeouts");
  eq(src.includes("리딤 계획 제외"), true, "guard: redeem plan errors are logged");
  eq(
    (src.match(/await fetch\(/g) || []).length,
    1,
    "guard: only fetch call left is inside fetchWithTimeout itself",
  );
  eq(src.includes("withClientTimeout("), true, "guard: SDK client wrapped with timeout");
  eq(src.includes('setStage("stoploss")'), true, "guard: cycle stage tracking present");
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  eq(html.includes("btnReport"), true, "guard: report button present in viewer");
  eq(html.includes("/report"), true, "guard: report posts to relay endpoint");
  eq(html.includes("async function sendReport"), true, "guard: shared report sender present");
  eq(html.includes("engine_unresponsive"), true, "guard: auto-report on unresponsive engine");
  eq(html.includes("autoReportSent"), true, "guard: auto-report once per episode");
  eq(html.includes("payload.bought"), true, "guard: report payload carries bought list");
  eq(src.includes("reconcileBoughtOnce"), true, "guard: bought reconciliation wired before scan");
  eq(src.includes("already_redeemed"), true, "guard: already-redeemed termination recorded");
  const bridge = readFileSync(
    new URL("../../android/src/com/roomedia/boringmech/Bridge.java", import.meta.url),
    "utf8",
  );
  eq(bridge.includes("synchronized (SECRET_KEY_LOCK)"), true, "guard: keystore key creation synchronized");
  eq(bridge.includes("putDurable"), true, "guard: durable mirror bridge exists");
  const svc = readFileSync(
    new URL("../../android/src/com/roomedia/boringmech/EngineService.java", import.meta.url),
    "utf8",
  );
  eq(svc.includes("getSubmitInFlight"), true, "guard: watchdog consults submit guard");
  eq(svc.includes("onRenderProcessGone"), true, "guard: engine recreates webview on renderer death");
  const act = readFileSync(
    new URL("../../android/src/com/roomedia/boringmech/MainActivity.java", import.meta.url),
    "utf8",
  );
  eq(act.includes("web.destroy()"), true, "guard: viewer webview destroyed in onDestroy");
  eq(act.includes("onRenderProcessGone"), true, "guard: viewer recreates webview on renderer death");
}

// ── reconcileBought: 온체인 이력·보유에 없는 기매수 기록만 제거 ──
{
  const bought = new Set(["a", "b", "c", "d"]);
  const onChain = new Set(["a"]);
  const held = new Set(["b"]);
  const { kept, dropped } = reconcileBought(bought, onChain, held);
  eq(JSON.stringify(kept), JSON.stringify(["a", "b"]), "reconcile: keeps traded and held");
  eq(JSON.stringify(dropped), JSON.stringify(["c", "d"]), "reconcile: drops phantom records");
  const none = reconcileBought(new Set(), onChain, held);
  eq(none.kept.length + none.dropped.length, 0, "reconcile: empty bought stays empty");
}

console.log(`book tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
