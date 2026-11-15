// 네이티브 이관 3단계 골든 생성기 — 판정 로직 기준 출력 산출.
//
// 방식은 2단계와 같다: app/src/engine.js 원본을 그대로 실행해 기대값을 만든다.
// - export된 순수 함수는 모듈을 직접 import해 호출한다.
// - export되지 않은 함수(verifyCandidate·fetchGammaPrices·fetchBuySlugs·
//   isFailsafe·ingestRedeemPending·recordStopLossAttempt)는 원본 텍스트에서
//   중괄호 매칭으로 추출해, fetchJson·state만 스텁으로 갈아끼우고 실행한다.
// - scanCandidates·verifyCandidate의 날짜 판정은 전역 Date를 고정해 결정적으로 만든다.
// - 입력의 "__NaN__"·"__Inf__"·"__-Inf__"는 revive로 실제 JS 수치로 되돌리고,
//   출력의 비유한 수치는 enc로 같은 센티넬 문자열로 인코딩한다 (JSON 왕복 보존).
//
// 실행: node gen_expected.mjs → fixtures.json을 읽어 expected.json을 쓴다.

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

// 모듈 import 전에 스텁을 깐다 (engine.js는 최상위에서 window.BM을 대입한다).
const lsStore = new Map();
globalThis.window = {};
globalThis.localStorage = {
  getItem: (k) => (lsStore.has(k) ? lsStore.get(k) : null),
  setItem: (k, v) => lsStore.set(k, String(v)),
  removeItem: (k) => lsStore.delete(k),
};
const BM = await import("../app/src/engine.js");

// ── 원본 추출 하네스 ─────────────────────────────────────────
const src = readFileSync(join(here, "../app/src/engine.js"), "utf8");

function extractBlock(marker) {
  const i = src.indexOf(marker);
  if (i < 0) throw new Error(`마커를 찾지 못함: ${marker}`);
  const open = src.indexOf("{", i);
  let depth = 0;
  for (let j = open; j < src.length; j += 1) {
    if (src[j] === "{") depth += 1;
    else if (src[j] === "}") {
      depth -= 1;
      if (depth === 0) return src.slice(i, j + 1);
    }
  }
  throw new Error(`중괄호 매칭 실패: ${marker}`);
}

function extractLine(marker) {
  const i = src.indexOf(marker);
  if (i < 0) throw new Error(`마커를 찾지 못함: ${marker}`);
  const end = src.indexOf("\n", i);
  return src.slice(i, end).replace(/;\s*$/, "");
}

function extractArray(marker) {
  const i = src.indexOf(marker);
  if (i < 0) throw new Error(`마커를 찾지 못함: ${marker}`);
  const open = src.indexOf("[", i);
  let depth = 0;
  for (let j = open; j < src.length; j += 1) {
    if (src[j] === "[") depth += 1;
    else if (src[j] === "]") {
      depth -= 1;
      if (depth === 0) return src.slice(i, j + 1);
    }
  }
  throw new Error(`대괄호 매칭 실패: ${marker}`);
}

const harnessPre = `
let fetchJsonImpl = async () => { throw new Error("fetchJson not stubbed"); };
async function fetchJson(...args) { return fetchJsonImpl(...args); }
const state = { client: null, lastWinRows: null, lastLostRows: null, lastClosedRows: null };
`;
const parts = [
  harnessPre,
  ...["GAMMA_MARKETS_URL", "DATA_API_V2", "BAND_LO", "BAND_HI", "SPREAD_MAX",
      "ASK_DEPTH_MIN_USD", "SLIPPAGE_CAP", "SNAPSHOT_MATCH_TOL", "ACTIVITY_PAGE_CAP"]
    .map((n) => extractLine(`const ${n} =`)),
  extractArray("const FAILSAFE_KEYWORDS ="),
  extractLine("const round2 ="),
  extractBlock("const LS = {"),
  extractBlock("export function isSportsMarket"),
  extractBlock("function roundTick"),
  extractBlock("async function verifyCandidate"),
  extractBlock("async function fetchGammaPrices"),
  extractBlock("async function fetchBuySlugs"),
  extractBlock("const isFailsafe"),
  extractBlock("export function durableWrite"),
  extractBlock("export function durableRead"),
  extractBlock("function loadDurableJsonList"),
  extractBlock("function loadRedeemPending"),
  extractBlock("function saveRedeemPending"),
  extractBlock("function ingestRedeemPending"),
  extractBlock("export function stopLossAttemptedTokens"),
  extractBlock("function recordStopLossAttempt"),
];
for (const p of parts.slice(1)) {
  if (!src.includes(p)) throw new Error("추출 조각이 원본에 verbatim으로 없음");
}
const code = parts.join("\n").replaceAll("export function", "function").replaceAll("export const", "const");
const H = new Function(
  `${code}\nreturn { verifyCandidate, fetchGammaPrices, fetchBuySlugs, isFailsafe,
    ingestRedeemPending, loadRedeemPending, stopLossAttemptedTokens, recordStopLossAttempt,
    __setFetch: (f) => { fetchJsonImpl = f; }, __state: state };`,
)();

