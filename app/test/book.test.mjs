// summarizeBook / judgeClosedRow 픽스처 테스트 — node_modules가 있는 사본에서 실행:
//   node test/book.test.mjs
// engine.js는 모듈 최상위에서 window.BM을 대입하므로 스텁을 먼저 깐다.
globalThis.window = {};
const {
  summarizeBook,
  judgeClosedRow,
  sizing,
  bootView,
  snapshotCacheOf,
  snapshotFromCache,
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
}

console.log(`book tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
