// summarizeBook 픽스처 테스트 — node_modules가 있는 사본에서 실행:
//   node test/book.test.mjs
// engine.js는 모듈 최상위에서 window.BM을 대입하므로 스텁을 먼저 깐다.
globalThis.window = {};
const {
  summarizeBook,
  summarizeCycle,
  sizing,
  bootView,
  snapshotCacheOf,
  snapshotFromCache,
  resolveHostMode,
  shouldAutoStart,
  isHeartbeatStale,
  HEARTBEAT_STALE_MS,
  isLoopStalled,
  LOOP_WATCH_INTERVAL_MS,
  LOOP_STALL_MS,
  shouldSelfReload,
  CYCLE_ERROR_RELOAD_THRESHOLD,
  SELF_RELOAD_COOLDOWN_MS,
  stopLossVerdict,
  pickOutcomeIndex,
  tryAcquireSubmitLock,
  releaseSubmitLock,
  isSubmitLocked,
  storeSecret,
  readSecret,
  migrateSecrets,
  planRedeems,
  collectRedeemPending,
  verifyRecovery,
  buildCtfRedeemCall,
  buildV2RedeemCall,
  toBytes31,
  scanCandidates,
  reconcileBought,
  judgeFill,
  boughtCorrupted,
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
  loadRedeemPending,
  saveRedeemPending,
  stopLossRecords,
  recordStopLoss,
  manualSellRecords,
  manualSellAttemptedTokens,
  stopLossAttemptedSlugs,
  STOPLOSS_RECENT_CAP,
  isOpenRow,
  parseTradeSettings,
  settingsSummaryText,
  DEFAULT_TRADE_SETTINGS,
  BAND_LO,
  BAND_HI,
  PRIORITY_BAND_LO,
  PRIORITY_BAND_HI,
  VOLUME_MIN,
  END_WINDOW_DAYS,
  SPREAD_MAX,
  ASK_DEPTH_MIN_USD,
  SLIPPAGE_CAP,
  SNAPSHOT_MATCH_TOL,
  ASK_ABOVE_SIGNAL,
  BET_USD,
  CASH_FLOOR_PCT,
  RESERVE_USD,
  MAX_POSITIONS,
  VERIFY_CAP,
  STOP_DROP,
  TEMP_STOP_DROP,
  DECIDED_HIGH,
  DECIDED_LOW,
  ORDER_TEST_DEFAULT_ROUNDS,
  stageDurationsText,
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

// 1. 정산 집계 (2026-11-24 API 표시 교체) — CLOSED 행의 realized_pnl 직독
eq(
  settled(summarizeBook([], [
    row({ token_id: "W1", realized_pnl: 1.5 }),
    row({ token_id: "W2", realized_pnl: 2 }),
    row({ token_id: "L1", realized_pnl: -1 }),
  ])),
  [3, 2, 1],
  "closed-only aggregation",
);

// 1b. 0원 확정 패자 행도 realized_pnl 그대로 — 내부 재판정·원가 계산 없음
{
  const loser = row({
    token_id: "LX1", status: "CLOSED", current_price: 0,
    current_value: 0, entry_cost_usdc: 5.09, realized_pnl: -5.09,
  });
  const book = summarizeBook([], [loser]);
  eq([book.settledCount, book.settledWins, book.settledLosses], [1, 0, 1],
    "closed loser classified by realized pnl sign");
  eq(book.settledRealizedPnl, -5.09, "closed loser realized is api value");
}

// 2. 우리 지갑 실측 예상값: 정산 완료 1건 · 1승 0패
eq(settled(summarizeBook([], [closedWinLive])), [1, 1, 0], "live wallet closed win");

// 3. CLOSED 패 — realized_pnl 음수
eq(
  settled(summarizeBook([], [row({ status: "CLOSED", current_price: 0, realized_pnl: -5 })])),
  [1, 0, 1],
  "closed loss by realized pnl",
);

// 4·5. 승패는 realized_pnl 부호 — 최종가와 무관
eq(
  settled(summarizeBook([], [row({ status: "CLOSED", current_price: 0.5, realized_pnl: 2.5 })])),
  [1, 1, 0],
  "closed win by realized pnl",
);
eq(
  settled(summarizeBook([], [row({ status: "CLOSED", current_price: 0.5, realized_pnl: -2.5 })])),
  [1, 0, 1],
  "closed loss by realized pnl sign",
);

// 6. realized_pnl 0 — 건수에만 더한다
eq(
  settled(summarizeBook([], [row({ status: "CLOSED", realized_pnl: 0 })])),
  [1, 0, 0],
  "closed zero pnl counts only",
);

// 7. dedupe — 같은 토큰 행이 중복돼도 한 번만
eq(
  settled(summarizeBook([], [closedWinLive, closedWinLive])),
  [1, 1, 0],
  "dedupe within closed",
);

// 8. dedupe 폴백 키 — token_id 없으면 condition_id+outcome_index, 먼저 온 행 우선
eq(
  settled(
    summarizeBook(
      [],
      [
        row({ token_id: undefined, status: "CLOSED", realized_pnl: -2 }),
        row({ token_id: undefined, status: "CLOSED", realized_pnl: 3 }),
      ],
    ),
  ),
  [1, 0, 1],
  "dedupe by condition fallback, first row wins",
);

// 9. 정산 미상 전파 — CLOSED를 못 읽으면 settled* 전부 null
eq(settled(summarizeBook([], null)), [null, null, null], "closed null propagates");
eq(settled(summarizeBook([], [])), [0, 0, 0], "closed empty is zero counts");

// 10. OPEN 집계 회귀 — CLOSED 행이 섞여도 보유 통계는 OPEN만
{
  const book = summarizeBook(
    [
      row({ token_id: "O1", slug: "o1", current_size: 10, avg_price: 0.5, current_value: 6 }),
      row({ token_id: "O2", slug: "o2", current_size: 10, avg_price: 0.5, current_value: 4 }),
      closedWinLive,
    ],
    [closedWinLive],
  );
  eq(book.openCount, 2, "open count excludes closed");
  eq(book.positionsPnlPct, 0, "open pnl pct excludes closed");
  eq([...book.heldSlugs].sort(), ["o1", "o2"], "held slugs are open only");
}

// 11. 보유 등락은 행의 total_pnl 우선 — 현재가치−원가로 재계산하지 않는다
{
  const book = summarizeBook(
    [row({ token_id: "T1", current_size: 10, avg_price: 0.5, current_value: 6, total_pnl: 3 })],
    [],
  );
  eq(book.positionsPnlPct, 100, "open pnl pct uses api total_pnl");
}

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

// 17. 실현수익 합산 — CLOSED 행 realized_pnl 직독, 건수·승패와 같은 dedupe 기준
{
  // 승 +2.5, 패 −5, CLOSED +0.7016 → −1.7984 → −1.8
  const book = summarizeBook([], [
    row({ token_id: "RW", realized_pnl: 2.5 }),
    row({ token_id: "RL", realized_pnl: -5 }),
    closedWinLive,
  ]);
  eq(settled(book), [3, 2, 1], "realized: mixed counts");
  eq(book.settledRealizedPnl, -1.8, "realized: mixed sum");
  eq(book.settledRealizedExcluded, 0, "realized: none excluded");
}
{
  // 우리 지갑 실측: CLOSED 승 1건의 realized_pnl 0.7016 → 0.7
  eq(
    summarizeBook([], [closedWinLive]).settledRealizedPnl,
    0.7,
    "realized: live wallet closed win",
  );
}
{
  // dedupe — 같은 토큰 행이 중복되면 먼저 온 행의 realized_pnl만 한 번
  const first = row({ token_id: "DUP", status: "CLOSED", realized_pnl: 2.5 });
  const second = row({ token_id: "DUP", status: "CLOSED", realized_pnl: 99 });
  const book = summarizeBook([], [first, second]);
  eq(settled(book), [1, 1, 0], "realized dedupe: counted once");
  eq(book.settledRealizedPnl, 2.5, "realized dedupe: first row wins");
}
{
  // 승패 판정 불가(건수만) 행도 realized_pnl이 있으면 합산한다 — 0도 값이다
  const book = summarizeBook(
    [],
    [row({ status: "CLOSED", current_price: 0.5, realized_pnl: 0 })],
  );
  eq(settled(book), [1, 0, 0], "realized: zero pnl counts only");
  eq(book.settledRealizedPnl, 0, "realized: zero pnl still sums");
  eq(book.settledRealizedExcluded, 0, "realized: zero pnl not excluded");
}
{
  // realized_pnl이 없는 행은 합산에서 빼고 excluded로 센다 — 0으로 만들지 않는다
  const noPnl = row({ status: "CLOSED", current_price: undefined, realized_pnl: undefined });
  const book = summarizeBook([], [noPnl]);
  eq(settled(book), [1, 0, 0], "realized excluded: still counted");
  eq(book.settledRealizedPnl, null, "realized excluded: no fabricated number");
  eq(book.settledRealizedExcluded, 1, "realized excluded: counted as excluded");
}
{
  // 일부만 합산 가능하면 가능한 행만 합산하고 나머지는 excluded
  const book = summarizeBook([], [
    row({ token_id: "GW", realized_pnl: 2 }),
    row({ token_id: "BX", status: "CLOSED", realized_pnl: undefined }),
  ]);
  eq(settled(book), [2, 1, 0], "realized partial: counts");
  eq(book.settledRealizedPnl, 2, "realized partial: sums known only");
  eq(book.settledRealizedExcluded, 1, "realized partial: excluded counted");
}
// 정산 미상이면 실현도 미상, 정산 0건이면 실현 0이 사실이다
eq(summarizeBook([], null).settledRealizedPnl, null, "realized: unknown propagates");
eq(summarizeBook([], []).settledRealizedPnl, 0, "realized: empty settled is zero");

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

// 20b. 루프 자가 감시 — 완료 후 5분 무진행이면 정체, 미기록은 정체 아님
eq(LOOP_WATCH_INTERVAL_MS, 60000, "loopwatch: interval is 60s");
eq(LOOP_STALL_MS, 300000, "loopwatch: stall threshold is 5min");
eq(isLoopStalled(1000, 1000 + 300000), false, "loopwatch: exactly at threshold is alive");
eq(isLoopStalled(1000, 1000 + 300001), true, "loopwatch: past threshold is stalled");
eq(isLoopStalled(0, 999999999), false, "loopwatch: never completed is not stalled");
eq(isLoopStalled(NaN, 1000), false, "loopwatch: NaN is not stalled");

// 20c. 사이클 오류 에스컬레이션 — 연속 10회 + 쿨다운 10분이 게이트
eq(CYCLE_ERROR_RELOAD_THRESHOLD, 10, "selfreload: threshold is 10 errors");
eq(SELF_RELOAD_COOLDOWN_MS, 600000, "selfreload: cooldown is 10min");
eq(shouldSelfReload(9, 0, 10000000), false, "selfreload: below threshold");
eq(shouldSelfReload(10, 0, 10000000), true, "selfreload: at threshold, no prior reload");
eq(shouldSelfReload(15, 0, 10000000), true, "selfreload: above threshold");
eq(shouldSelfReload(10, 10000000 - 599999, 10000000), false, "selfreload: inside cooldown");
eq(shouldSelfReload(10, 10000000 - 600000, 10000000), true, "selfreload: cooldown elapsed");
eq(shouldSelfReload(NaN, 0, 10000000), false, "selfreload: NaN errors");
eq(shouldSelfReload(10, NaN, 10000000), true, "selfreload: NaN last reload treated as none");

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
// 기온 전용 폭(0.15) — 경계 발화, 메인 폭으로는 미발화인 구간
eq(stopLossVerdict(0.90, 0.75, 0.15), "triggered", "stoploss temp: boundary triggers at -0.15");
eq(stopLossVerdict(0.90, 0.76, 0.15), "holding", "stoploss temp: above -0.15 line holds");
eq(stopLossVerdict(0.90, 0.70, 0.15), "triggered", "stoploss temp: -0.20 triggers temp drop");
eq(stopLossVerdict(0.90, 0.70), "holding", "stoploss temp: -0.20 holds at main drop");
eq(stopLossVerdict(0.90, 0.97, 0.15), "decided", "stoploss temp: decided wins over drop");
// 결정 구간 경계 설정값
eq(stopLossVerdict(0.90, 0.80, 0.15, 0.80, 0.03), "decided", "stoploss cfg: custom decided high");
eq(stopLossVerdict(0.90, 0.74, 0.15, 0.97, 0.03), "triggered", "stoploss cfg: default decided lets drop fire");

// 22. outcome 인덱스 — Gamma 이름 대조 1순위, outcome_index 폴백
eq(pickOutcomeIndex(["Yes", "No"], { outcome: "No" }), 1, "index: name match");
eq(pickOutcomeIndex(["Yes", "No"], { outcome: "yes" }), 0, "index: name case-insensitive");
eq(pickOutcomeIndex(["Yes", "No"], { outcome: "Maybe", outcome_index: 1 }), 1, "index: fallback to outcome_index");
eq(pickOutcomeIndex(["Yes", "No"], { outcome_index: 0 }), 0, "index: outcome_index only");
eq(pickOutcomeIndex(["Yes", "No"], {}), -1, "index: nothing usable");
eq(pickOutcomeIndex([], { outcome_index: 2 }), 2, "index: fallback without outcomes list");
eq(pickOutcomeIndex(["Yes", "No"], { outcome_index: 1.5 }), -1, "index: non-integer rejected");
eq(pickOutcomeIndex(["Yes", "No"], { outcome_index: -1 }), -1, "index: negative rejected");

// 23. 손절 건수 분류 — CLOSED 행과 기록의 토큰 교집합만 센다.
// 기록의 금액·승패는 표시로 가져오지 않는다 (2026-11-24 API 표시 교체).
{
  // 손절로 판 토큰이 승으로 정산돼도 표시는 API 행(+55 → 승)을 따르고
  // 손절 건수에만 잡힌다
  const closedRow = row({
    token_id: "SL1",
    status: "CLOSED",
    current_price: 1,
    realized_pnl: 55,
    entry_cost_usdc: 100,
    current_value: 0,
  });
  const book = summarizeBook([], [closedRow], [
    { token_id: "SL1", realized_pnl: -30 },
  ]);
  eq(settled(book), [1, 1, 0], "stoploss settled: api row verdict wins");
  eq(book.settledRealizedPnl, 55, "stoploss settled: api row pnl wins");
  eq(book.settledStopLoss, 1, "stoploss settled: counted as stoploss");
}
{
  // CLOSED 행이 없으면 손절 기록만으로는 정산에 잡히지 않는다
  const book = summarizeBook([], [], [{ token_id: "SL2", realized_pnl: -12.5 }]);
  eq(settled(book), [0, 0, 0], "stoploss settled: record alone does not count");
  eq(book.settledRealizedPnl, 0, "stoploss settled: record alone no pnl");
  eq(book.settledStopLoss, 0, "stoploss settled: record alone not flagged");
}
{
  // 기록이 없으면 손절 건수는 0이다
  const book = summarizeBook([], [row({ token_id: "W1", realized_pnl: 1.5 })], []);
  eq(settled(book), [1, 1, 0], "stoploss settled: no records regression");
  eq(book.settledStopLoss, 0, "stoploss settled: zero without records");
  eq(summarizeBook([], []).settledStopLoss, 0, "stoploss settled: omitted arg is zero");
}
{
  // 깨진 기록은 건너뛴다 — 토큰 없는 기록이 분류를 오염시키지 않는다
  const book = summarizeBook([], [row({ token_id: "OK1", realized_pnl: -5 })], [
    { realized_pnl: -5 },
    { token_id: "OK1", realized_pnl: -5 },
    null,
  ]);
  eq(settled(book), [1, 0, 1], "stoploss settled: malformed records skipped");
  eq(book.settledStopLoss, 1, "stoploss settled: only valid record flagged");
}
{
  // 정산 미상이면 손절 건수도 미상이다
  eq(summarizeBook([], null, []).settledStopLoss, null, "stoploss settled: unknown propagates");
}

// 23b. 가드 (2026-11-24 정산 손익 차이 분석) — 실측 8건 픽스처.
// 내부 기록에 틀린 실현손익이 있어도 표시는 API 행만으로 만들어져야 한다.
// 실측: 앱 내부 기록 합계 −11.10 (손절 2건 총액 계산 −4.43·−3.38,
// 수동 매도 1건 +4.06) vs API realized_pnl 합계 −12.13.
{
  const closedLive = [
    { token_id: "T675", status: "CLOSED", realized_pnl: 0.7016 },
    { token_id: "T65", status: "CLOSED", realized_pnl: 2.1378 },
    { token_id: "T625", status: "CLOSED", realized_pnl: -4.9612 },
    { token_id: "TGENG", status: "CLOSED", realized_pnl: -5.0924 },
    { token_id: "TBILI", status: "CLOSED", realized_pnl: -5.0952 },
    { token_id: "T725", status: "CLOSED", realized_pnl: 3.7108 },
    { token_id: "T60", status: "CLOSED", realized_pnl: -3.5368 },
    { token_id: "THEL", status: "CLOSED", realized_pnl: 0.0048 },
  ];
  // 내부 기록 — 손절 2건의 realized_pnl은 수수료 미반영 총액 계산값(틀림),
  // 수동 매도 1건도 마찬가지. 표시에 새면 합계가 −11.10으로 어긋난다.
  const wrongRecords = [
    { token_id: "T625", realized_pnl: -4.43 },
    { token_id: "T60", realized_pnl: -3.38 },
  ];
  const book = summarizeBook([], closedLive, wrongRecords);
  eq(settled(book), [8, 4, 4], "guard live8: counts from api rows");
  eq(book.settledRealizedPnl, -12.13, "guard live8: realized is api sum, not record sum");
  eq(book.settledStopLoss, 2, "guard live8: stoploss count by token intersection");
  eq(book.settledRealizedExcluded, 0, "guard live8: none excluded");
}
{
  // CLOSED 조회 실패(null)면 내부 기록이 있어도 정산 표시는 미상이다 —
  // 내부 계산값으로 조용히 대체하지 않는다.
  const book = summarizeBook([], null, [{ token_id: "T625", realized_pnl: -4.43 }]);
  eq(settled(book), [null, null, null], "guard: closed null stays unknown despite records");
  eq(book.settledRealizedPnl, null, "guard: closed null realized stays null");
  eq(book.settledStopLoss, null, "guard: closed null stoploss stays null");
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

  // v0.3.39 감사 C — pending status 승격: LOST로 먼저 수집된 토큰이
  // 나중에 CLOSED 승으로 오면 승자 판정에 잡혀야 한다 (강등은 없음).
  {
    const cid = `0x${"cc".repeat(32)}`;
    const lostRow = { condition_id: cid, token_id: "t1", slug: "s1", current_value: 0 };
    const c1 = collectRedeemPending([], new Set(), [], [lostRow], []);
    eq(c1.pending.length, 1, "auditC: cycle1 collects lost row");
    eq(c1.pending[0].status, "REDEEMABLE_LOST", "auditC: cycle1 status is LOST");
    eq(planRedeems(c1.pending).targets.length, 0, "auditC: cycle1 no target yet");
    const closedWin = {
      condition_id: cid, token_id: "t1", slug: "s1",
      current_price: 1, current_value: 0, entry_cost_usdc: 10, realized_pnl: 2,
    };
    const c2 = collectRedeemPending(c1.pending, new Set(), [], [], [closedWin]);
    eq(c2.pending[0].status, "REDEEMABLE", "auditC: status promoted LOST → REDEEMABLE");
    eq(c2.pending[0].current_value, 12, "auditC: value updated to closed estimate");
    const plan2 = planRedeems(c2.pending);
    eq(plan2.targets.length, 1, "auditC: promoted entry becomes redeem target");
    eq(plan2.worthless.length, 0, "auditC: promoted entry not worthless");
    const c3 = collectRedeemPending(c2.pending, new Set(), [], [lostRow], []);
    eq(c3.pending[0].status, "REDEEMABLE", "auditC: later lost row does not demote");
  }

  // v0.3.39 경화 — worthless streak 가드는 runRedeems가 정산 조회 성공
  // 여부를 보고 결정한다 (네이티브 NativeCycle과 같은 가드).
  {
    const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
    const rrSrc = src.slice(src.indexOf("async function runRedeems"));
    eq(
      rrSrc.includes("const settlementKnown = state.lastWinRows != null"),
      true,
      "hardening: runRedeems computes settlementKnown",
    );
    eq(
      rrSrc.includes("if (plan.worthless.length && settlementKnown)"),
      true,
      "hardening: worthless streak gated on settlement evidence",
    );
  }

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

// 28b. 만기 창 경계 (기본 14일, v0.3.64) — 오늘 마감은 제외, 잔여 14일은
// 포함, 15일은 제외. 창을 45로 넓히면 15일이 들어오는 것도 함께 고정해
// 픽스처가 창 값을 실제로 타는지 확인한다.
{
  const day = 86400_000;
  const isoIn = (days) => new Date(Date.now() + days * day).toISOString();
  const mkt = (slug, days) => ({
    active: true,
    closed: false,
    slug,
    endDate: isoIn(days),
    volume: 20000,
    outcomePrices: JSON.stringify([0.75, 0.25]),
  });
  const markets = [mkt("win-today", 0), mkt("win-1d", 1), mkt("win-14d", 14), mkt("win-15d", 15)];
  const out = scanCandidates(markets, new Set(), new Set());
  eq(
    out.map((c) => c.slug).sort(),
    ["win-14d", "win-1d"],
    "window: today excluded, 14d included, 15d excluded at default 14",
  );
  const wide = scanCandidates(markets, new Set(), new Set(), null,
    { ...parseTradeSettings(null), endWindowDays: 45 });
  eq(
    wide.map((c) => c.slug).sort(),
    ["win-14d", "win-15d", "win-1d"],
    "window: 15d included when window widened to 45",
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

// 37. 부수4 — 손절 원장 아카이브: 캡을 넘어도 기록이 남고 분류가 유지된다
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
  const closedRows = recs.map((r) => ({ token_id: r.token_id, status: "CLOSED", realized_pnl: -1 }));
  const book = summarizeBook([], closedRows, recs);
  eq(book.settledCount, total, "archive: settled count from closed rows");
  eq(book.settledStopLoss, total, "archive: all classified as stoploss");
  eq(book.settledRealizedPnl, -total, "archive: realized sum from closed rows");
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
  // 우승 아웃라이트: sportsMarketType이 비어도 sports feeType이면 제외
  const outStats2 = {};
  const outright = scanCandidates([
    mk({ slug: "outright-1", endDate: soon, feeType: "sports_fees_v3" }),
    mk({ slug: "politics-1", endDate: soon, feeType: "politics_fees" }),
  ], new Set(), new Set(), outStats2);
  eq(outright.map((c) => c.slug), ["politics-1"], "sports: outright excluded by feeType, politics kept");
  eq(outStats2.sports, 1, "sports: outright counted as sports");
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
  eq(html.includes("cycle_error_streak"), true, "guard: auto-report on cycle error streak");
  eq(html.includes("ERROR_STREAK_LIMIT = 5"), true, "guard: error streak threshold is 5");
  eq(html.includes("countErrorStreak"), true, "guard: error streak counter present");
  eq(src.includes("reconcileBoughtOnce"), true, "guard: bought reconciliation wired before scan");
  eq(src.includes("next_cursor"), true, "guard: activity paging uses cursor, not offset");
  eq(src.includes("기매수 정화 실패"), true, "guard: reconcile failure is logged, not silent");
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
  // 네이티브 전환 — 엔진 호스트 WebView가 없어 렌더러 사망 자체가 없고,
  // JS 엔진과 네이티브가 동시에 도는 경로도 구조적으로 없다.
  eq(svc.includes("NativeEngine"), true, "guard: engine runs on native engine thread");
  eq(svc.includes("new WebView"), false, "guard: service never creates an engine webview (no dual execution)");
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

// ── v0.3.24 전면 감사 가드 ──
{
  // judgeFill: 전송 실패(status 없음·0)는 결과 불명, 명시 거부는 error
  eq(judgeFill({ error: "Network Error" }).kind, "unknown", "audit: transport error is unknown");
  eq(judgeFill({ error: "Network Error", status: 0 }).kind, "unknown", "audit: status 0 is unknown");
  eq(judgeFill({ error: "forbidden", status: 403 }).kind, "error", "audit: http error stays error");
  eq(judgeFill({ success: false }).kind, "rejected", "audit: rejected unchanged");
  eq(judgeFill({ success: true, status: "matched" }).filled, true, "audit: filled unchanged");

  // scanCandidates: NaN 거래량·가격은 가드를 통과하지 못하고 탈락한다
  const soon = new Date(Date.now() + 10 * 86400_000).toISOString();
  const mk = (over) => ({
    active: true, closed: false, acceptingOrders: true, archived: false,
    slug: "nan-x", endDate: soon, volume: 50000,
    outcomePrices: "[0.75, 0.25]", ...over,
  });
  eq(scanCandidates([mk({ volume: "abc" })], new Set(), new Set()).length, 0,
    "audit: NaN volume dropped");
  eq(scanCandidates([mk({ outcomePrices: "[\"x\", 0.25]" })], new Set(), new Set()).length, 0,
    "audit: NaN price dropped");
  eq(scanCandidates([mk({})], new Set(), new Set()).length, 1,
    "audit: sane market still passes");

  // summarizeBook: status 누락 + 수량 있는 행은 보유로 본다
  const noStatus = row({ token_id: "NS1", status: undefined, current_size: 5 });
  eq(summarizeBook([noStatus], []).openCount, 1, "audit: status-less row with size is open");
  const redeemable = row({ token_id: "NS2", status: "REDEEMABLE", current_size: 5 });
  eq(summarizeBook([redeemable], []).openCount, 0, "audit: redeemable row is not open");

  // summarizeBook: CLOSED 패자 행은 최종가가 아니라 realized_pnl 부호로 가른다
  const loserRow = row({ token_id: "U1", status: "CLOSED", current_price: 1, realized_pnl: -5, entry_cost_usdc: 5, current_value: 0 });
  const ub = summarizeBook([], [loserRow]);
  eq([ub.settledCount, ub.settledWins, ub.settledLosses], [1, 0, 1],
    "audit: closed loser judged by realized pnl, not final price");
  eq(ub.settledRealizedPnl, -5, "audit: loser realized is api value");

  // summarizeBook: 내부 기록은 CLOSED 행의 판정을 바꾸지 않는다
  const closedRow2 = row({ token_id: "AT1", status: "CLOSED", current_price: 1, realized_pnl: -4.5 });
  const ab = summarizeBook([], [closedRow2], [{ token_id: "AT1", realized_pnl: 9 }]);
  eq([ab.settledWins, ab.settledLosses], [0, 1], "audit: record does not flip api verdict");
  const ab2 = summarizeBook([], [closedRow2]);
  eq([ab2.settledWins, ab2.settledLosses], [0, 1], "audit: same verdict without record");

  // summarizeBook: 손절 건수는 CLOSED 행과 기록 토큰의 교집합으로만 센다
  const slRow = row({ token_id: "SL1", status: "CLOSED", current_price: 1, realized_pnl: 4 });
  const sb = summarizeBook([], [slRow], [{ token_id: "SL1", realized_pnl: -3 }]);
  eq([sb.settledWins, sb.settledLosses, sb.settledStopLoss], [1, 0, 1],
    "audit: stoploss counted by intersection, verdict from api row");

  // summarizeBook: 손절 원장 slug가 반환에 실린다 (재매수 차단 합류용)
  const sb2 = summarizeBook([], [], [{ token_id: "SL2", slug: "sold-mkt", realized_pnl: -1 }]);
  eq(sb2.stopLossSlugs.has("sold-mkt"), true, "audit: stopLossSlugs carries sold slugs");

  // boughtCorrupted: 손상 원문과 정상 기록을 구분한다
  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  store.set("bm_bought", '["a","b"]');
  eq(boughtCorrupted(), false, "audit: intact bought is not corrupted");
  store.set("bm_bought", "{broken");
  eq(boughtCorrupted(), true, "audit: broken json is corrupted");
  store.set("bm_bought", '{"not":"array"}');
  eq(boughtCorrupted(), true, "audit: non-array json is corrupted");
  store.delete("bm_bought");
  eq(boughtCorrupted(), false, "audit: missing bought is not corrupted");
}

// 39. 종목 목록 캐시 가드 — 스냅샷 캐시를 거쳐도 positions가 살아남는다
// (v0.3.31~32 실기기 결함: snapshotFromCache가 positions를 버려 뷰어의
// 보유 종목 카드·수동 매도가 어떤 버전에서도 뜰 수 없었다)
{
  const row = {
    slug: "will-btc-hit-100k",
    token_id: "tok-yes-1",
    title: "Will BTC hit 100k?",
    outcome: "Yes",
    avg_price: 0.72,
    current_price: 0.81,
    current_size: 6.94,
    current_value: 5.62,
    fee_rate: 0.04,
    end_date: "2026-11-30",
  };
  // ① 네이티브가 쓴 캐시 원문(JSON)을 파서가 그대로 통과시킨다
  const fromNative = snapshotFromCache(
    JSON.stringify({ cash: 12.5, equity: 40.1, openCount: 1, positions: [row] }),
  );
  eq(fromNative.positions.length, 1, "positions: parsed cache keeps rows");
  eq(fromNative.positions[0].slug, "will-btc-hit-100k", "positions: slug kept");
  eq(fromNative.positions[0].token_id, "tok-yes-1", "positions: token_id kept (sell path)");
  eq(fromNative.positions[0].title, "Will BTC hit 100k?", "positions: title kept");
  eq(fromNative.positions[0].outcome, "Yes", "positions: outcome kept");
  eq(fromNative.positions[0].avg_price, 0.72, "positions: avg_price kept");
  eq(fromNative.positions[0].current_price, 0.81, "positions: current_price kept");
  eq(fromNative.positions[0].current_size, 6.94, "positions: current_size kept");
  eq(fromNative.positions[0].current_value, 5.62, "positions: current_value kept");
  eq(fromNative.positions[0].fee_rate, 0.04, "positions: fee_rate kept");
  eq(fromNative.positions[0].end_date, "2026-11-30", "positions: end_date kept");

  // 라운드트립 — 기록(snapshotCacheOf)도 읽기도 positions를 유지한다
  const roundTrip = snapshotFromCache(
    JSON.stringify(
      snapshotCacheOf({
        cash: 12.5, positionsValue: 27.6, equity: 40.1, openCount: 1,
        settledCount: 0, settledWins: 0, settledLosses: 0,
        settledRealizedPnl: 0, positionsPnlPct: 1.2,
        heldSlugs: new Set(["will-btc-hit-100k"]),
        cashFloor: 12.03, available: 0, bet: 5, slots: 0,
        positions: [row],
      }),
    ),
  );
  eq(roundTrip.positions.length, 1, "positions: round trip keeps rows");
  eq(roundTrip.positions[0].token_id, "tok-yes-1", "positions: round trip token_id");

  // ② positions 없는 옛 캐시는 빈 배열로 안전하게 떨어진다
  const legacy = snapshotFromCache('{"cash":10,"equity":10}');
  eq(Array.isArray(legacy.positions), true, "positions: missing field is array");
  eq(legacy.positions.length, 0, "positions: missing field is empty");

  // 형식이 깨진 행은 제외하고, 깨진 숫자는 null로 떨어진다
  const messy = snapshotFromCache(
    JSON.stringify({
      cash: 10, equity: 10,
      positions: [
        null, "junk", 42, [1, 2], {},
        { outcome: "Yes" },
        { slug: "ok-mkt", token_id: "t2", avg_price: "0.7", current_size: null },
      ],
    }),
  );
  eq(messy.positions.length, 1, "positions: malformed rows dropped");
  eq(messy.positions[0].slug, "ok-mkt", "positions: surviving row slug");
  eq(messy.positions[0].title, "ok-mkt", "positions: title falls back to slug");
  eq(messy.positions[0].avg_price, null, "positions: non-numeric price is null");
  eq(messy.positions[0].current_size, null, "positions: null size stays null");

  // ③ 뷰어 구조 가드 — posCard는 처음부터 숨김이 아니고, renderPositions는
  // 카드를 숨기지 않으며 빈 상태를 표시한다
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  eq(html.includes('<div class="card" id="posCard">'), true, "positions: card not hidden initially");
  const renderSrc = html.slice(html.indexOf("function renderPositions"));
  const renderBody = renderSrc.slice(0, renderSrc.indexOf("\n    function "));
  eq(renderBody.includes("card.classList.add('hidden')"), false, "positions: render never hides card");
  eq(renderBody.includes("보유 종목 없음"), true, "positions: render shows empty state");
  eq(renderBody.includes("openSellPopup(p)"), true, "positions: sell button wired per row");
}

// 40. open 판정 패리티 가드 — JS snapshot()·폴백이 장부와 같은 단일 기준을
// 쓴다 (v0.3.33 감사: 종전에는 status === "OPEN" 필터라 status 없는 보유
// 행이 종목 목록·손절 대상에서 빠졌다)
{
  eq(isOpenRow({ status: "OPEN", current_size: 0 }), true, "open: OPEN status");
  eq(isOpenRow({ current_size: 5 }), true, "open: no status with size");
  eq(isOpenRow({ status: "", current_size: 5 }), true, "open: empty status with size");
  eq(isOpenRow({ current_size: 0 }), false, "open: no status no size");
  eq(isOpenRow({ current_size: "x" }), false, "open: no status bad size");
  eq(isOpenRow({ status: "CLOSED", current_size: 5 }), false, "open: CLOSED excluded");
  eq(isOpenRow({ status: "REDEEMABLE", current_size: 5 }), false, "open: REDEEMABLE excluded");
  eq(isOpenRow(null), false, "open: null row");

  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  eq(src.includes("positions.filter(isOpenRow)"), true, "open: snapshot uses single criterion");
  eq(src.includes("rows.filter(isOpenRow)"), true, "open: fallback uses single criterion");
  eq(src.includes('filter((p) => p.status === "OPEN")'), false, "open: legacy filter gone");
  const snapSrc = src.slice(src.indexOf("async function snapshot("), src.indexOf("async function verifyCandidate"));
  eq(snapSrc.includes("positions: openRows.map"), true, "open: snapshot fills snap.positions");
}

// 41. 수동 매도 기록 표시 독립 가드 — 수동 매도 기록은 표시 경로에서
// 빠졌다 (2026-11-24 API 표시 교체). 기록이 있어도 표시는 CLOSED 행의
// realized_pnl을 따르고, 기록 자체는 감사 원장으로 남는다.
{
  const closedRow = {
    token_id: "mtok", slug: "m-mkt", status: "CLOSED",
    current_price: 1, current_size: 0, current_value: 0, realized_pnl: 2.5,
  };
  const book = summarizeBook([], [closedRow]);
  eq(book.settledCount, 1, "manual-book: settled count");
  eq(book.settledWins, 1, "manual-book: judged by api realized pnl");
  eq(book.settledLosses, 0, "manual-book: record does not flip verdict");
  eq(book.settledRealizedPnl, 2.5, "manual-book: realized is api value");
  eq(book.settledStopLoss, 0, "manual-book: not counted as stop loss");

  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  const snapSrc = src.slice(src.indexOf("async function snapshot("), src.indexOf("async function verifyCandidate"));
  eq(snapSrc.includes("manualSellRecords()"), false, "manual-book: snapshot no longer wires records");
  eq(snapSrc.includes("stopLossAttemptedTokens()"), false, "manual-book: snapshot no longer wires attempted");
}

// 42. 캡 회전 아카이브 읽기 가드 — 수동 매도 원장·시도가 아카이브로
// 회전돼도 감사 원장 읽기에서 사라지지 않고, 손절 시도 slug·리딤 done
// 아카이브도 같은 입력으로 읽힌다 (v0.3.35 감사 축 2·3)
{
  const ls = new Map();
  globalThis.localStorage = {
    getItem: (k) => (ls.has(k) ? ls.get(k) : null),
    setItem: (k, v) => ls.set(k, String(v)),
    removeItem: (k) => ls.delete(k),
  };
  if (globalThis.window) delete globalThis.window.AndroidBridge;
  durableWrite("bm_manual_sell_archive", JSON.stringify([
    { token_id: "atok", slug: "a-mkt", realized_pnl: -3 },
    { token_id: "bad", realized_pnl: "x" },
  ]));
  durableWrite("bm_manual_sell", JSON.stringify([
    { token_id: "rtok", slug: "r-mkt", realized_pnl: 1.5 },
  ]));
  const recs = manualSellRecords();
  eq(recs.length, 2, "manual-archive: archive+recent merged, bad row filtered");
  eq(recs[0].token_id, "atok", "manual-archive: archive comes first");
  durableWrite("bm_manual_sell_attempted_archive", JSON.stringify(["atok"]));
  durableWrite("bm_manual_sell_attempted", JSON.stringify(["rtok"]));
  const attempted = manualSellAttemptedTokens();
  eq(attempted.has("atok") && attempted.has("rtok") && attempted.size === 2, true,
    "manual-archive: attempted merged");
  durableWrite("bm_stoploss_attempted_slugs", JSON.stringify(["s-mkt"]));
  eq(stopLossAttemptedSlugs().has("s-mkt"), true, "attempt-slugs: read back");

  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  eq(src.includes("stopLossAttemptedSlugs()) boughtAll.add(s)"), true,
    "attempt-slugs: buy exclusion wires attempted slugs");
  eq(src.includes("LS.redeemDoneArchive"), true, "done-archive: wired");
}

// 43. 기온 손절은 태그 방식 — 슬러그 단독·태그 단독 제외 가드가 되돌아오지
// 않게 소스 가드로 고정하고, 매수 기록 전 경로가 전략을 넘기는지 본다.
// (기온 전용 폭 배선 자체는 temp 테스트의 구조 가드가 본다.)
{
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  eq(src.includes("if (isTempNoSlug(slug)) return false;"), false,
    "temp-tag: no slug-only stop-loss guard");
  eq(
    src.includes('if (isTempNoSlug(slug) && boughtStrategyOf(slug) === "temp_no") return false;'),
    false,
    "temp-tag: no tag-based stop-loss exemption left",
  );
  eq(src.split("markBought(cand.slug, cand.strategy)").length - 1, 4,
    "temp-tag: all four buy record paths pass strategy");
  eq(src.includes("markBought(cand.slug);"), false,
    "temp-tag: no untagged buy record path");
}

// 44. 매매 설정 — 기본값은 기존 하드코딩 상수와 같고(동작 불변),
// 범위 밖 값은 필드 단위로, 어긋난 쌍은 쌍째로 기본값으로 되돌린다.
eq(DEFAULT_TRADE_SETTINGS, {
  bandLo: BAND_LO, bandHi: BAND_HI,
  priorityBandLo: PRIORITY_BAND_LO, priorityBandHi: PRIORITY_BAND_HI,
  volumeMin: VOLUME_MIN, endWindowDays: END_WINDOW_DAYS,
  spreadMax: SPREAD_MAX, askDepthMinUsd: ASK_DEPTH_MIN_USD,
  slippageCap: SLIPPAGE_CAP, snapshotMatchTol: SNAPSHOT_MATCH_TOL,
  askAboveSignal: ASK_ABOVE_SIGNAL,
  betUsd: BET_USD, cashFloorPct: CASH_FLOOR_PCT,
  reserveUsd: RESERVE_USD, maxPositions: MAX_POSITIONS, verifyCap: VERIFY_CAP,
  stopDrop: STOP_DROP, tempStopDrop: TEMP_STOP_DROP,
  decidedHigh: DECIDED_HIGH, decidedLow: DECIDED_LOW,
  orderTestRounds: ORDER_TEST_DEFAULT_ROUNDS,
}, "trade settings: defaults equal legacy constants");
eq(parseTradeSettings(null), DEFAULT_TRADE_SETTINGS, "trade settings: null gives defaults");
eq(parseTradeSettings("not json"), DEFAULT_TRADE_SETTINGS, "trade settings: bad json gives defaults");
eq(parseTradeSettings({}), DEFAULT_TRADE_SETTINGS, "trade settings: empty gives defaults");
{
  const c = parseTradeSettings({ bandLo: 0.5, betUsd: 7, maxPositions: 10, verifyCap: 5 });
  eq([c.bandLo, c.betUsd, c.maxPositions, c.verifyCap], [0.5, 7, 10, 5],
    "trade settings: custom values kept");
  eq(c.bandHi, BAND_HI, "trade settings: untouched fields stay default");
  // 비율 키는 더 이상 읽지 않는다 — 저장된 옛 키가 있어도 금액 기본값이다
  eq(parseTradeSettings({ betPct: 0.02, betMinUsd: 3 }).betUsd, BET_USD,
    "trade settings: legacy ratio keys ignored");
}
eq(parseTradeSettings({ bandLo: 1.5 }).bandLo, BAND_LO, "trade settings: bandLo above 1 falls back");
eq(parseTradeSettings({ bandLo: 0.95, bandHi: 0.9 }).bandLo, BAND_LO, "trade settings: band pair reset (lo)");
eq(parseTradeSettings({ bandLo: 0.95, bandHi: 0.9 }).bandHi, BAND_HI, "trade settings: band pair reset (hi)");
eq(parseTradeSettings({ priorityBandLo: 0.85, priorityBandHi: 0.8 }).priorityBandLo, PRIORITY_BAND_LO,
  "trade settings: priority pair reset");
eq(parseTradeSettings({ decidedLow: 0.98, decidedHigh: 0.97 }).decidedHigh, DECIDED_HIGH,
  "trade settings: decided pair reset");
eq(parseTradeSettings({ maxPositions: 0 }).maxPositions, MAX_POSITIONS, "trade settings: maxPositions 0 falls back");
eq(parseTradeSettings({ maxPositions: 3.5 }).maxPositions, MAX_POSITIONS, "trade settings: fractional maxPositions falls back");
eq(parseTradeSettings({ verifyCap: "30" }).verifyCap, 30, "trade settings: numeric string coerced");
eq(parseTradeSettings({ endWindowDays: 0 }).endWindowDays, END_WINDOW_DAYS, "trade settings: endWindowDays 0 falls back");
eq(parseTradeSettings({ stopDrop: 0 }).stopDrop, STOP_DROP, "trade settings: stopDrop 0 falls back");
eq(parseTradeSettings({ tempStopDrop: 0.2 }).tempStopDrop, 0.2, "trade settings: tempStopDrop kept");
// 커스텀 설정이 실제 판정을 바꾸는지 — 사이징과 스캔으로 확인한다.
{
  const cfg = parseTradeSettings({ betUsd: 7, maxPositions: 2 });
  eq(sizing(1000, 1000, 0, cfg).bet, 7, "trade settings: sizing uses custom betUsd");
  eq(sizing(100000, 100000, 0, cfg).slots, 2, "trade settings: sizing uses custom maxPositions");
  eq(sizing(1000, 1000, 0).bet, 5, "trade settings: default sizing unchanged");
  // 금액 방식 — 자본이 변해도 건당 금액은 설정값 그대로다
  eq(sizing(1000, 100, 0).bet, sizing(1000, 100000, 0).bet,
    "trade settings: bet does not scale with equity");
  eq(sizing(1000, 100, 0, cfg).bet, 7, "trade settings: custom bet does not scale with equity");
  const mkt = (slug, price, vol) => ({
    slug, question: slug, active: true, closed: false, acceptingOrders: true,
    endDate: new Date(Date.now() + 10 * 86400_000).toISOString(),
    volume: vol, outcomePrices: JSON.stringify([price, 1 - price]),
  });
  const wide = parseTradeSettings({ bandLo: 0.5, bandHi: 0.95, volumeMin: 500 });
  eq(scanCandidates([mkt("a", 0.55, 600)], new Set(), new Set(), null, wide).length, 1,
    "trade settings: scan uses custom band+volume");
  eq(scanCandidates([mkt("a", 0.55, 600)], new Set(), new Set()).length, 0,
    "trade settings: default scan still excludes 0.55/low volume");
}

// 45. v0.3.38 감사 수리 가드 — 설정 파서 타입 엄격화, decided 쌍 교차
// 가드, bet<=0 슬롯 차단, 사이클당 설정 1회 파싱, 리딤 pending 아카이브.
{
  // 손상 타입은 Number() 강제변환(null→0, true→1)이 아니라 기본값으로 떨어진다
  eq(parseTradeSettings({ cashFloorPct: null }).cashFloorPct, CASH_FLOOR_PCT,
    "audit1: cashFloorPct null falls back");
  eq(parseTradeSettings({ betUsd: true }).betUsd, BET_USD,
    "audit1: betUsd true falls back");
  eq(parseTradeSettings({ volumeMin: true }).volumeMin, VOLUME_MIN,
    "audit1: volumeMin true falls back");
  eq(parseTradeSettings({ askDepthMinUsd: null }).askDepthMinUsd, ASK_DEPTH_MIN_USD,
    "audit1: askDepthMinUsd null falls back");
  eq(parseTradeSettings({ bandLo: "" }).bandLo, BAND_LO,
    "audit1: bandLo empty string falls back");
  eq(parseTradeSettings({ bandLo: [] }).bandLo, BAND_LO,
    "audit1: bandLo array falls back");
  eq(parseTradeSettings({ bandLo: {} }).bandLo, BAND_LO,
    "audit1: bandLo object falls back");
  eq(parseTradeSettings({ betUsd: " 7 " }).betUsd, 7,
    "audit1: numeric string with spaces kept");
  // decided 쌍이 0.5를 사이에 두지 않으면 순서가 맞아도 쌍째로 기본값
  eq(parseTradeSettings({ decidedLow: 0.90, decidedHigh: 0.95 }).decidedLow, DECIDED_LOW,
    "audit4: decided pair above 0.5 resets (low)");
  eq(parseTradeSettings({ decidedLow: 0.90, decidedHigh: 0.95 }).decidedHigh, DECIDED_HIGH,
    "audit4: decided pair above 0.5 resets (high)");
  eq(parseTradeSettings({ decidedLow: 0.4, decidedHigh: 0.45 }).decidedHigh, DECIDED_HIGH,
    "audit4: decided pair below 0.5 resets");
  eq(parseTradeSettings({ decidedLow: 0.2, decidedHigh: 0.8 }).decidedLow, 0.2,
    "audit4: decided pair straddling 0.5 kept");
  // bet이 0이면 슬롯은 0 — 무한대 나눗셈으로 상한까지 차지 않는다
  const zeroBetCfg = parseTradeSettings({ betUsd: 0 });
  const zs = sizing(100000, 1, 0, zeroBetCfg);
  eq(zs.bet, 0, "audit3: bet rounds to 0");
  eq(zs.slots, 0, "audit3: bet 0 gives slots 0");
  // 사이클당 설정 1회 파싱 — runCycle이 파싱한 설정을 snapshot 전부에 넘긴다
  const src = readFileSync(new URL("../src/engine.js", import.meta.url), "utf8");
  eq(src.split("await snapshot(tradeCfg)").length - 1, 3,
    "audit2: runCycle passes cycle settings to every snapshot");
  eq(src.includes("cfg ?? parseTradeSettings(durableRead(LS.settings))"), true,
    "audit2: snapshot parses only when called outside a cycle");
  // 리딤 pending 절단 아카이브 — 300건을 넘겨도 id가 사라지지 않고,
  // 해소된 id는 아카이브에서도 제거된다
  const ls = new Map();
  globalThis.localStorage = {
    getItem: (k) => (ls.has(k) ? ls.get(k) : null),
    setItem: (k, v) => ls.set(k, String(v)),
    removeItem: (k) => ls.delete(k),
  };
  if (globalThis.window) delete globalThis.window.AndroidBridge;
  const entries = [];
  for (let i = 0; i < 305; i += 1) {
    entries.push({ condition_id: `c${i}`, token_id: `t${i}`, current_value: 1 });
  }
  saveRedeemPending(entries);
  eq(JSON.parse(ls.get("bm_redeem_pending")).length, 300,
    "audit7: pending body capped at 300");
  eq(JSON.parse(ls.get("bm_redeem_pending_archive")).length, 5,
    "audit7: overflow archived, not dropped");
  const merged = loadRedeemPending();
  eq(merged.length, 305, "audit7: load merges archive back");
  eq(merged[0].condition_id, "c0", "audit7: oldest entry recovered first");
  saveRedeemPending(merged.filter((e) => e.condition_id !== "c0"));
  eq(loadRedeemPending().length, 304, "audit7: resolved entry removed everywhere");
  eq(JSON.parse(ls.get("bm_redeem_pending_archive")).some((e) => e.condition_id === "c0"), false,
    "audit7: resolved id removed from archive");
}

// 46. 설정 화면 UX (2026-11-23 동호 확정) — 자동 초기화 제거, 되돌리기
// 버튼, 숫자 직접 입력, 저장 버튼 활성 상태. 뷰어 인라인 스크립트라
// 구조 가드로 고정한다.
{
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  // 자동 초기화 제거 — refresh의 렌더는 변경 중인 카드를 덮어쓰지 않는다
  eq(html.includes("if (!force && formBaseline[name] && groupDirty(name))"), true,
    "ux1: dirty group survives refresh render");
  eq(html.includes("renderTempSettings();\n      renderTradeSettings();"), true,
    "ux1: refresh still syncs clean groups");
  eq(html.includes("syncGroupForm('temp', true)"), true,
    "ux1: temp save force-syncs from saved values");
  eq(html.includes("syncGroupForm(name, true)"), true,
    "ux1: trade save force-syncs its group");
  // 변경 표시와 저장 버튼 활성 상태
  eq(html.includes(".set.dirty .lab::after"), true, "ux1: dirty dot style exists");
  eq(html.includes("saveBtn.disabled = cardLocked[name] || !dirty"), true, "ux4: save enabled only when dirty and that card unlocked");
  eq(html.includes("svBtn.disabled = cardLocked[name] || !dirty"), true, "ux2: revert-to-saved enabled only when dirty and that card unlocked");
  eq(html.includes("dfBtn.disabled = cardLocked[name] || atDefault"), true, "ux2: revert-to-default disabled at defaults or that card locked");
  // 카드 4개 전부 저장 버튼은 비활성으로 시작하고 되돌리기 버튼 쌍을 갖는다
  for (const card of ["Temp", "Band", "Size", "Stop"]) {
    eq(html.includes(`id="btn${card}Save" disabled`), true, `ux4: ${card} save starts disabled`);
    eq(html.includes(`id="btn${card}Saved" disabled`), true, `ux2: ${card} revert-to-saved button exists`);
    eq(html.includes(`id="btn${card}Default"`), true, `ux2: ${card} revert-to-default button exists`);
  }
  // 되돌리기는 컨펌 후 폼에만 적용된다 (저장소 쓰기는 저장 버튼 경로뿐).
  // 컨펌은 인페이지 팝업(askConfirm)이 정본이다 — window.confirm은 기기
  // WebView에서 항상 false라 무반응이 된다 (v0.3.42, html-guard가 잔존 차단).
  eq(html.includes("askConfirm(msg, '되돌리기', false, function(){"), true, "ux2: revert asks confirm first");
  eq(html.includes("function revertGroup(name, toDefault)"), true, "ux2: revert handler exists");
  // 화면을 떠날 때 저장 안 한 변경이 있으면 한 번 묻는다
  eq(html.includes("window.addEventListener('beforeunload'"), true, "ux1: beforeunload guard exists");
  eq(html.includes("anySettingsDirty()"), true, "ux1: dirty aggregate exists");
  // 숫자 직접 입력 — 시트와 범위 검증 (범위 밖이면 넣지 않는다)
  eq(html.includes('id="numOverlay"'), true, "ux3: number sheet exists");
  eq(html.includes('id="numInput"'), true, "ux3: number input exists");
  eq(html.includes("if (ok && t.min !== undefined && v < t.min) ok = false;"), true,
    "ux3: below-min input rejected");
  eq(html.includes("if (ok && t.max !== undefined && v > t.max) ok = false;"), true,
    "ux3: above-max input rejected");
  eq(html.includes("v = base + Math.round((v - base) / t.step) * t.step;"), true,
    "ux3: input snapped to step grid");
  eq(html.includes("function sliderSpec(inputId, dispId, label)"), true,
    "ux3: direct input reuses slider min/max/step");
}

// 47. 연동 화면 하단 요약 — 저장된 설정의 해석 결과로 렌더한다.
// 기본값이면 기존 고정 문구와 글자까지 같고, 바꾸면 바뀐 값이 보인다.
{
  const LEGACY_SUMMARY = "밴드 0.60–0.90 · 건당 $5.00 · 현금 30% 유지 · 최대 70건 · 손절 진입가 −0.25, 그 외 만기 보유";
  eq(settingsSummaryText(parseTradeSettings(null)), LEGACY_SUMMARY,
    "summary: defaults render legacy text");
  eq(settingsSummaryText(), LEGACY_SUMMARY, "summary: no cfg renders defaults");
  eq(settingsSummaryText(parseTradeSettings({
    bandLo: 0.7, bandHi: 0.95, betUsd: 7, cashFloorPct: 0.5, maxPositions: 10, stopDrop: 0.15
  })), "밴드 0.70–0.95 · 건당 $7.00 · 현금 50% 유지 · 최대 10건 · 손절 진입가 −0.15, 그 외 만기 보유",
    "summary: changed settings reflected");
  eq(settingsSummaryText(parseTradeSettings({ betUsd: 5.5, cashFloorPct: 0.125 })),
    "밴드 0.60–0.90 · 건당 $5.50 · 현금 12.5% 유지 · 최대 70건 · 손절 진입가 −0.25, 그 외 만기 보유",
    "summary: fractional percents trimmed");
  // 해석 단계의 폴백(밴드 쌍 역전 → 쌍째 기본값)도 요약에 그대로 드러난다
  eq(settingsSummaryText(parseTradeSettings({ bandLo: 0.95, bandHi: 0.9 })), LEGACY_SUMMARY,
    "summary: invalid band pair falls back to defaults");
  // 뷰어가 실제로 쓰는 BM 경유 경로
  eq(typeof globalThis.window.BM?.settingsSummaryText, "function",
    "summary: BM exposes settingsSummaryText");
  eq(typeof globalThis.window.BM?.parseTradeSettings, "function",
    "summary: BM exposes parseTradeSettings");
  eq(globalThis.window.BM.settingsSummaryText(globalThis.window.BM.parseTradeSettings({ stopDrop: 0.2 })),
    "밴드 0.60–0.90 · 건당 $5.00 · 현금 30% 유지 · 최대 70건 · 손절 진입가 −0.20, 그 외 만기 보유",
    "summary: BM path renders changed stopDrop");
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  eq(html.includes('id="settingsSummary"'), true, "summary: footer span exists");
  eq(html.includes("function renderSettingsSummary()"), true, "summary: renderer exists");
  eq(html.includes("BM.settingsSummaryText(BM.parseTradeSettings(readSettingsObject()))"), true,
    "summary: renderer uses parsed saved settings");
  eq(html.includes("renderTradeSettings();\n      renderSettingsSummary();"), true,
    "summary: refresh renders summary");
  eq(html.includes("syncGroupForm(name, true);\n      renderSettingsSummary();"), true,
    "summary: save re-renders summary immediately");
}

// orderTestRounds는 v0.3.43에서 기능이 제거된 뒤에도 파싱만 남은 유산
// 필드다 — 저장된 설정이 있어도 깨지지 않고 기본값 규칙이 유지되는지,
// 그리고 사이클 단계별 소요 표기(유지 대상)가 그대로인지 고정한다.
{
  eq(parseTradeSettings({}).orderTestRounds, 30, "settings: legacy rounds default");
  eq(parseTradeSettings({ orderTestRounds: 45 }).orderTestRounds, 45, "settings: legacy rounds set");
  eq(parseTradeSettings({ orderTestRounds: 0 }).orderTestRounds, 30, "settings: legacy rounds 0 fallback");
  eq(parseTradeSettings({ orderTestRounds: 101 }).orderTestRounds, 30, "settings: legacy rounds 101 fallback");
  eq(parseTradeSettings({ orderTestRounds: 45.5 }).orderTestRounds, 30, "settings: legacy rounds fractional fallback");

  eq(stageDurationsText(1, 2, 3, 4, 5),
    "소요 스냅샷 1ms · 손절 2ms · 스캔 3ms · 검증 4ms · 주문 5ms", "summary: durations text");
  eq(summarizeCycle({ candidates: 1, verified: 1, rejects: {}, filled: 1, unfilled: 0, failed: 0, durations: "D" }).endsWith(" · D"),
    true, "summary: durations appended");
  eq(summarizeCycle({ candidates: 1, verified: 1, rejects: {}, filled: 1, unfilled: 0, failed: 0 }).includes("소요"),
    false, "summary: without durations unchanged");
}


console.log(`book tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