// ── 날짜 고정 ────────────────────────────────────────────────
const RealDate = Date;
function patchDate(ms) {
  globalThis.Date = class extends RealDate {
    constructor(...a) {
      super(...(a.length ? a : [ms]));
    }
    static now() {
      return ms;
    }
  };
}
function unpatchDate() {
  globalThis.Date = RealDate;
}

// ── 센티넬 revive / enc ──────────────────────────────────────
function revive(v) {
  if (v === "__NaN__") return NaN;
  if (v === "__Inf__") return Infinity;
  if (v === "__-Inf__") return -Infinity;
  if (Array.isArray(v)) return v.map(revive);
  if (v && typeof v === "object") {
    const o = {};
    for (const [k, x] of Object.entries(v)) o[k] = revive(x);
    return o;
  }
  return v;
}

function enc(v) {
  if (v === undefined) return "__UNDEF__";
  if (typeof v === "number") {
    if (Number.isNaN(v)) return "__NaN__";
    if (v === Infinity) return "__Inf__";
    if (v === -Infinity) return "__-Inf__";
    return v;
  }
  if (typeof v === "bigint") return `__bigint:${v}`;
  if (v instanceof Set) return [...v].map(enc);
  if (Array.isArray(v)) return v.map(enc);
  if (v && typeof v === "object") {
    const o = {};
    for (const [k, x] of Object.entries(v)) o[k] = enc(x);
    return o;
  }
  return v;
}

// ── 종류별 실행 ──────────────────────────────────────────────
const runners = {
  scan(inp) {
    patchDate(inp.nowMs);
    try {
      const stats = {};
      const out = BM.scanCandidates(inp.markets, new Set(inp.heldSlugs), new Set(inp.boughtSlugs), stats);
      return { candidates: out, stats };
    } finally {
      unpatchDate();
    }
  },

  async verify(inp) {
    H.__setFetch(async () => inp.gammaPage);
    H.__state.client = {
      getOrderBook: async () => {
        if (inp.bookError) throw new Error("book boom");
        return inp.book;
      },
      getTickSize: async () => inp.tickSizeFallback,
    };
    patchDate(inp.nowMs);
    try {
      return await H.verifyCandidate(inp.cand, inp.bet);
    } finally {
      unpatchDate();
    }
  },

  slVerdict: (inp) => ({ verdict: BM.stopLossVerdict(inp.entry, inp.current) }),
  pickIndex: (inp) => ({ index: BM.pickOutcomeIndex(inp.outcomes, inp.row) }),

  async gammaParse(inp) {
    H.__setFetch(async () => inp.page);
    const r = await H.fetchGammaPrices("any-slug");
    return { parsed: r === null ? null : { outcomes: r.outcomes, prices: r.prices } };
  },

  async slEvaluate(inp) {
    // checkStopLossPosition의 판정 흐름을 원본 함수들로 재구성한 하네스 —
    // 가드 순서·재검증 조건은 원본과 같고, 실행(executeStopLossSell) 직전에서 멈춘다.
    const pages = [inp.page1, inp.page2];
    let qi = 0;
    H.__setFetch(async () => pages[qi++]);
    const row = inp.row;
    const slug = row?.slug;
    const entry = Number(row?.avg_price);
    const size = Number(row?.current_size);
    if (!slug || !row?.token_id || !Number.isFinite(entry)) return { action: "skip" };
    if (!Number.isFinite(size) || size <= 0) return { action: "skip" };
    if (entry <= 0) return { action: "bad_entry" };
    const first = await H.fetchGammaPrices(row.slug).catch(() => null);
    if (!first) return { action: "skip" };
    const idx = BM.pickOutcomeIndex(first.outcomes, row);
    if (idx < 0 || idx >= first.prices.length) return { action: "no_index" };
    if (BM.stopLossVerdict(entry, first.prices[idx]) !== "triggered") {
      return { action: "holding", current: first.prices[idx] };
    }
    const second = await H.fetchGammaPrices(row.slug).catch(() => null);
    if (!second) return { action: "skip" };
    const idx2 = BM.pickOutcomeIndex(second.outcomes, row);
    if (idx2 < 0 || idx2 >= second.prices.length) return { action: "skip" };
    const current = second.prices[idx2];
    if (BM.stopLossVerdict(entry, current) !== "triggered") {
      return { action: "recheck_dropped", current };
    }
    return { action: "triggered", current };
  },

  plan(inp) {
    const p = BM.planRedeems(inp.entries);
    return {
      targets: p.targets.map((t) => ({
        condition_id: t.condition_id, wins: t.wins, losses: t.losses, expected_usd: t.expected_usd,
      })),
      worthless: p.worthless,
      errors: p.errors.map((e) => e.reason),
    };
  },

  verifyRecovery: (inp) => BM.verifyRecovery(inp.before, inp.after, inp.expected),
  balanceGate: (inp) => ({
    gate: BM.redeemBalanceGate(inp.balances === null ? null : inp.balances.map((b) => BigInt(b))),
  }),
  normalizeCid: (inp) => ({ cid: BM.normalizeConditionId(inp.raw) }),

  toBytes31(inp) {
    try {
      return { hex: BM.toBytes31(inp.conditionId) };
    } catch {
      return { throw: true };
    }
  },

  redeemCallCtf: (inp) => BM.buildCtfRedeemCall(inp.conditionId, inp.negRisk),

  redeemCallV2(inp) {
    try {
      return BM.buildV2RedeemCall(inp.conditionId, BigInt(inp.outcomeIndex), BigInt(inp.amount));
    } catch {
      return { throw: true };
    }
  },

  judgeFill: (inp) => BM.judgeFill(inp.resp),

  reconcile(inp) {
    const r = BM.reconcileBought(new Set(inp.bought), new Set(inp.onChain), new Set(inp.held));
    return { kept: r.kept, dropped: r.dropped };
  },

  async buySlugs(inp) {
    const pages = inp.pages;
    let qi = 0;
    H.__setFetch(async () => {
      const p = pages[qi++];
      if (p && p.$throw) throw new Error("fetch boom");
      return p;
    });
    const r = await H.fetchBuySlugs("0xFUNDER");
    return { slugs: [...r.slugs], complete: r.complete };
  },

  sizing: (inp) => BM.sizing(inp.cash, inp.equity, inp.openCount),
  betUsd: (inp) => ({ bet: BM.betUsdFor(inp.equity) }),
  settledParse: (inp) => ({ base: BM.parseSettledBaseline(inp.raw) }),
  settledNotice: (inp) => ({ notice: BM.settledNoticeFor(inp.base, inp.snap) }),

  slLedger(inp) {
    lsStore.clear();
    if (inp.recentRaw != null) localStorage.setItem("bm_stoploss", inp.recentRaw);
    if (inp.archiveRaw != null) localStorage.setItem("bm_stoploss_archive", inp.archiveRaw);
    BM.recordStopLoss(inp.rec);
    return {
      recent: JSON.parse(localStorage.getItem("bm_stoploss") || "[]"),
      archive: JSON.parse(localStorage.getItem("bm_stoploss_archive") || "[]"),
      merged: BM.stopLossRecords(),
    };
  },

  slParse(inp) {
    lsStore.clear();
    if (inp.recentRaw != null) localStorage.setItem("bm_stoploss", inp.recentRaw);
    if (inp.archiveRaw != null) localStorage.setItem("bm_stoploss_archive", inp.archiveRaw);
    return { merged: BM.stopLossRecords() };
  },

  attempted(inp) {
    lsStore.clear();
    if (inp.raw != null) localStorage.setItem("bm_stoploss_attempted", inp.raw);
    H.recordStopLossAttempt(inp.add);
    return { tokens: [...H.stopLossAttemptedTokens()] };
  },

  ingest(inp) {
    lsStore.clear();
    localStorage.setItem("bm_redeem_pending", JSON.stringify(inp.pending));
    localStorage.setItem("bm_redeem_history", JSON.stringify(inp.history));
    localStorage.setItem("bm_redeem_closed", JSON.stringify(inp.closed));
    H.__state.lastWinRows = inp.winRows;
    H.__state.lastLostRows = inp.lostRows;
    H.__state.lastClosedRows = inp.closedRows;
    const before = localStorage.getItem("bm_redeem_pending");
    H.ingestRedeemPending();
    const after = localStorage.getItem("bm_redeem_pending");
    return { pending: JSON.parse(after || "[]"), changed: after !== before };
  },

  corrupted(inp) {
    lsStore.clear();
    if (inp.raw != null) localStorage.setItem("bm_bought", inp.raw);
    return { corrupted: BM.boughtCorrupted() };
  },

  failsafe: (inp) => ({ hit: H.isFailsafe(inp.text) }),
};

const fixtures = JSON.parse(readFileSync(join(here, "fixtures.json"), "utf8"));
const out = { meta: { ...fixtures.meta, expected_from: "app/src/engine.js (모듈 import + 원본 추출 실행)" }, cases: {} };
for (const c of fixtures.cases) {
  const runner = runners[c.kind];
  if (!runner) throw new Error(`러너 없음: ${c.kind}`);
  let result;
  try {
    result = await runner(revive(c.input));
  } catch (e) {
    result = { throw: true };
  }
  out.cases[c.name] = enc(result);
}
writeFileSync(join(here, "expected.json"), JSON.stringify(out, null, 1) + "\n");
console.log(`expected.json 생성: ${Object.keys(out.cases).length} 케이스`);
