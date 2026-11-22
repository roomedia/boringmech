import {
  AssetType,
  Chain,
  ClobClient,
  OrderType,
  Side,
  SignatureTypeV2,
} from "@polymarket/clob-client-v2";
import { createWalletClient, encodeFunctionData, http, parseAbi } from "viem";
import { privateKeyToAccount } from "viem/accounts";
import { polygon } from "viem/chains";
import { deriveBeaconDepositWallet, deriveUupsDepositWallet } from "./depositWallet.js";

// 빌드 시 esbuild define으로 주입되는 앱 버전 (app/bundle.mjs가 package.json에서 읽어 박는다).
// 미주입 환경에서는 null이 되어 업데이트 확인 전체가 조용히 비활성화된다.
const APP_VERSION = typeof __APP_VERSION__ !== "undefined" ? __APP_VERSION__ : null;

const CLOB_HOST = "https://clob.polymarket.com";
const GAMMA_MARKETS_URL = "https://gamma-api.polymarket.com/markets";
const GAMMA_EVENTS_URL = "https://gamma-api.polymarket.com/events";
const DATA_API_V2 = "https://data-api.polymarket.com/v2";
const PUSD = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB";
const POLYGON_RPCS = [
  "https://polygon-bor-rpc.publicnode.com",
  "https://1rpc.io/matic",
  "https://polygon.drpc.org",
  "https://polygon.api.onfinality.io/public",
];

// 매매 판단 상수 — 전부 bm_settings로 바꿀 수 있고(parseTradeSettings),
// 기본값은 아래 상수와 같다. 설정을 건드리기 전에는 동작이 변하지 않는다.
export const BET_PCT = 0.01;
export const BET_MIN_USD = 5.0;
export const CASH_FLOOR_PCT = 0.3;
export const RESERVE_USD = 2.87;
export const MAX_POSITIONS = 70;
export const BAND_LO = 0.6;
export const BAND_HI = 0.9;
export const PRIORITY_BAND_LO = 0.7;
export const PRIORITY_BAND_HI = 0.8;
export const VOLUME_MIN = 10000;
export const END_WINDOW_DAYS = 45;
export const SPREAD_MAX = 0.02;
export const ASK_DEPTH_MIN_USD = 10.0;
export const SLIPPAGE_CAP = 0.015;
export const SNAPSHOT_MATCH_TOL = 0.03;
export const ASK_ABOVE_SIGNAL = 0.05;
export const VERIFY_CAP = 60;
const CYCLE_MS = 60_000;
// 요청 타임아웃 (F1) — 무응답 fetch가 사이클을 무한정 붙잡지 않게 한다.
export const FETCH_TIMEOUT_MS = 20_000;
// 워치독 하트비트 주기 (F1) — 사이클 진행 중에도 이 간격으로 비트를 남겨
// 긴 정상 사이클이 stale로 오인돼 재로드되지 않게 한다.
export const HEARTBEAT_INTERVAL_MS = 30_000;
// 하트비트가 한 번도 없을 때 뷰어가 "응답 없음"으로 판정하기까지의 유예 (F2)
export const NO_HEARTBEAT_GRACE_MS = 60_000;
// 포지션 페이지네이션 상한 (부수2) — 커서가 순환해도 스냅샷이 끝나지 않게 한다
export const POSITIONS_PAGE_CAP = 50;
// 제출 진행 중 워치독 유예의 상한 (F1) — 하트비트가 이보다 오래됐으면
// 제출 가드가 걸려 있어도 재로드를 허용한다 (제출이 이만큼 걸릴 수는 없다)
export const SUBMIT_GUARD_MAX_MS = 600_000;

const SHORT_TERM_RE = /(bitcoin|ethereum|solana|xrp|dogecoin|cardano)-up-or-down|up-or-down-/;
const FAILSAFE_KEYWORDS = [
  "403", "forbidden", "geoblock", "geo-block", "signature",
  "maker address not allowed", "invalid signature", "not allowed",
];

const LS = {
  key: "bm_pk",
  creds: "bm_creds",
  funder: "bm_funder",
  bought: "bm_bought",
  runIntent: "bm_run_intent",
  snap: "bm_snap",
  log: "bm_log",
  updateInfo: "bm_update",
  wdSeen: "bm_wd_seen",
  wdCycleSeen: "bm_wd_cycle_seen",
  selfReload: "bm_self_reload",
  stopLoss: "bm_stoploss",
  stopLossArchive: "bm_stoploss_archive",
  settledBase: "bm_settled_base",
  relayer: "bm_relayer",
  redeemPending: "bm_redeem_pending",
  redeemPendingArchive: "bm_redeem_pending_archive",
  redeemHistory: "bm_redeem_history",
  redeemClosed: "bm_redeem_closed",
  redeemDoneArchive: "bm_redeem_done_archive",
  stage: "bm_stage",
  stopLossAttempted: "bm_stoploss_attempted",
  stopLossAttemptedSlugs: "bm_stoploss_attempted_slugs",
  manualSell: "bm_manual_sell",
  manualSellArchive: "bm_manual_sell_archive",
  manualSellAttempted: "bm_manual_sell_attempted",
  manualSellAttemptedArchive: "bm_manual_sell_attempted_archive",
  settings: "bm_settings",
};

// 손절 (2026-11-14 사용자 결정 — 페이퍼 boringmech와 동일 규칙):
// 보유 포지션 현재가(Gamma outcomePrices) ≤ 진입가 − 0.25면 전량 매도.
// 사실상 결정된 마켓(≥0.97·≤0.03)은 정산이 처리하므로 제외하고,
// 가격 조회 실패는 0으로 취급하지 않고 그 건만 스킵한다.
export const STOP_DROP = 0.25;
export const DECIDED_HIGH = 0.97;
export const DECIDED_LOW = 0.03;

const round2 = (x) => Math.round((x + Number.EPSILON) * 100) / 100;
const round4 = (x) => Math.round((x + Number.EPSILON) * 10000) / 10000;

const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

// 실패를 간격을 두고 다시 시도한다 (F2 — 복원 경로 전용. 매매 경로는 쓰지 않는다)
export async function retryAsync(fn, attempts = 3, delayMs = 2000, nap = sleep) {
  let last;
  for (let i = 0; i < attempts; i += 1) {
    try {
      return await fn();
    } catch (e) {
      last = e;
      if (i < attempts - 1) await nap(delayMs);
    }
  }
  throw last;
}

// 타임아웃 있는 fetch (F1) — AbortController로 끊고 시간 초과를 일반 오류로 바꾼다
async function fetchWithTimeout(url, options = {}, timeoutMs = FETCH_TIMEOUT_MS) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: ctl.signal });
  } catch (e) {
    if (e?.name === "AbortError") {
      throw new Error(
        `요청 시간 초과 (${Math.round(timeoutMs / 1000)}초) ${String(url).split("?")[0]}`,
      );
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

export function betUsdFor(equity, cfg = DEFAULT_TRADE_SETTINGS) {
  return round2(Math.max(cfg.betMinUsd, (equity || 0) * cfg.betPct));
}

export function sizing(cash, equity, openCount, cfg = DEFAULT_TRADE_SETTINGS) {
  // 비유한 입력(NaN·Infinity)이면 사이징을 닫는다 — 측정값을 못 믿으면 사지 않는다
  if (!Number.isFinite(cash) || !Number.isFinite(equity) || !Number.isFinite(openCount)) {
    return { cashFloor: cfg.reserveUsd, available: 0, bet: betUsdFor(0, cfg), slots: 0 };
  }
  const cashFloor = round2(Math.max(cfg.reserveUsd, equity * cfg.cashFloorPct));
  const available = round2(Math.max(0, cash - cashFloor));
  const bet = betUsdFor(equity, cfg);
  // bet이 0 이하면 나눗셈이 무한대로 터져 슬롯이 상한까지 차는 사고가
  // 난다 (betMinUsd=0 + 작은 betPct 조합) — 베팅액이 없으면 슬롯도 없다.
  const cashSlots = bet > 0 ? Math.floor((available + 1e-9) / bet) : 0;
  const slots = Math.max(0, Math.min(cashSlots, cfg.maxPositions - openCount));
  return { cashFloor, available, bet, slots };
}

async function rpcCall(method, params) {
  let lastError;
  for (const url of POLYGON_RPCS) {
    try {
      const resp = await fetchWithTimeout(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
      }, 15_000);
      const data = await resp.json();
      if (data.error) throw new Error(data.error.message || "rpc error");
      return data.result;
    } catch (e) {
      lastError = e;
    }
  }
  throw lastError || new Error("모든 Polygon RPC 실패");
}

const getCode = (addr) => rpcCall("eth_getCode", [addr, "latest"]);

async function pusdBalance(addr) {
  const data = `0x70a08231${addr.slice(2).toLowerCase().padStart(64, "0")}`;
  const result = await rpcCall("eth_call", [{ to: PUSD, data }, "latest"]);
  return Number(BigInt(result)) / 1e6;
}

async function detectFunder(eoa) {
  const candidates = [
    { kind: "beacon", addr: deriveBeaconDepositWallet(eoa) },
    { kind: "uups", addr: deriveUupsDepositWallet(eoa) },
  ];
  const deployed = [];
  for (const c of candidates) {
    const code = await getCode(c.addr);
    if (code && code !== "0x") deployed.push(c);
  }
  if (!deployed.length) return null;
  if (deployed.length === 1) return deployed[0].addr;
  let best = deployed[0];
  let bestBal = -1;
  for (const c of deployed) {
    const bal = await pusdBalance(c.addr).catch(() => 0);
    if (bal > bestBal) {
      best = c;
      bestBal = bal;
    }
  }
  return best.addr;
}

async function fetchJson(url) {
  const resp = await fetchWithTimeout(url, {
    headers: { "User-Agent": "boringmech-app/0.3" },
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status} ${url.split("?")[0]}`);
  return resp.json();
}

export async function fetchPositions(funder, status) {
  const out = [];
  const seenKeys = new Set();
  let cursor = null;
  for (let page = 0; ; page += 1) {
    // 커서가 순환하면 스냅샷이 영영 끝나지 않는다 — 상한에서 오류로 끊는다 (부수2)
    if (page >= POSITIONS_PAGE_CAP) {
      throw new Error("포지션 조회 페이지 상한 초과 — 커서 순환 의심");
    }
    const params = new URLSearchParams({ user: funder, limit: "100" });
    if (status) params.set("status", status);
    if (cursor) params.set("cursor", cursor);
    const data = await fetchJson(`${DATA_API_V2}/positions?${params}`);
    // data가 배열이 아닌 200 응답을 빈 목록으로 삼키면 보유 0건으로 오독해
    // 건수 캡과 손절 대상이 뚫린다 — 형식 오류는 실패로 던진다.
    if (!Array.isArray(data?.data)) {
      throw new Error("포지션 조회 응답 형식 오류");
    }
    // 페이지가 비었는데 다음이 있다고 하면 부분 목록을 전체로 확정할 수
    // 없다 — 같은 이유로 실패로 던진다.
    if (!data.data.length && data.pagination?.has_more) {
      throw new Error("포지션 조회 부분 응답 — 빈 페이지인데 has_more");
    }
    for (const p of data.data) {
      // 순회 중 상태 전이로 페이지가 밀리면 같은 토큰이 두 번 잡힌다 —
      // openCount가 부풀지 않게 토큰 단위로 중복을 제거한다.
      const key = positionKey(p);
      if (key) {
        if (seenKeys.has(key)) continue;
        seenKeys.add(key);
      }
      out.push(p);
    }
    cursor = data.pagination?.next_cursor;
    if (!cursor) {
      // 뒤가 더 있는데 커서가 없으면 절단된 목록이다 (v0.3.32 감사).
      if (data.pagination?.has_more) {
        throw new Error("포지션 조회 부분 응답 — has_more인데 next_cursor 없음");
      }
      return out;
    }
  }
}

// 포지션 식별 키 — 정산 목록 간 중복 제거용. 포지션은 토큰(자산) 단위다.
function positionKey(p) {
  if (p?.token_id) return `t:${p.token_id}`;
  if (p?.condition_id) return `c:${p.condition_id}|${p.outcome_index ?? ""}`;
  return null;
}

// CLOSED 행의 승패 판정 — 행 status만으로는 못 가른다.
// 1순위 current_price(문서: 주당 라이브 마크; 정산이 끝난 토큰은 1 또는 0으로
// 확정된다 — 실응답 승 건에서 1.0 확인): 1이면 보유 토큰 쪽 승리, 0이면 패배.
// 2순위 realized_pnl(문서: 포지션 누적 실현 손익) 부호 — 정산 전 전량 청산으로
// 닫힌 행처럼 current_price가 0/1이 아닌 경우의 폴백.
// 둘 다로 못 가르면 null (건수에만 더하고 승패에서는 뺀다).
export function judgeClosedRow(p) {
  const cp = Number(p?.current_price);
  if (Number.isFinite(cp)) {
    if (cp >= 1) return "win";
    if (cp <= 0) return "loss";
  }
  const rp = Number(p?.realized_pnl);
  if (Number.isFinite(rp)) {
    if (rp > 0) return "win";
    if (rp < 0) return "loss";
  }
  return null;
}

// 정산 행의 실현손익 — 건수·승패와 같은 dedupe 집합 기준으로, 살아남은 행이
// 속한 조회 목록의 정의를 따른다. 전 상태 행이 같은 필드를 갖는다(문서 List
// positions의 Position 스키마 + CLOSED 실응답 대조 — REDEEMABLE 행은 현재
// 지갑에 0건이라 실응답으로는 미확인, 문서 스키마로 대조).
// 승(REDEEMABLE): 확정 지급액 − 진입 원가 = current_value − entry_cost_usdc.
// 패(REDEEMABLE_LOST): 토큰 가치가 0으로 확정 — −entry_cost_usdc.
// CLOSED: 행의 realized_pnl(문서: 포지션 누적 실현 손익).
// 필요한 필드가 숫자가 아니면 null — 합산에서 빼고 settledRealizedExcluded로
// 센다. 승패 판정 불가로 건수에만 들어간 행도 realized_pnl이 있으면 합산한다.
function realizedOf(kind, p) {
  const cost = Number(p?.entry_cost_usdc);
  if (kind === "win") {
    const value = Number(p?.current_value);
    return Number.isFinite(value) && Number.isFinite(cost) ? value - cost : null;
  }
  if (kind === "loss") return Number.isFinite(cost) ? -cost : null;
  const rp = Number(p?.realized_pnl);
  return Number.isFinite(rp) ? rp : null;
}

// 포지션 장부 집계 — Data API v2 필드 기준 (문서 List positions + 실응답 대조):
// 수량 current_size, 평균 진입가 avg_price, 현재 가치 current_value.
// 등락 %는 보유(OPEN) 행만으로 (현재가치 합 − 진입원가 합) / 진입원가 합.
// 정산 승패는 행의 status 값으로 가르지 않는다 — 상환 가능 패자 행도 status가
// REDEEMABLE로 보존되고 REDEEMABLE·REDEEMABLE_LOST 양쪽 조회에 동시에
// 잡히므로(2026-11-15 실측), REDEEMABLE 조회분은 행 필드(judgeClosedRow)로
// 검증해 가르고 REDEEMABLE_LOST 조회분만 출처 그대로 패로 본다.
// CLOSED 조회분은 행 필드로 판정한다(judgeClosedRow — 2026-11-14 사용자 결정으로
// 정산 완료 집계에 포함). 세 목록의 합집합은 positionKey로 중복을 제거한다 —
// 상태 전이 타이밍에 같은 포지션이 두 목록에 걸려도 한 번만 센다.
// 실현손익도 같은 합집합으로 합산한다(realizedOf). 합산 가능한 행이 하나도
// 없는데 정산 건만 있으면 settledRealizedPnl은 null — 0으로 지어내지 않는다.
// 정산 조회를 하나라도 못 읽으면 settled*는 null —
// 화면은 숫자를 지어내지 않고 미표시로 남긴다.
// 손절 실행 기록(stopLossRecords: {token_id, realized_pnl})은 CLOSED 행보다
// 먼저 합집합에 넣는다 — 손절로 닫힌 건은 현재가로 승패를 가르지 않고
// 매도 실현손익 부호로 가르고, 실현도 기록값을 쓴다. 같은 토큰의 CLOSED
// 행은 dedupe로 걸러져 기록을 덮지 못한다.
// open 판정 단일 기준 — 네이티브 PositionBook.isOpenRow와 동일하다.
// status가 "OPEN"인 행이 본체. status가 비어 있는 행은 종료 상태
// (REDEEMABLE·CLOSED)가 아니고 수량이 있으면 보유로 본다 (표기 누락
// 하나로 보유가 장부에서 사라져 중복 매수가 열리는 것 방지).
export function isOpenRow(p) {
  if (!p || typeof p !== "object") return false;
  if (p.status === "OPEN") return true;
  if (!p.status) {
    const size = Number(p.current_size);
    return Number.isFinite(size) && size > 0;
  }
  return false;
}

export function summarizeBook(allRows, winRows, lostRows, closedRows, stopLossRecords, attemptedTokens = new Set(), manualSellRecords = []) {
  const open = (allRows || []).filter(isOpenRow);
  let value = 0;
  let cost = 0;
  for (const p of open) {
    const v = Number(p.current_value);
    const c = Number(p.current_size) * Number(p.avg_price);
    if (Number.isFinite(v)) value += v;
    if (Number.isFinite(c)) cost += c;
  }
  const positionsPnlPct =
    open.length > 0 && cost > 0 ? ((value - cost) / cost) * 100 : null;
  const settledKnown =
    Array.isArray(winRows) && Array.isArray(lostRows) && Array.isArray(closedRows);
  let settledCount = null;
  let settledWins = null;
  let settledLosses = null;
  let settledRealizedPnl = null;
  let settledRealizedExcluded = null;
  let settledStopLoss = null;
  if (settledKnown) {
    const entries = new Map();
    let seq = 0;
    const add = (rows, kind, verdictOf) => {
      for (const p of rows) {
        const key = positionKey(p) ?? `u:${seq++}`;
        if (!entries.has(key)) {
          const verdict = verdictOf(p);
          // 실현손익 종류는 REDEEMABLE 조회분에 한해 확정 판정을 따른다 —
          // 그 조회에 섞인 패자 행이 승 종류로 계산되지 않게. CLOSED 행은
          // 종전대로 realized_pnl 기준(closed 종류)을 유지한다.
          const kindOf =
            kind === "win" && verdict === "loss" ? "loss" : kind;
          entries.set(key, { verdict, pnl: realizedOf(kindOf, p), kind });
        }
      }
    };
    // 손절 기록이 조회 목록보다 먼저다 — 손절로 닫힌 건은 현재가로
    // 가르지 않고 매도 실현손익으로 가른다는 주석의 우선순위를 코드와
    // 일치시킨다 (종전에는 조회 쪽이 먼저 들어가 주석과 달랐다).
    for (const r of stopLossRecords || []) {
      if (!r || typeof r.token_id !== "string" || !r.token_id) continue;
      const key = `t:${r.token_id}`;
      if (entries.has(key)) continue;
      const rp = Number(r.realized_pnl);
      const known = Number.isFinite(rp);
      entries.set(key, {
        verdict: known ? (rp > 0 ? "win" : rp < 0 ? "loss" : null) : null,
        pnl: known ? rp : null,
        kind: "stoploss",
      });
    }
    // 수동 매도 기록도 조회 목록보다 먼저다 — 실제 매도 실현손익으로
    // 가르고, 손절 건수에는 넣지 않는다 (kind "manual", v0.3.33 감사).
    for (const r of manualSellRecords || []) {
      if (!r || typeof r.token_id !== "string" || !r.token_id) continue;
      const key = `t:${r.token_id}`;
      if (entries.has(key)) continue;
      const rp = Number(r.realized_pnl);
      const known = Number.isFinite(rp);
      entries.set(key, {
        verdict: known ? (rp > 0 ? "win" : rp < 0 ? "loss" : null) : null,
        pnl: known ? rp : null,
        kind: "manual",
      });
    }
    // 패자 행도 REDEEMABLE 조회에 함께 잡힌다 (2026-11-15 실측: 0원 확정
    // 2건이 REDEEMABLE·REDEEMABLE_LOST 양쪽에 동시 존재). 조회 출처만으로
    // 승 고정하면 패가 승으로 오분류되므로 행 필드로 검증한다 — 필드
    // 판정이 가능하면 그쪽이 이기고, 필드가 없어 못 가릴 때만 출처를
    // 폴백으로 쓴다 (실응답은 current_price가 항상 실리는 스키마).
    add(winRows, "win", (p) => judgeClosedRow(p) ?? "win");
    add(lostRows, "loss", () => "loss");
    // 결과 불명 손절을 시도한 토큰의 CLOSED 행은 최종가(1.0이면 승으로
    // 뒤집힘)가 아니라 realized_pnl 부호로 판정한다.
    add(closedRows, "closed", (p) =>
      attemptedTokens.has(p?.token_id)
        ? (() => {
            const rp = Number(p?.realized_pnl);
            if (Number.isFinite(rp)) return rp > 0 ? "win" : rp < 0 ? "loss" : null;
            return null;
          })()
        : judgeClosedRow(p),
    );
    settledCount = entries.size;
    settledWins = 0;
    settledLosses = 0;
    settledStopLoss = 0;
    settledRealizedExcluded = 0;
    let pnlSum = 0;
    let pnlKnown = 0;
    for (const e of entries.values()) {
      if (e.verdict === "win") settledWins += 1;
      else if (e.verdict === "loss") settledLosses += 1;
      if (e.kind === "stoploss") settledStopLoss += 1;
      if (e.pnl == null) settledRealizedExcluded += 1;
      else {
        pnlSum += e.pnl;
        pnlKnown += 1;
      }
    }
    if (pnlKnown > 0 || settledCount === 0) settledRealizedPnl = round2(pnlSum);
  }
  return {
    openCount: open.length,
    positionsPnlPct,
    settledCount,
    settledWins,
    settledLosses,
    settledRealizedPnl,
    settledRealizedExcluded,
    settledStopLoss,
    heldSlugs: new Set(open.map((p) => p.slug).filter(Boolean)),
    // 손절로 닫은 종목의 slug — 앱 밖에서 산 포지션을 손절한 경우처럼
    // bought 기록이 없는 종목도 재매수 차단에 넣기 위한 집합
    stopLossSlugs: new Set(
      (stopLossRecords || []).map((r) => r?.slug).filter(Boolean),
    ),
  };
}

async function fetchPositionsValue(funder) {
  const data = await fetchJson(
    `${DATA_API_V2}/value?${new URLSearchParams({ user: funder })}`,
  );
  const v = Number(data?.data?.value);
  // 필드 누락·비숫자를 0으로 삼키면 equity가 과소 계산돼 현금 하한이
  // 무너진 채 매수가 나간다 — 다른 조회처럼 실패로 던져 사이클을 멈춘다.
  if (!Number.isFinite(v)) throw new Error("포지션 평가액 응답 형식 오류");
  return v;
}

async function fetchMarkets(total = 1000, pageSize = 100) {
  const markets = [];
  let offset = 0;
  while (markets.length < total) {
    const params = new URLSearchParams({
      active: "true", closed: "false", order: "liquidity",
      ascending: "false", limit: String(pageSize), offset: String(offset),
    });
    const page = await fetchJson(`${GAMMA_MARKETS_URL}?${params}`);
    if (!Array.isArray(page) || !page.length) break;
    markets.push(...page);
    if (page.length < pageSize) break;
    offset += pageSize;
  }
  return markets.slice(0, total);
}

// 스포츠 판정 — 경기 마켓(sportsMarketType)만이 아니라 우승 아웃라이트까지
// 포함한다. 아웃라이트는 sportsMarketType이 비어 있지만 Gamma가 스포츠
// 수수료 체계(feeType: sports_fees_*)를 붙이므로 그걸로 가른다
// (2026-11-15 실측: 유동성 상위 1,000개 중 아웃라이트 170개, 정치 마켓은
// politics_fees로 명확히 분리됨). 사용자 지시로 제외 범위를 우승까지 확장.
export function isSportsMarket(m) {
  return !!m?.sportsMarketType || String(m?.feeType || "").startsWith("sports");
}

export function scanCandidates(markets, heldSlugs, boughtSlugs, stats = null, cfg = DEFAULT_TRADE_SETTINGS) {
  const today = new Date();
  const iso = (d) => d.toISOString().slice(0, 10);
  const todayIso = iso(today);
  const endMaxIso = iso(new Date(today.getTime() + cfg.endWindowDays * 86400_000));
  const out = [];
  const drop = (reason) => {
    if (stats) stats[reason] = (stats[reason] || 0) + 1;
  };
  for (const m of markets) {
    if (!m.active || m.closed) { drop("inactive"); continue; }
    if (m.acceptingOrders === false || m.archived === true) { drop("not_accepting"); continue; } // 주문 수용 중단·보관 마켓 (문서 Market Details — Market Status)
    const slug = m.slug || "";
    if (SHORT_TERM_RE.test(slug)) { drop("short_term"); continue; }
    if (isSportsMarket(m)) { drop("sports"); continue; } // 스포츠는 목록 단계에서 제외 (검증 단계에도 가드로 남겨 둠)
    if (heldSlugs.has(slug) || boughtSlugs.has(slug)) { drop("owned"); continue; }
    const end = (m.endDate || "").slice(0, 10);
    if (!(todayIso < end && end <= endMaxIso)) { drop("end_out"); continue; }
    // NaN은 모든 비교를 false로 만들어 가드를 통과한다 — 비유한 숫자는
    // 형식 오류로 탈락시킨다.
    const volume = Number(m.volume || 0);
    if (!Number.isFinite(volume) || volume < cfg.volumeMin) { drop("volume_low"); continue; }
    let prices;
    try {
      prices = JSON.parse(m.outcomePrices || "[]").map(Number);
    } catch {
      drop("price_bad");
      continue;
    }
    if (prices.length < 2 || !Number.isFinite(prices[0]) || !Number.isFinite(prices[1])) {
      drop("price_bad");
      continue;
    }
    const side = prices[0] >= prices[1] ? "yes" : "no";
    const price = side === "yes" ? prices[0] : prices[1];
    if (price < cfg.bandLo || price > cfg.bandHi) { drop("band_out"); continue; }
    out.push({ slug, question: m.question || "", side, price, endDate: m.endDate || "" });
  }
  if (stats) stats.total = markets.length;
  // 매수 우선순위: 우선 밴드(priorityBandLo–priorityBandHi) 구간 먼저,
  // 그 안에서 만기 가까운 순 (동률은 입력 순서 유지 — Array.sort는 안정 정렬이다)
  const bandGroup = (p) => (p >= cfg.priorityBandLo && p < cfg.priorityBandHi ? 0 : 1);
  out.sort((a, b) =>
    bandGroup(a.price) - bandGroup(b.price) ||
    a.endDate.localeCompare(b.endDate));
  return out;
}

// ── 기온 No 페이드 (2026-11-20) ─────────────────────────────
// 일일 최고기온 이벤트(highest-temperature-in-<city>-on-<date>, negRisk
// 그룹)의 정확한 정수 기온 시장 No를 밴드 안에서 사서 정산까지 보유한다.
// 근거는 전용 백테스트 PASS (backtest-temp-no-20261119.md). 손절은 메인
// (-0.25)과 분리해 기온 전용 폭(-0.15)을 적용하고, 판정은 매수 태그
// (temp_no)가 있는 건만 기온으로 본다 (태그 없는 기온 시장은 메인 취급).
// 기본 OFF — 뷰어 설정(bm_settings)의 tempNoEnabled가 켜질 때만 후보를 만든다.
export const TEMP_NO_BAND_HI = 0.998;
export const TEMP_NO_DEFAULT_BAND_LO = 0.8;
export const TEMP_NO_FEE_RATE = 0.05;
// 기온 전용 손절 폭 — 진입가 − TEMP_STOP_DROP이면 전량 매도 (메인 STOP_DROP과 별개).
export const TEMP_STOP_DROP = 0.15;
// 기온 슬롯 예약 수 — 기본 1개, 설정(tempNoReserveSlots)으로 0~MAX 지정.
// 0이면 예약 없음. 검증 캡 예약도 같은 수를 따른다.
export const TEMP_NO_DEFAULT_RESERVE_SLOTS = 1;
export const TEMP_NO_MAX_RESERVE_SLOTS = 70;
const TEMP_NO_ENTRY_HOUR_UTC = 6;
const TEMP_NO_CACHE_MS = 15 * 60_000;
const TEMP_NO_SLUG_PREFIX = "highest-temperature-in-";
const TEMP_NO_MONTHS = [
  "january", "february", "march", "april", "may", "june", "july",
  "august", "september", "october", "november", "december",
];
// 도시 슬러그 전체 — 백테스트 캐시(bt-temp-no-20261119)의 public-search
// 수확분과 같은 목록이다.
export const TEMP_NO_CITIES = [
  "amsterdam", "ankara", "austin", "beijing", "buenos-aires", "busan",
  "cape-town", "chengdu", "chongqing", "dallas", "guangzhou", "helsinki",
  "hong-kong", "houston", "istanbul", "jeddah", "kuala-lumpur", "london",
  "los-angeles", "madrid", "miami", "milan", "munich", "nyc", "paris",
  "qingdao", "san-francisco", "sao-paulo", "seattle", "seoul", "shanghai",
  "shenzhen", "singapore", "taipei", "tel-aviv", "tokyo", "toronto",
  "warsaw", "wellington", "wuhan",
];

/** 기온 이벤트·하위 시장 슬러그 판정 — 기온 손절 폭 판정의 슬러그 쪽 조건 (매수 태그와 함께 본다). */
export function isTempNoSlug(slug) {
  return typeof slug === "string" && slug.startsWith(TEMP_NO_SLUG_PREFIX);
}

const EXACT_TEMP_TITLE_RE = /^-?\d+°[CF]$/;

/**
 * 정확한 정수 기온 판정 — groupItemTitle이 정수 기온만으로 된 것만
 * ("3°C"·"72°F"). "6°C or below"·"21°C or higher" 꼬리 버킷과
 * "50-51°F" 구간 버킷은 제외한다.
 */
export function isExactTempTitle(title) {
  return typeof title === "string" && EXACT_TEMP_TITLE_RE.test(title.trim());
}

/** 오늘(UTC) 날짜의 도시별 이벤트 슬러그. */
export function tempEventSlugFor(city, nowMs) {
  const d = new Date(nowMs);
  return `${TEMP_NO_SLUG_PREFIX}${city}-on-${TEMP_NO_MONTHS[d.getUTCMonth()]}-${d.getUTCDate()}-${d.getUTCFullYear()}`;
}

/**
 * 조회 범위 슬러그 — 오늘과 내일(UTC) 날짜의 도시별 슬러그 전부.
 * 후보 생성은 창 안(당일)에서만 일어나지만, 예약 홀드는 내일 이벤트가
 * 전날 저녁부터 보여야 걸리므로 조회 범위만 이틀치로 넓힌다.
 */
export function tempEventSlugsForFetch(nowMs) {
  const out = [];
  for (const dayOffset of [0, 1]) {
    const t = nowMs + dayOffset * 86_400_000;
    for (const city of TEMP_NO_CITIES) out.push(tempEventSlugFor(city, t));
  }
  return out;
}

/**
 * 진입 창 — 이벤트 당일 entryHourUtc:00 UTC ≤ now < 이벤트 endDate.
 * 이벤트 날짜는 endDate의 UTC 날짜로 정한다 (실측 endDate는 당일
 * 12:00 UTC). 창 밖이면 후보를 만들지 않는다.
 */
export function tempWindowOpen(nowMs, endMs, entryHourUtc = TEMP_NO_ENTRY_HOUR_UTC) {
  if (!Number.isFinite(nowMs) || !Number.isFinite(endMs)) return false;
  const d = new Date(endMs);
  const startMs = Date.UTC(
    d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), entryHourUtc,
  );
  return startMs <= nowMs && nowMs < endMs;
}

/**
 * 진입 창이 남은 기온 이벤트 존재 판정 — 이벤트의 창 시작
 * (endDate 날짜의 entryHourUtc)이 endDate보다 먼저이고, 아직 endDate가
 * 지나지 않았으면 그 이벤트의 창은 열리거나 열릴 예정이다. 조회 목록은
 * 오늘+내일 이틀치라 내일 이벤트도 전날 저녁부터 이 판정에 걸린다.
 * 예약 홀드를 기온 후보 존재에만 걸면 창이 열리기 전에는 후보가 없어
 * 메인이 마지막 슬롯까지 먹어 버리므로, 드라이버는 이 판정으로 홀드를
 * 건다 (네이티브 TempNo.hasUpcomingWindow와 같은 규칙).
 */
export function tempHasUpcomingWindow(events, nowMs, entryHourUtc = TEMP_NO_ENTRY_HOUR_UTC, heldSlugs = new Set(), boughtSlugs = new Set()) {
  if (!Number.isFinite(nowMs)) return false;
  for (const ev of events || []) {
    if (!ev || !isTempNoSlug(ev.slug)) continue;
    const endMs = Date.parse(ev.endDate || "");
    if (!Number.isFinite(endMs)) continue;
    const d = new Date(endMs);
    const startMs = Date.UTC(
      d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), entryHourUtc,
    );
    if (startMs < endMs && nowMs < endMs && tempEventHasCandidateMarket(ev, heldSlugs, boughtSlugs)) return true;
  }
  return false;
}

/**
 * 이벤트 하위 시장 중 후보 가능(활성이고 아직 보유·매수 기록이 없는
 * 정확한 정수 시장)인 것이 있는지 — markets가 전부 보유·종결·꼬리
 * 버킷인 이벤트는 살 것이 없어 예약할 이유가 없다 (v0.3.39 감사 D,
 * 네이티브 TempNo.hasUpcomingWindow와 같은 규칙).
 */
function tempEventHasCandidateMarket(ev, heldSlugs, boughtSlugs) {
  if (!Array.isArray(ev?.markets)) return false;
  return ev.markets.some((m) => {
    if (!m?.active || m.closed) return false;
    if (m.acceptingOrders === false || m.archived === true) return false;
    const slug = m.slug || "";
    if (heldSlugs?.has(slug) || boughtSlugs?.has(slug)) return false;
    return isExactTempTitle(m.groupItemTitle);
  });
}

// ── 매매 설정 (bm_settings) ──────────────────────────────────
// 매매 판단 수치는 전부 설정으로 바꿀 수 있다. 기본값은 기존 하드코딩
// 상수와 같고, 범위를 벗어난 값은 필드 단위로 기본값으로 되돌린다
// (못 믿는 설정으로 사지 않는다). 하한·상한 쌍은 순서가 어긋나면
// 쌍째로 기본값으로 되돌린다.
export const DEFAULT_TRADE_SETTINGS = Object.freeze({
  bandLo: BAND_LO,
  bandHi: BAND_HI,
  priorityBandLo: PRIORITY_BAND_LO,
  priorityBandHi: PRIORITY_BAND_HI,
  volumeMin: VOLUME_MIN,
  endWindowDays: END_WINDOW_DAYS,
  spreadMax: SPREAD_MAX,
  askDepthMinUsd: ASK_DEPTH_MIN_USD,
  slippageCap: SLIPPAGE_CAP,
  snapshotMatchTol: SNAPSHOT_MATCH_TOL,
  askAboveSignal: ASK_ABOVE_SIGNAL,
  betPct: BET_PCT,
  betMinUsd: BET_MIN_USD,
  cashFloorPct: CASH_FLOOR_PCT,
  reserveUsd: RESERVE_USD,
  maxPositions: MAX_POSITIONS,
  verifyCap: VERIFY_CAP,
  stopDrop: STOP_DROP,
  tempStopDrop: TEMP_STOP_DROP,
  decidedHigh: DECIDED_HIGH,
  decidedLow: DECIDED_LOW,
});

function settingsObject(raw) {
  let o = raw;
  if (typeof raw === "string") {
    try {
      o = JSON.parse(raw);
    } catch {
      o = null;
    }
  }
  return o && typeof o === "object" ? o : null;
}

/**
 * 설정 숫자 필드의 엄격 해석 — 실제 숫자(유한)와 숫자 문자열(공백 제거
 * 후 비어 있지 않고 완전히 숫자로 파싱되는 것)만 숫자로 인정한다.
 * null·빈 문자열·불리언·배열·객체는 Number() 강제변환( null→0, true→1,
 * ""→0, []→0 )으로 위험한 값이 되는 대신 NaN으로 떨어져 필드 기본값을
 * 쓰게 한다 (v0.3.38 감사 축 4).
 */
export function strictNum(v) {
  if (typeof v === "number") return v;
  if (typeof v === "string" && v.trim() !== "") return Number(v);
  return NaN;
}

function numField(o, key, def, ok) {
  const v = strictNum(o?.[key]);
  return Number.isFinite(v) && ok(v) ? v : def;
}

const inOpen01 = (v) => v > 0 && v < 1;
const inHalfOpen01 = (v) => v > 0 && v <= 1;
const nonNeg = (v) => v >= 0;

/** 뷰어 설정(bm_settings) 해석 — 매매 판단 수치 전체. */
export function parseTradeSettings(raw) {
  const o = settingsObject(raw);
  const d = DEFAULT_TRADE_SETTINGS;
  if (!o) return { ...d };
  const out = {
    bandLo: numField(o, "bandLo", d.bandLo, inOpen01),
    bandHi: numField(o, "bandHi", d.bandHi, inHalfOpen01),
    priorityBandLo: numField(o, "priorityBandLo", d.priorityBandLo, nonNeg),
    priorityBandHi: numField(o, "priorityBandHi", d.priorityBandHi, inHalfOpen01),
    volumeMin: numField(o, "volumeMin", d.volumeMin, nonNeg),
    endWindowDays: numField(o, "endWindowDays", d.endWindowDays,
      (v) => Number.isInteger(v) && v >= 1 && v <= 365),
    spreadMax: numField(o, "spreadMax", d.spreadMax, inHalfOpen01),
    askDepthMinUsd: numField(o, "askDepthMinUsd", d.askDepthMinUsd, nonNeg),
    slippageCap: numField(o, "slippageCap", d.slippageCap, (v) => v >= 0 && v <= 0.5),
    snapshotMatchTol: numField(o, "snapshotMatchTol", d.snapshotMatchTol, (v) => v >= 0 && v <= 1),
    askAboveSignal: numField(o, "askAboveSignal", d.askAboveSignal, (v) => v >= 0 && v <= 1),
    betPct: numField(o, "betPct", d.betPct, inHalfOpen01),
    betMinUsd: numField(o, "betMinUsd", d.betMinUsd, nonNeg),
    cashFloorPct: numField(o, "cashFloorPct", d.cashFloorPct, (v) => v >= 0 && v <= 1),
    reserveUsd: numField(o, "reserveUsd", d.reserveUsd, nonNeg),
    maxPositions: numField(o, "maxPositions", d.maxPositions,
      (v) => Number.isInteger(v) && v >= 1 && v <= 10000),
    verifyCap: numField(o, "verifyCap", d.verifyCap,
      (v) => Number.isInteger(v) && v >= 1 && v <= 10000),
    stopDrop: numField(o, "stopDrop", d.stopDrop, inOpen01),
    tempStopDrop: numField(o, "tempStopDrop", d.tempStopDrop, inOpen01),
    decidedHigh: numField(o, "decidedHigh", d.decidedHigh, inOpen01),
    decidedLow: numField(o, "decidedLow", d.decidedLow, inOpen01),
  };
  if (!(out.bandLo < out.bandHi)) {
    out.bandLo = d.bandLo;
    out.bandHi = d.bandHi;
  }
  if (!(out.priorityBandLo < out.priorityBandHi)) {
    out.priorityBandLo = d.priorityBandLo;
    out.priorityBandHi = d.priorityBandHi;
  }
  if (!(out.decidedLow < out.decidedHigh)) {
    out.decidedLow = d.decidedLow;
    out.decidedHigh = d.decidedHigh;
  }
  // decided 쌍이 0.5를 사이에 두지 않으면(둘 다 한쪽) 모든 가격이
  // decided 판정이 돼 손절이 영구히 발화하지 않는다 — 순서가 맞아도
  // 쌍째로 기본값으로 되돌린다 (v0.3.38 감사 축 2).
  if (out.decidedLow >= 0.5 || out.decidedHigh <= 0.5) {
    out.decidedLow = d.decidedLow;
    out.decidedHigh = d.decidedHigh;
  }
  return out;
}

/**
 * 연동 화면 하단 요약 — 저장된 설정의 해석 결과(parseTradeSettings)로
 * 만든다. 기본값이면 기존 고정 문구와 글자까지 같아야 한다. 기온 값은
 * 요약에 넣지 않는다.
 */
export function settingsSummaryText(cfg) {
  const c = cfg || DEFAULT_TRADE_SETTINGS;
  const price = (v) => v.toFixed(2);
  const pct = (v) => String(parseFloat((v * 100).toFixed(2)));
  return `밴드 ${price(c.bandLo)}–${price(c.bandHi)} · 건당 평가금 ${pct(c.betPct)}% · 현금 ${pct(c.cashFloorPct)}% 유지 · 최대 ${c.maxPositions}건 · 손절 진입가 −${price(c.stopDrop)}, 그 외 만기 보유`;
}

/**
 * 뷰어 설정(bm_settings) 해석 — { tempNoEnabled, tempNoBandLo, tempNoBandHi,
 * tempEntryHourUtc, tempNoReserveSlots }.
 * 토글은 정확히 true일 때만 켜진 것으로 보고, 밴드 하한은 (0, 상한]
 * 범위를 벗어나면 기본값으로 되돌린다 (못 믿는 설정으로 사지 않는다).
 * 예약 슬롯 수는 0~TEMP_NO_MAX_RESERVE_SLOTS의 정수만 받고, 그 외
 * (음수·소수·문자열·범위 초과)는 기본값 1로 되돌린다.
 */
export function parseTempSettings(raw) {
  const o = settingsObject(raw);
  if (!o) {
    return {
      enabled: false,
      bandLo: TEMP_NO_DEFAULT_BAND_LO,
      bandHi: TEMP_NO_BAND_HI,
      entryHourUtc: TEMP_NO_ENTRY_HOUR_UTC,
      reserveSlots: TEMP_NO_DEFAULT_RESERVE_SLOTS,
    };
  }
  const hi = strictNum(o.tempNoBandHi);
  let bandHi = Number.isFinite(hi) && hi > 0 && hi <= 1 ? hi : TEMP_NO_BAND_HI;
  const lo = strictNum(o.tempNoBandLo);
  let bandLo = Number.isFinite(lo) && lo > 0 && lo <= TEMP_NO_BAND_HI
    ? lo
    : TEMP_NO_DEFAULT_BAND_LO;
  if (!(bandLo < bandHi)) {
    bandLo = TEMP_NO_DEFAULT_BAND_LO;
    bandHi = TEMP_NO_BAND_HI;
  }
  const hour = strictNum(o.tempEntryHourUtc);
  const entryHourUtc = Number.isInteger(hour) && hour >= 0 && hour <= 23
    ? hour
    : TEMP_NO_ENTRY_HOUR_UTC;
  const rs = strictNum(o.tempNoReserveSlots);
  const reserveSlots = Number.isInteger(rs)
    && rs >= 0 && rs <= TEMP_NO_MAX_RESERVE_SLOTS
    ? rs
    : TEMP_NO_DEFAULT_RESERVE_SLOTS;
  return { enabled: o.tempNoEnabled === true, bandLo, bandHi, entryHourUtc, reserveSlots };
}

// 테이커 수수료율 — feeType 카테고리 매핑(네이티브 ManualSell의
// feeRateForFeeType와 같은 표)이 1순위, 판정 불가면 시장의
// feeSchedule rate를 쓴다. 기온은 weather 카테고리라 0.05다.
function feeRateForFeeType(feeType) {
  if (feeType == null || feeType === "") return null;
  const f = String(feeType).toLowerCase();
  if (f.startsWith("crypto")) return 0.07;
  if (f.startsWith("politics") || f.startsWith("finance") || f.startsWith("tech")) return 0.04;
  if (f.startsWith("geopolitics")) return 0.0;
  return 0.05;
}

export function resolveTempFeeRate(m) {
  const byType = feeRateForFeeType(m?.feeType);
  if (byType != null) return byType;
  const rate = Number(m?.feeSchedule?.rate);
  return Number.isFinite(rate) && rate >= 0 ? rate : null;
}

/**
 * 기온 예약 슬롯의 실효값 — 예약이 전체 포지션 상한을 넘으면 메인이
 * 사이클마다 전량 미뤄지므로, 실효 예약은 maxPositions로 제한한다
 * (v0.3.38 감사 축 3). 두 설정이 모두 있는 판정 지점에서만 계산한다.
 */
export function effectiveTempReserveSlots(reserveSlots, maxPositions) {
  const r = Number.isFinite(reserveSlots) ? reserveSlots : TEMP_NO_DEFAULT_RESERVE_SLOTS;
  const m = Number.isFinite(maxPositions) ? maxPositions : MAX_POSITIONS;
  return Math.max(0, Math.min(r, m));
}

/**
 * 기온 슬롯 홀드 판정 — 홀드가 켜졌을 때 메인 후보를 이 사이클에서
 * 차단할지 정하는 순수 함수. 홀드 활성 자체는 드라이버가 정한다.
 * 메인 후보는 슬롯 reserveSlots개와 검증 reserveSlots건을 기온 몫으로
 * 비워 둔 지점부터 차단한다. slots는 건수 상한과 현금 기준 슬롯 수의
 * 최소값이라, 슬롯 차단은 건당 베팅액 × reserveSlots만큼의 현금도
 * 함께 비운다. 기온 후보 자체는 차단하지 않는다 (예약은 하한이지
 * 상한이 아니다). reserveSlots가 0이면 홀드가 없어 아무것도 차단하지
 * 않는다. 차단된 메인은 같은 사이클에 다시 타지 않는다 — 다시 태우면
 * 비워 둔 슬롯이 그 사이클 안에서 사라진다.
 */
export function tempReserveBlocksMain(strategy, slotsLeft, verified, reserveSlots = TEMP_NO_DEFAULT_RESERVE_SLOTS, verifyCap = VERIFY_CAP) {
  if (strategy === "temp_no") return false;
  if (!(reserveSlots > 0)) return false;
  return slotsLeft <= reserveSlots || verified >= verifyCap - reserveSlots;
}

/**
 * 기온 이벤트 목록 → No 페이드 후보. 후보는 메인 후보와 같은 선정
 * 패스(슬롯·검증 캡 공유)를 타며, excludeSlugs(메인 후보 slug)와
 * 겹치면 메인 쪽이 이긴다. 정렬은 만기 가까운 순, 동률은 No 가격
 * 높은 순 (백테스트의 평균 진입가 쪽부터 채운다).
 */
export function scanTempCandidates(events, heldSlugs, boughtSlugs, settings, nowMs, stats = null, excludeSlugs = null) {
  const out = [];
  if (!settings?.enabled) return out;
  const bandLo = settings.bandLo;
  const bandHi = settings.bandHi ?? TEMP_NO_BAND_HI;
  const entryHourUtc = settings.entryHourUtc ?? TEMP_NO_ENTRY_HOUR_UTC;
  const drop = (reason) => {
    if (stats) stats[reason] = (stats[reason] || 0) + 1;
  };
  for (const ev of events || []) {
    if (!ev || !isTempNoSlug(ev.slug)) continue;
    const endMs = Date.parse(ev.endDate || "");
    if (!Number.isFinite(endMs)) { drop("temp_end_bad"); continue; }
    if (!tempWindowOpen(nowMs, endMs, entryHourUtc)) { drop("temp_window"); continue; }
    for (const m of ev.markets || []) {
      if (!m.active || m.closed) { drop("temp_inactive"); continue; }
      if (m.acceptingOrders === false || m.archived === true) { drop("temp_not_accepting"); continue; }
      const slug = m.slug || "";
      if (heldSlugs.has(slug) || boughtSlugs.has(slug) || (excludeSlugs && excludeSlugs.has(slug))) {
        drop("temp_owned");
        continue;
      }
      if (!isExactTempTitle(m.groupItemTitle)) { drop("temp_not_exact"); continue; }
      let prices;
      try {
        prices = JSON.parse(m.outcomePrices || "[]").map(Number);
      } catch {
        drop("temp_price_bad");
        continue;
      }
      if (prices.length < 2 || !Number.isFinite(prices[0]) || !Number.isFinite(prices[1])) {
        drop("temp_price_bad");
        continue;
      }
      // No 가격 = Gamma outcomePrices[1] (Yes의 보수와 같은 값).
      const price = prices[1];
      if (price < bandLo || price > bandHi) { drop("temp_band_out"); continue; }
      out.push({
        slug,
        question: m.question || "",
        side: "no",
        price,
        endDate: m.endDate || ev.endDate || "",
        strategy: "temp_no",
        bandLo,
        bandHi,
        feeRate: resolveTempFeeRate(m),
      });
    }
  }
  out.sort((a, b) => a.endDate.localeCompare(b.endDate) || b.price - a.price);
  return out;
}

/**
 * 조회 날짜 집합의 기준 키 — nowMs가 속한 UTC 날짜 (ISO). 조회 범위가
 * 오늘+내일(UTC)이라 기준 날짜가 바뀌면 집합 자체가 달라진다.
 */
export function tempFetchDayKey(nowMs) {
  return new Date(nowMs).toISOString().slice(0, 10);
}

// 오늘+내일 기온 이벤트 조회 — 도시별 슬러그 직접 조회라 사이클마다
// 전수 호출하지 않게 15분 캐시를 둔다. 도시별 실패는 그 도시만
// 건너뛰고, 전부 실패한 조회는 캐시하지 않아 다음 사이클에 다시
// 시도한다. 후보 생성(scanTempCandidates)은 창 판정으로 당일 이벤트만
// 쓰므로, 내일 이벤트는 예약 홀드 판정에만 쓰인다. 캐시는 기준 UTC
// 날짜가 같은 동안만 유효하다 — 자정을 넘기면 날짜 집합이 달라져
// 전날 캐시가 새 집합(오늘·내일)을 덮지 않게 무효화한다.
let tempEventsCache = { atMs: 0, dayKey: "", events: null };
async function fetchTempEvents(nowMs) {
  const dayKey = tempFetchDayKey(nowMs);
  if (
    tempEventsCache.events &&
    tempEventsCache.dayKey === dayKey &&
    nowMs - tempEventsCache.atMs < TEMP_NO_CACHE_MS
  ) {
    return tempEventsCache.events;
  }
  const slugs = tempEventSlugsForFetch(nowMs);
  const events = [];
  let failures = 0;
  for (const slug of slugs) {
    try {
      const page = await fetchJson(
        `${GAMMA_EVENTS_URL}?${new URLSearchParams({ slug })}`,
      );
      if (Array.isArray(page) && page[0]) events.push(page[0]);
    } catch {
      failures += 1;
    }
  }
  if (failures >= slugs.length) {
    // 전수 실패는 "오늘 이벤트 없음"(빈 목록)과 구분해 null로 알린다 —
    // 호출자가 예약을 안전 방향(유지)으로 판정하게 하기 위해서다
    // (v0.3.39 감사 E). 부분 실패(일부 도시 성공)는 성공으로 취급한다.
    return null;
  }
  tempEventsCache = { atMs: nowMs, dayKey, events };
  return events;
}

function roundTick(price, tick) {
  const q = Math.floor(price / tick + 0.5);
  return Number((q * tick).toFixed(6));
}

/**
 * 매수 지정가 — 슬리피지 캡과 밴드 상한을 건 뒤 틱 배수로 정렬하고
 * [tick, 1 - tick]로 절단한다 (매도 floorPrice와 같은 방식). 밴드 상한
 * (기온 기본 0.998)이 틱 배수가 아니면 종전 식 min(roundTick(...),
 * bandHi)가 0.998 같은 비배수·priceValid 상한(1 - tick) 초과 가격을
 * 만들어 서명이 전건 실패했다 (v0.3.39 감사 축 1).
 */
export function buyLimitPrice(bestAsk, slippageCap, tick, bandHi) {
  let limit = roundTick(Math.min(bestAsk + slippageCap, bandHi), tick);
  if (limit > bandHi) {
    // 반올림이 밴드 상한을 넘었으면 상한 아래 가장 큰 틱 배수로 내린다
    limit = Number((Math.floor(bandHi / tick + 1e-9) * tick).toFixed(6));
  }
  return Math.min(Math.max(tick, limit), 1 - tick);
}

const state = {
  client: null,
  account: null,
  eoa: null,
  funder: null,
  running: false,
  timer: null,
  cycle: 0,
  gen: 0,
  sessionFills: 0,
  posGapWarned: false,
  boughtReconciled: false,
  reconcileFailLogged: false,
  updateInfo: null,
  updateNotifiedTag: "",
  onLog: () => {},
  onState: () => {},
  onUpdate: () => {},
  lastSnapshot: null,
  lastOpenRows: [],
  lastWinRows: null,
  lastLostRows: null,
  lastClosedRows: null,
  redeemKeyWarnedDay: "",
  settledBaseline: null,
  settledBaselineLoaded: false,
  submitCount: 0,
  hbTimer: null,
  watchTimer: null,
  lastCycleDoneMs: 0,
  cycleInFlight: false,
  cycleErrors: 0,
};

function log(line) {
  const t = new Date();
  const p = (n) => String(n).padStart(2, "0");
  state.onLog(`[${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}] ${line}`);
}

// ── 영속 미러 (부수1) ────────────────────────────────────────
// localStorage의 디스크 반영은 비동기라 프로세스 급사 시 직전 쓰기가
// 사라질 수 있다. 돈이 걸린 기록(매수·손절·리딤·정산 기준선)은 네이티브
// SharedPreferences에도 동기(commit)로 이중 기록하고, localStorage가
// 비어 있을 때만 미러에서 되살린다.
export function durableWrite(lsKey, value) {
  try {
    localStorage.setItem(lsKey, value);
  } catch {}
  try {
    window.AndroidBridge?.putDurable?.(lsKey, value);
  } catch {}
}

export function durableRead(lsKey) {
  let v = null;
  try {
    v = localStorage.getItem(lsKey);
  } catch {}
  if (v) return v;
  try {
    const m = window.AndroidBridge?.getDurable?.(lsKey);
    if (typeof m === "string" && m) {
      try {
        localStorage.setItem(lsKey, m);
      } catch {}
      return m;
    }
  } catch {}
  return v;
}

function loadDurableJsonList(key) {
  try {
    const a = JSON.parse(durableRead(key) || "[]");
    return Array.isArray(a) ? a : [];
  } catch {
    return [];
  }
}

// 기매수 기록이 손상됐는지 — 손상을 "기록 없음"과 구분하지 못하면 닫힌
// 종목 전체가 신규로 떨어져 재매수가 열린다 (2026-11-15 감사). 원문이
// 있는데 배열로 파싱되지 않으면 손상이다.
export function boughtCorrupted() {
  const raw = durableRead(LS.bought);
  if (!raw) return false;
  try {
    return !Array.isArray(JSON.parse(raw));
  } catch {
    return true;
  }
}

// 매수 기록 항목은 두 형태가 섞인다 — 기존 문자열(slug만)과, 전략 태그를
// 단 객체({slug, strategy})다. 기온 No 페이드 매수만 객체로 남겨 손절 폭
// 판정을 슬러그 패턴이 아니라 실제 전략 귀속으로 판정한다.
function boughtEntries() {
  try {
    const parsed = JSON.parse(durableRead(LS.bought) || "[]");
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function boughtEntrySlug(entry) {
  if (typeof entry === "string") return entry;
  if (entry && typeof entry === "object" && typeof entry.slug === "string") {
    return entry.slug;
  }
  return null;
}

function boughtSlugs() {
  const out = new Set();
  for (const e of boughtEntries()) {
    const s = boughtEntrySlug(e);
    if (s) out.add(s);
  }
  return out;
}

/**
 * 해당 slug의 매수 기록에 붙은 전략 태그 — 기온 매수면 "temp_no",
 * 태그가 없거나 기록 자체가 없으면 null (메인 매수로 간주한다).
 */
export function boughtStrategyOf(slug) {
  if (!slug) return null;
  // 첫 일치 항목만 보면 중복 항목 뒤에 숨은 temp_no 태그를 놓친다 —
  // 일치 항목을 전부 스캔해 temp_no가 하나라도 있으면 temp_no다
  // (v0.3.38 감사 경화).
  for (const e of boughtEntries()) {
    if (boughtEntrySlug(e) !== slug) continue;
    if (e && typeof e === "object" && e.strategy === "temp_no") return "temp_no";
  }
  return null;
}

export function markBought(slug, strategy) {
  const entries = boughtEntries().filter((e) => boughtEntrySlug(e) !== slug);
  entries.push(strategy === "temp_no" ? { slug, strategy: "temp_no" } : slug);
  durableWrite(LS.bought, JSON.stringify(entries));
  // 쓰기가 조용히 실패하면 잠금·기록이 동시에 없는 상태가 된다 — 다시
  // 읽어 실제로 남았는지 확인하고, 없으면 로그로 드러낸다.
  if (!boughtSlugs().has(slug)) {
    log(`매수 기록 저장 확인 실패 ${slug} — 저장소 쓰기가 반영되지 않았습니다`);
  }
}

// 기매수 기록 정화 — bought에는 체결분과 "결과 불명"으로 남긴 방어 기록이
// 섞인다. 방어 기록이 실제 체결로 이어지지 않았으면 그 종목은 영원히
// 후보에서 빠지는 미끼가 된다 (2026-11-15 실측: 후보 10건 전부 이 기록에
// 걸려 진입 0). 온체인 매수 이력과 현재 보유에 모두 없는 항목은 산 적이
// 없는 것으로 판정해 제거한다. 판정은 순수 함수로 분리해 테스트한다.
export function reconcileBought(boughtSet, onChainBuySlugs, heldSlugsSet) {
  const truth = new Set([...onChainBuySlugs, ...heldSlugsSet]);
  const kept = [];
  const dropped = [];
  for (const s of boughtSet) (truth.has(s) ? kept : dropped).push(s);
  return { kept, dropped };
}

const ACTIVITY_PAGE_CAP = 10;

async function fetchBuySlugs(funder) {
  // Data API v2 activity는 커서 전용이다 — offset을 붙이면 400으로 거부된다
  // (2026-11-15 실측: "'offset' is not a query param on this API").
  // positions와 같은 커서 순회 패턴을 쓴다.
  // 반환은 { slugs, complete } — 순회가 중간에 끊긴 부분 집합을 완전한
  // 진실로 채택하면 실제 체결 기록까지 정화 대상으로 지워져 재매수가
  // 열린다 (2026-11-15 감사). complete=false면 정화를 중단한다.
  const slugs = new Set();
  let cursor = null;
  for (let page = 0; page < ACTIVITY_PAGE_CAP; page++) {
    const params = new URLSearchParams({ user: funder, limit: "100" });
    if (cursor) params.set("cursor", cursor);
    const data = await fetchJson(`${DATA_API_V2}/activity?${params}`);
    const rows = Array.isArray(data) ? data : data?.data;
    if (!Array.isArray(rows)) return { slugs, complete: false };
    const hasMore = Boolean(data?.pagination?.has_more);
    if (!rows.length) {
      // 빈 페이지인데 뒤가 더 있다고 하면 부분 응답이다
      return { slugs, complete: !hasMore };
    }
    for (const r of rows) {
      if (r?.type === "TRADE" && r?.side === "BUY" && r?.slug) slugs.add(r.slug);
    }
    const next = data?.pagination?.next_cursor;
    if (!hasMore) return { slugs, complete: true };
    // 뒤가 더 있는데 진행할 커서가 없거나 순환하면 부분 응답이다 —
    // 완결로 판정하면 정화가 실체결 기록을 지울 수 있다 (v0.3.32 감사).
    if (!next || next === cursor) return { slugs, complete: false };
    cursor = next;
  }
  // 페이지 상한에 닿아 끊겼다 — 뒤에 남은 이력이 진실에서 빠져 있다
  return { slugs, complete: false };
}

// 엔진 가동 중 첫 스캔 전에 1회만 정화한다. 이력 조회에 실패하면
// 플래그를 세우지 않아 다음 사이클에 다시 시도한다 — 단, 실패는 조용히
// 묻지 않고 첫 실패를 로그로 남긴다 (v0.3.19에서 offset 400 실패가
// 무음으로 묻혀 정화가 영영 안 돈 전적).
async function reconcileBoughtOnce(snap) {
  let fetched;
  try {
    fetched = await fetchBuySlugs(state.funder);
  } catch (e) {
    if (!state.reconcileFailLogged) {
      state.reconcileFailLogged = true;
      log(`기매수 정화 실패 — 매수 이력 조회 오류: ${String(e?.message ?? e).slice(0, 100)} (다음 사이클 재시도)`);
    }
    return;
  }
  const cur = boughtSlugs();
  // 불완전한 이력(상한 도달·부분 응답)이거나, 기록이 있는데 온체인 매수가
  // 전무로 돌아오면 진실을 확정할 수 없다 — 지우지 않고 다음 기회에 미룬다.
  // 특히 빈 응답을 "매수 이력 전무"로 읽으면 손절·정산으로 비운 종목의
  // 기록까지 전량 삭제돼 재매수가 열린다.
  if (!fetched.complete || (cur.size > 0 && fetched.slugs.size === 0)) {
    if (!state.reconcileFailLogged) {
      state.reconcileFailLogged = true;
      log("기매수 정화 보류 — 매수 이력이 불완전하거나 비어 있어 기록을 지우지 않습니다 (다음 사이클 재시도)");
    }
    return;
  }
  state.boughtReconciled = true;
  if (!cur.size) return;
  const { kept, dropped } = reconcileBought(cur, fetched.slugs, snap.heldSlugs);
  if (!dropped.length) return;
  // 남은 항목은 원본 형태(전략 태그 포함) 그대로 다시 쓴다 — slug 문자열로
  // 되쓰면 기온 매수의 태그가 정화 한 번에 사라진다.
  const keptSet = new Set(kept);
  durableWrite(
    LS.bought,
    JSON.stringify(boughtEntries().filter((e) => keptSet.has(boughtEntrySlug(e)))),
  );
  log(
    `기매수 기록 정화 — 온체인 매수 이력 없는 ${dropped.length}건 제거: ` +
      dropped.slice(0, 5).join(", ") + (dropped.length > 5 ? " 외" : ""),
  );
}

// ── 비밀 저장 (Android Keystore 암호화) ─────────────────────
// 개인키·CLOB creds·Relayer 키는 localStorage에 암호문만 남긴다.
// 암호화/복호화는 네이티브 브리지(Android Keystore AES-GCM)가 하고,
// JS는 접두사 "enc1:"로 암호문과 레거시 평문을 구분한다.
// v0.3.14 첫 부팅 때 평문 저장분을 읽어 암호문으로 덮는다(마이그레이션) —
// 복호화 실패는 null로만 돌려주고, 연동 화면으로 안전하게 떨어지게 한다.
const SECRET_PREFIX = "enc1:";

export function storeSecret(lsKey, plain) {
  try {
    const bridge = window.AndroidBridge;
    if (bridge?.encryptSecret) {
      const enc = bridge.encryptSecret(String(plain));
      if (typeof enc === "string" && enc) {
        localStorage.setItem(lsKey, SECRET_PREFIX + enc);
        return true;
      }
      return false; // 브리지가 있는데 암호화에 실패 — 평문으로 저장하지 않는다
    }
    localStorage.setItem(lsKey, String(plain)); // 브라우저 미리보기·테스트 전용 폴백
    return true;
  } catch {
    return false;
  }
}

export function readSecret(lsKey) {
  let raw = null;
  try {
    raw = localStorage.getItem(lsKey);
  } catch {}
  if (!raw) return null;
  if (raw.startsWith(SECRET_PREFIX)) {
    try {
      const dec = window.AndroidBridge?.decryptSecret?.(raw.slice(SECRET_PREFIX.length));
      return typeof dec === "string" && dec ? dec : null;
    } catch {
      return null;
    }
  }
  return raw; // 레거시 평문 (마이그레이션 전 또는 브리지 없는 환경)
}

// 평문으로 남은 비밀을 암호문으로 덮는다. 이미 암호문이거나 없으면 무동작.
export function migrateSecrets() {
  for (const lsKey of [LS.key, LS.creds, LS.relayer]) {
    try {
      const raw = localStorage.getItem(lsKey);
      if (raw && !raw.startsWith(SECRET_PREFIX)) storeSecret(lsKey, raw);
    } catch {}
  }
}

// Relayer API 키 (자동 상환 제출 인증) — {key, address}를 암호화 저장한다.
function readRelayerCreds() {
  const raw = readSecret(LS.relayer);
  if (!raw) return null;
  try {
    const o = JSON.parse(raw);
    if (o && typeof o.key === "string" && o.key && typeof o.address === "string") {
      return { key: o.key, address: o.address };
    }
  } catch {}
  return null;
}

// SDK 호출 타임아웃 — SDK 내부 HTTP가 응답 없이 매달리면 그 await를 기다리는
// 사이클 루프 전체가 영구 정지한다 (다음 사이클 타이머는 runCycle이 끝나야
// 걸린다). 모든 메서드 호출을 감싸 제한 시간을 넘기면 오류로 끊는다.
// 제출 계열이 시간 초과로 끊기면 호출부의 기존 catch가 "결과 불명"으로
// 처리하므로 정합한다.
export function withClientTimeout(client, timeoutMs = FETCH_TIMEOUT_MS) {
  return new Proxy(client, {
    get(target, prop) {
      const v = Reflect.get(target, prop, target);
      if (typeof v !== "function") return v;
      return (...args) => {
        let timer;
        const timeout = new Promise((_, reject) => {
          timer = setTimeout(
            () =>
              reject(
                new Error(
                  `SDK 호출 시간 초과 (${Math.round(timeoutMs / 1000)}초) ${String(prop)}`,
                ),
              ),
            timeoutMs,
          );
        });
        return Promise.race([Reflect.apply(v, target, args), timeout]).finally(() =>
          clearTimeout(timer),
        );
      };
    },
  });
}

function buildClient(signer, creds, funder) {
  return withClientTimeout(
    new ClobClient({
      host: CLOB_HOST,
      chain: Chain.POLYGON,
      signer,
      creds,
      signatureType: SignatureTypeV2.POLY_1271,
      funderAddress: funder,
    }),
  );
}

// 개인키 입력의 유일한 정규화 지점 — 0x 접두사는 있어도 없어도 된다.
function normalizePrivateKey(raw) {
  const s = String(raw ?? "").trim();
  if (/^[0-9a-fA-F]{64}$/.test(s)) return `0x${s}`;
  if (/^0x[0-9a-fA-F]{64}$/.test(s)) return s;
  throw new Error("개인키 형식이 아닙니다 (64자리 hex).");
}

function makeSigner(privateKey) {
  const account = privateKeyToAccount(privateKey);
  return {
    account,
    wallet: createWalletClient({
      account,
      chain: polygon,
      transport: http(POLYGON_RPCS[0], { timeout: 15_000 }),
    }),
  };
}

// 부팅 화면 판정 — 저장된 키가 있으면 연동 화면은 어떤 경우에도 첫 화면이
// 아니다. 실행 의도까지 있으면 실행 중 상태로 복원 표시부터 한다
// (실제 재개 확정은 restore()가 미러 대조로 다시 판정한다).
export function bootView(hasKey, runIntent) {
  if (!hasKey) return "link";
  return runIntent ? "running" : "stopped";
}

// 스냅샷 로컬 캐시 — 재진입 시 네트워크 왕복을 기다리지 않고 마지막 숫자를
// 즉시 표시하기 위한 직렬화 쌍. 핵심 숫자(현금·평가금)가 숫자가 아닌 캐시는
// 버린다: 깨진 캐시를 그럴듯한 숫자로 보여주는 게 자리표시보다 나쁘다.
// 보유 종목 행 정규화 — 뷰어 종목 목록·수동 매도가 쓰는 필드만 추리고,
// 형식이 깨진 행은 버린다. 식별자(slug·title·token_id)가 전혀 없는 행은
// 표시도 매도도 할 수 없어 제외한다. 숫자는 유한한 값만, 아니면 null.
function snapshotPositionsOf(rows) {
  if (!Array.isArray(rows)) return [];
  const strOrNull = (v) => (typeof v === "string" && v ? v : null);
  const numOrNull = (v) => (Number.isFinite(v) ? v : null);
  const out = [];
  for (const p of rows) {
    if (!p || typeof p !== "object" || Array.isArray(p)) continue;
    const slug = strOrNull(p.slug);
    const title = strOrNull(p.title);
    const tokenId = strOrNull(p.token_id);
    if (!slug && !title && !tokenId) continue;
    out.push({
      slug,
      token_id: tokenId,
      title: title || slug,
      outcome: strOrNull(p.outcome),
      end_date: strOrNull(p.end_date),
      avg_price: numOrNull(p.avg_price),
      current_price: numOrNull(p.current_price),
      current_size: numOrNull(p.current_size),
      current_value: numOrNull(p.current_value),
      fee_rate: numOrNull(p.fee_rate),
    });
  }
  return out;
}

export function snapshotCacheOf(snap) {
  if (!snap) return null;
  const num = (v) => (Number.isFinite(v) ? v : null);
  return {
    cash: num(snap.cash),
    positionsValue: num(snap.positionsValue),
    equity: num(snap.equity),
    openCount: num(snap.openCount),
    settledCount: num(snap.settledCount),
    settledWins: num(snap.settledWins),
    settledLosses: num(snap.settledLosses),
    settledStopLoss: num(snap.settledStopLoss),
    settledRealizedPnl: num(snap.settledRealizedPnl),
    positionsPnlPct: num(snap.positionsPnlPct),
    heldSlugs: [...(snap.heldSlugs || [])].filter((s) => typeof s === "string"),
    cashFloor: num(snap.cashFloor),
    available: num(snap.available),
    bet: num(snap.bet),
    slots: num(snap.slots),
    positions: snapshotPositionsOf(snap.positions),
  };
}

export function snapshotFromCache(raw) {
  if (!raw) return null;
  let o = raw;
  if (typeof raw === "string") {
    try {
      o = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!o || typeof o !== "object") return null;
  if (!Number.isFinite(o.cash) || !Number.isFinite(o.equity)) return null;
  const numOr = (v, fallback) => (Number.isFinite(v) ? v : fallback);
  const numOrNull = (v) => (Number.isFinite(v) ? v : null);
  return {
    cash: o.cash,
    positionsValue: numOr(o.positionsValue, 0),
    equity: o.equity,
    openCount: numOr(o.openCount, 0),
    settledCount: numOrNull(o.settledCount),
    settledWins: numOrNull(o.settledWins),
    settledLosses: numOrNull(o.settledLosses),
    settledStopLoss: numOrNull(o.settledStopLoss),
    settledRealizedPnl: numOrNull(o.settledRealizedPnl),
    positionsPnlPct: numOrNull(o.positionsPnlPct),
    heldSlugs: new Set(
      (Array.isArray(o.heldSlugs) ? o.heldSlugs : []).filter(
        (s) => typeof s === "string",
      ),
    ),
    cashFloor: numOr(o.cashFloor, RESERVE_USD),
    available: numOr(o.available, 0),
    bet: numOr(o.bet, betUsdFor(o.equity)),
    slots: numOr(o.slots, 0),
    // 종목 목록은 캐시를 거쳐도 살아남아야 한다 — 이 필드를 빠뜨리면
    // 뷰어가 보유 종목을 영영 못 본다 (v0.3.31~32 실기기 결함).
    positions: snapshotPositionsOf(o.positions),
  };
}

function cachedSnapshot() {
  try {
    return snapshotFromCache(localStorage.getItem(LS.snap));
  } catch {
    return null;
  }
}

async function clobCash() {
  const bal = await state.client.getBalanceAllowance({
    asset_type: AssetType.COLLATERAL,
  });
  return round2(Number(BigInt(bal.balance || "0")) / 1e6);
}

async function snapshot(cfg) {
  const cash = await clobCash();
  const positions = await fetchPositions(state.funder);
  const positionsValue = round2(await fetchPositionsValue(state.funder));
  const equity = round2(cash + positionsValue);
  let winRows = null;
  let lostRows = null;
  let closedRows = null;
  try {
    [winRows, lostRows, closedRows] = await Promise.all([
      fetchPositions(state.funder, "REDEEMABLE"),
      fetchPositions(state.funder, "REDEEMABLE_LOST"),
      fetchPositions(state.funder, "CLOSED"),
    ]);
  } catch {
    // 정산 집계를 못 읽으면 settled*는 null로 남아 화면이 미표시한다
  }
  const book = summarizeBook(
    positions, winRows, lostRows, closedRows, stopLossRecords(),
    new Set([...stopLossAttemptedTokens(), ...manualSellAttemptedTokens()]),
    manualSellRecords(),
  );
  state.lastClosedRows = closedRows;
  // 설정은 사이클당 1회만 파싱한다 — runCycle이 파싱한 cfg를 그대로 쓰고,
  // 사이클 밖 호출자(연동·복원)는 인자가 없어 이 진입점에서 새로 파싱한다
  // (v0.3.38 감사 축 1/4).
  const s = sizing(cash, equity, book.openCount, cfg ?? parseTradeSettings(durableRead(LS.settings)));
  // 종목 목록·손절 대상은 장부와 같은 단일 기준으로 추린다 — status 없는
  // 보유 행이 목록에서도 손절에서도 빠지지 않게 한다 (네이티브 패리티).
  const openRows = positions.filter(isOpenRow);
  const snap = {
    cash,
    positionsValue,
    equity,
    openCount: book.openCount,
    settledCount: book.settledCount,
    settledWins: book.settledWins,
    settledLosses: book.settledLosses,
    settledStopLoss: book.settledStopLoss,
    settledRealizedPnl: book.settledRealizedPnl,
    positionsPnlPct: book.positionsPnlPct,
    heldSlugs: book.heldSlugs,
    stopLossSlugs: book.stopLossSlugs,
    positions: openRows.map((p) => snapshotPositionsOf([p])[0]).filter(Boolean),
    ...s,
  };
  state.lastSnapshot = snap;
  state.lastOpenRows = openRows;
  state.lastWinRows = winRows;
  state.lastLostRows = lostRows;
  try {
    const cache = snapshotCacheOf(snap);
    if (cache) {
      const json = JSON.stringify(cache);
      localStorage.setItem(LS.snap, json);
      // 서비스 엔진과 뷰어 액티비티가 확실히 공유하는 통로로도 남긴다
      try {
        window.AndroidBridge?.putSnapCache?.(json);
      } catch {}
    }
  } catch {}
  return snap;
}

async function verifyCandidate(cand, bet, cfg = DEFAULT_TRADE_SETTINGS, entryHourUtc = TEMP_NO_ENTRY_HOUR_UTC) {
  const reject = (reason) => ({ ok: false, reason });
  const page = await fetchJson(
    `${GAMMA_MARKETS_URL}?${new URLSearchParams({ slug: cand.slug })}`,
  );
  const m = Array.isArray(page) ? page[0] : null;
  if (!m) return reject("gamma_market_missing");
  if (isSportsMarket(m)) return reject("sports");
  if (m.acceptingOrders === false || m.archived === true) return reject("market_not_accepting");
  let tokens, prices;
  try {
    // 거래 식별자는 마켓의 프로토콜 버전으로 고른다 (문서 Market Details — Market Outcomes):
    // v2는 positionIds 배열을 그대로, v1(CTF)은 clobTokenIds JSON 문자열을 파싱한다.
    tokens = m.version === "v2" && Array.isArray(m.positionIds) && m.positionIds.length >= 2
      ? m.positionIds
      : JSON.parse(m.clobTokenIds || "[]");
    prices = JSON.parse(m.outcomePrices || "[]").map(Number);
  } catch {
    return reject("gamma_parse_error");
  }
  const idx = cand.side === "yes" ? 0 : 1;
  if (tokens.length <= idx || prices.length <= idx) return reject("gamma_shape_error");
  if (!Number.isFinite(prices[0]) || !Number.isFinite(prices[1])) {
    return reject("gamma_shape_error");
  }
  // 스캔 후 상태가 바뀐 마켓은 사지 않는다 — 활성·종료·만기를 스캔과
  // 같은 규칙으로 다시 본다.
  if (!m.active || m.closed) return reject("market_inactive");
  // 기온 후보는 자기 밴드와 진입 창을 들고 온다 — 메인 밴드(0.60~0.90)와
  // 45일 만기 창을 적용하면 전건 탈락하므로 전략별로 갈라서 본다.
  const isTemp = cand.strategy === "temp_no";
  const bandLo = isTemp ? cand.bandLo : cfg.bandLo;
  const bandHi = isTemp ? cand.bandHi : cfg.bandHi;
  if (isTemp) {
    const endMs = Date.parse(m.endDate || "");
    if (!Number.isFinite(endMs)) return reject("end_out_of_range");
    if (!tempWindowOpen(Date.now(), endMs, entryHourUtc)) return reject("end_out_of_range");
  } else {
    const todayIso = new Date().toISOString().slice(0, 10);
    const endMaxIso = new Date(Date.now() + cfg.endWindowDays * 86400_000).toISOString().slice(0, 10);
    const end = (m.endDate || "").slice(0, 10);
    if (!(todayIso < end && end <= endMaxIso)) return reject("end_out_of_range");
  }
  if (Math.abs(prices[idx] - cand.price) > cfg.snapshotMatchTol) {
    return reject("snapshot_mismatch");
  }
  // 선택한 쪽이 여전히 우세 쪽인지 다시 본다 — 스캔 뒤 가격이 뒤집히면
  // 편차 검사만으로는 진 쪽을 사게 된다.
  if (prices[idx] < prices[1 - idx]) return reject("side_flipped");
  const tokenId = tokens[idx];
  const book = await state.client.getOrderBook(tokenId);
  const asks = (book.asks || [])
    .map((a) => ({ price: Number(a.price), size: Number(a.size) }))
    .filter((a) => Number.isFinite(a.price) && Number.isFinite(a.size))
    .sort((a, b) => a.price - b.price);
  const bids = (book.bids || [])
    .map((b) => ({ price: Number(b.price), size: Number(b.size) }))
    .filter((b) => Number.isFinite(b.price) && Number.isFinite(b.size))
    .sort((a, b) => b.price - a.price);
  if (!asks.length) return reject("no_asks");
  if (!bids.length) return reject("no_bids");
  const bestAsk = asks[0].price;
  const bestBid = bids[0].price;
  if (bestAsk < bandLo || bestAsk > bandHi) return reject("ask_out_of_band");
  // 신호가보다 크게 비싸진 호가는 사지 않는다 — 슬리피지 상한이 호가
  // 기준뿐이면 신호 0.75를 0.90에 사는 경로가 정상으로 통과한다.
  if (bestAsk > cand.price + cfg.askAboveSignal) return reject("ask_above_signal");
  if (bestAsk - bestBid > cfg.spreadMax + 1e-9) return reject("spread_too_wide");
  if (asks[0].size * bestAsk < Math.max(cfg.askDepthMinUsd, bet)) return reject("ask_depth_low");
  const tick = Number(book.tick_size || (await state.client.getTickSize(tokenId)));
  const limitPrice = buyLimitPrice(bestAsk, cfg.slippageCap, tick, bandHi);
  // 최소 주문 수량 검사용 추정치 — 실제 주식수는 시장가 주문이 금액에서 역산한다
  const estShares = bet / limitPrice;
  const minSize = Number(book.min_order_size || 0);
  if (estShares < minSize) return reject("below_min_order_size");
  return {
    ok: true,
    tokenId,
    limitPrice,
    estShares,
    tickSize: String(tick),
    negRisk: Boolean(book.neg_risk),
  };
}

// 검증 탈락 사유의 표시 이름 — 모르는 사유는 원문 그대로 보여준다
const REJECT_LABELS = {
  sports: "스포츠",
  spread_too_wide: "스프레드",
  ask_out_of_band: "밴드이탈",
  snapshot_mismatch: "가격변동",
  no_asks: "매도호가없음",
  no_bids: "매수호가없음",
  ask_depth_low: "호가깊이부족",
  below_min_order_size: "최소수량미달",
  gamma_market_missing: "마켓없음",
  market_not_accepting: "주문불가",
  gamma_parse_error: "응답파싱오류",
  gamma_shape_error: "응답형식오류",
  verify_error: "검증오류",
  side_flipped: "우세뒤집힘",
  ask_above_signal: "신호가초과",
  market_inactive: "마켓비활성",
  end_out_of_range: "만기범위이탈",
};

// 사이클 결과를 한 줄로 집계한다. 탈락을 건별로 찍으면 로그가 폭발하므로
// 사유별 카운트만 남긴다 — "왜 안 샀는지"가 사이클마다 보이게 하는 게 목적.
const SCAN_DROP_LABELS = {
  inactive: "비활성",
  not_accepting: "주문중단",
  short_term: "단기코인",
  sports: "스포츠",
  owned: "보유·기매수",
  end_out: "만기밖",
  volume_low: "거래량미달",
  price_bad: "가격형식",
  band_out: "밴드밖",
};

export function summarizeCycle({ candidates, verified, rejects, filled, unfilled, failed, scan = null }) {
  const parts = [`사이클 요약 · 후보 ${candidates} · 검증 ${verified}`];
  if (scan) {
    const drops = Object.entries(scan)
      .filter(([k, n]) => k !== "total" && n > 0)
      .sort((a, b) => b[1] - a[1]);
    const dropText = drops.length
      ? ` (${drops.map(([k, n]) => `${SCAN_DROP_LABELS[k] || k} ${n}`).join("·")})`
      : "";
    parts.push(`조회 ${scan.total ?? 0}${dropText}`);
  }
  const entries = Object.entries(rejects || {})
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]);
  if (entries.length) {
    const text = entries
      .map(([reason, n]) => `${REJECT_LABELS[reason] || reason} ${n}`)
      .join("·");
    parts.push(`탈락 ${text}`);
  }
  parts.push(`제출 ${filled}`);
  if (unfilled > 0) parts.push(`미체결 ${unfilled}`);
  if (failed > 0) parts.push(`실패 ${failed}`);
  return parts.join(" · ");
}

const isFailsafe = (text) => {
  const t = (text || "").toLowerCase();
  return FAILSAFE_KEYWORDS.some((k) => t.includes(k));
};

// 체결 판정은 응답의 실제 필드로만 한다. CLOB는 FOK가 미체결이어도
// success:true + status:"unmatched"로 응답하므로, errorMsg 유무만으로 보면
// 체결 안 된 주문을 매수로 오인한다 (2026-11-14 실계정 사고: 잔고 불변인데
// "매수" 로그 2건). 체결 증거(status matched/filled·트랜잭션 해시·체결
// ID·실제 지불액)가 하나라도 있어야 체결로 인정한다.
// SDK는 throwOnError 없이는 거절을 예외가 아니라 객체로 반환한다:
// HTTP 오류는 {error: <본문>, status: <HTTP 코드>}, 네트워크 오류는
// {error: <메시지>}. error 필드를 보지 않으면 거절이 전부 status 없는
// 미체결로 떨어진다 (2026-11-14 2차 사고).
export function judgeFill(resp) {
  if (!resp || typeof resp !== "object") {
    return { filled: false, kind: "unfilled", status: "" };
  }
  if (resp.error) {
    const err = resp.error;
    const status = String(resp.status ?? "");
    // HTTP 상태가 없는 error는 서버의 명시 거부가 아니라 전송 단계
    // 실패(네트워크 단절·응답 유실)다 — 주문이 접수·체결됐을 수 있어
    // 결과 불명으로 가른다 (브리지는 네트워크 실패를 status 0의
    // {error} 객체로 돌려준다). 명시 거부와 같은 실패로 처리하면
    // 체결됐는데 방어 기록이 안 남아 중복 매수가 열린다.
    const transport = !status || status === "0";
    return {
      filled: false,
      kind: transport ? "unknown" : "error",
      status,
      error: typeof err === "string" ? err : JSON.stringify(err),
    };
  }
  if (resp.errorMsg) {
    return {
      filled: false,
      kind: "error",
      status: String(resp.status || ""),
      error: String(resp.errorMsg),
    };
  }
  if (resp.success === false) {
    return { filled: false, kind: "rejected", status: String(resp.status || "") };
  }
  const status = String(resp.status || "").toLowerCase();
  const hashes = Array.isArray(resp.transactionsHashes) ? resp.transactionsHashes : [];
  const trades = Array.isArray(resp.tradeIDs) ? resp.tradeIDs : [];
  const making = Number(resp.makingAmount || 0);
  const filled = resp.success === true &&
    (status === "matched" || status === "filled" || hashes.length > 0 ||
      trades.length > 0 || making > 0);
  return filled
    ? { filled: true, kind: "filled", status }
    : { filled: false, kind: "unfilled", status };
}

// ── 제출 잠금 (중복 체결 방지) ───────────────────────────────
// 2026-11-14 실계좌에서 같은 후보에 건당 주문이 2번 체결된 결함의 수정.
// 제출 시작(서명)부터 결과 확정(체결/미체결/불명 판정)까지 slug 단위로 잠가
// 재진입을 막는다. 세대 가드와 별개로 동작하므로, 서명·전송 await 사이에
// 재시작·재개가 끼어 루프가 겹쳐도 두 번째 제출은 잠금에서 걸린다.
const submitLocks = new Set();

export function tryAcquireSubmitLock(key) {
  if (submitLocks.has(key)) return false;
  submitLocks.add(key);
  return true;
}

export function releaseSubmitLock(key) {
  submitLocks.delete(key);
}

export function isSubmitLocked(key) {
  return submitLocks.has(key);
}

// ── 제출 진행 가드 (F1) ─────────────────────────────────────
// 제출(서명·전송·확정)이 진행 중인 동안은 워치독이 페이지를 재로드하지
// 않게 네이티브에 알린다. 재로드가 postOrder 대기를 끊으면 체결 기록이
// 남지 않아 중복 체결 창이 다시 열리기 때문이다. 카운터가 0↔1로 바뀔
// 때만 브리지를 부르고, 페이지가 (재)로드되면 restore가 가드를 해제한다.
function setNativeSubmitFlag(on) {
  try {
    window.AndroidBridge?.setSubmitInFlight?.(on);
  } catch {}
}

export function beginSubmit() {
  state.submitCount += 1;
  if (state.submitCount === 1) setNativeSubmitFlag(true);
}

export function endSubmit() {
  if (state.submitCount <= 0) {
    state.submitCount = 0;
    return;
  }
  state.submitCount -= 1;
  if (state.submitCount === 0) setNativeSubmitFlag(false);
}

export function submitInFlight() {
  return state.submitCount > 0;
}

// ── 돈 이벤트 알림 ───────────────────────────────────────────
// 종목명·마켓명은 넣지 않는다 — 금액·가격·실현손익만.
function signedUsd(v) {
  return `${v < 0 ? "-$" : "+$"}${Math.abs(v)}`;
}

function notifyMoney(title, body) {
  try {
    window.AndroidBridge?.notify?.(title, body);
  } catch {}
}

// ── 손절 ─────────────────────────────────────────────────────
// 판정은 순수 함수로 분리한다 (테스트 대상). 가격은 Gamma outcomePrices만
// 쓴다 — 조회 실패·빈 응답이면 null을 돌려 그 건을 스킵하게 한다.
// 포지션의 outcome 인덱스는 페이퍼와 같이 Gamma outcomes 이름 대조가
// 1순위, 실패하면 Data API 행의 outcome_index로 폴백한다.
export function stopLossVerdict(entry, current, drop = STOP_DROP, decidedHigh = DECIDED_HIGH, decidedLow = DECIDED_LOW) {
  if (!Number.isFinite(entry) || !Number.isFinite(current)) return "skip";
  if (current >= decidedHigh || current <= decidedLow) return "decided";
  // 경계 허용 오차 — 십진으로 정확히 −drop인 쌍(0.30→0.05 등)이
  // double 연산 오차로 미발화하는 것을 막는다 (v0.3.32 감사).
  if (current <= entry - drop + 1e-9) return "triggered";
  return "holding";
}

export function pickOutcomeIndex(outcomes, row) {
  if (Array.isArray(outcomes) && outcomes.length) {
    const name = String(row?.outcome ?? "").trim().toLowerCase();
    if (name) {
      const i = outcomes.findIndex((o) => String(o).trim().toLowerCase() === name);
      if (i >= 0) return i;
    }
  }
  const idx = Number(row?.outcome_index);
  return Number.isInteger(idx) && idx >= 0 ? idx : -1;
}

async function fetchGammaPrices(slug) {
  const page = await fetchJson(
    `${GAMMA_MARKETS_URL}?${new URLSearchParams({ slug })}`,
  );
  const m = Array.isArray(page) ? page[0] : null;
  if (!m) return null;
  let outcomes, prices;
  try {
    outcomes = JSON.parse(m.outcomes || "[]");
    prices = JSON.parse(m.outcomePrices || "[]").map(Number);
  } catch {
    return null;
  }
  if (!Array.isArray(outcomes) || !outcomes.length) return null;
  if (outcomes.length !== prices.length) return null;
  if (prices.some((p) => !Number.isFinite(p))) return null;
  return { outcomes, prices };
}

// 손절 실행 원장 — 사후 판정용 영속 기록. 정산 집계(summarizeBook)가
// token_id로 대조해 현재가가 아닌 매도 실현손익으로 승패를 가른다.
// 최근 STOPLOSS_RECENT_CAP건은 전체 기록을 유지하고, 그보다 오래된 건은
// 정산 분류에 필요한 {token_id, realized_pnl}만 아카이브로 옮긴다 (부수4 —
// 캡으로 밀려난 건이 CLOSED 행의 현재가로 재분류돼 회계가 뒤집히지 않게).
export const STOPLOSS_RECENT_CAP = 200;
export const STOPLOSS_ARCHIVE_CAP = 10_000;

function parseStopLossList(raw) {
  try {
    const arr = JSON.parse(raw || "[]");
    if (!Array.isArray(arr)) return [];
    return arr.filter(
      (r) => r && typeof r.token_id === "string" && Number.isFinite(Number(r.realized_pnl)),
    );
  } catch {
    return [];
  }
}

export function stopLossRecords() {
  return [
    ...parseStopLossList(durableRead(LS.stopLossArchive)),
    ...parseStopLossList(durableRead(LS.stopLoss)),
  ];
}

export function recordStopLoss(rec) {
  const recent = parseStopLossList(durableRead(LS.stopLoss));
  recent.push(rec);
  let archive = parseStopLossList(durableRead(LS.stopLossArchive));
  while (recent.length > STOPLOSS_RECENT_CAP) {
    const old = recent.shift();
    // slug는 재매수 차단 집합(stopLossSlugs)의 근거라 아카이브에서도
    // 보존한다 — 버리면 캡 초과분부터 차단이 풀린다 (v0.3.32 감사).
    archive.push({ token_id: old.token_id, slug: old.slug, realized_pnl: Number(old.realized_pnl) });
  }
  if (archive.length > STOPLOSS_ARCHIVE_CAP) {
    archive = archive.slice(-STOPLOSS_ARCHIVE_CAP);
  }
  durableWrite(LS.stopLoss, JSON.stringify(recent));
  durableWrite(LS.stopLossArchive, JSON.stringify(archive));
}

// 결과 불명으로 끝난 손절 매도의 토큰 기록 — 실제로는 체결됐는데 원장이
// 없으면, 그 마켓이 나중에 승으로 정산될 때 CLOSED 행의 최종가만 보고
// "승"으로 뒤집혀 손절 사후 판정 원장이 오염된다 (2026-11-15 감사).
// summarizeBook이 이 집합의 토큰은 최종가가 아니라 realized_pnl 부호로
// 판정한다. 확정 기록(recordStopLoss)이 생긴 토큰은 그쪽이 우선한다.
export function stopLossAttemptedTokens() {
  try {
    const parsed = JSON.parse(durableRead(LS.stopLossAttempted) || "[]");
    return new Set(Array.isArray(parsed) ? parsed.filter((t) => typeof t === "string") : []);
  } catch {
    return new Set();
  }
}

// 수동 매도 원장·시도 토큰 — 네이티브가 쓴 기록을 장부 집계에 반영한다.
// 기록 형식이 손절과 같아 같은 파싱 필터를 쓴다 (v0.3.33 감사).
// 아카이브로 회전된 기록·토큰도 같은 입력에 포함한다 (v0.3.35 감사 축 3).
export function manualSellRecords() {
  return [
    ...parseStopLossList(durableRead(LS.manualSellArchive)),
    ...parseStopLossList(durableRead(LS.manualSell)),
  ];
}

export function manualSellAttemptedTokens() {
  const out = new Set();
  for (const key of [LS.manualSellAttemptedArchive, LS.manualSellAttempted]) {
    try {
      const parsed = JSON.parse(durableRead(key) || "[]");
      if (Array.isArray(parsed)) {
        for (const t of parsed) {
          if (typeof t === "string") out.add(t);
        }
      }
    } catch {}
  }
  return out;
}

export function stopLossAttemptedSlugs() {
  try {
    const parsed = JSON.parse(durableRead(LS.stopLossAttemptedSlugs) || "[]");
    return new Set(Array.isArray(parsed) ? parsed.filter((s) => typeof s === "string") : []);
  } catch {
    return new Set();
  }
}

function recordStopLossAttempt(tokenId, slug) {
  if (tokenId) {
    const set = stopLossAttemptedTokens();
    if (!set.has(tokenId)) {
      set.add(tokenId);
      durableWrite(LS.stopLossAttempted, JSON.stringify([...set].slice(-200)));
    }
  }
  // slug도 함께 남긴다 — 실제로는 체결됐는데 bm_bought에 없는 포지션이면
  // 체결 기록도 보유도 사라져 재매수 차단이 뚫린다 (v0.3.35 감사 축 2).
  if (slug) {
    const slugs = stopLossAttemptedSlugs();
    if (!slugs.has(slug)) {
      slugs.add(slug);
      durableWrite(LS.stopLossAttemptedSlugs, JSON.stringify([...slugs].slice(-10000)));
    }
  }
}

async function executeStopLossSell(row, current, cfg = DEFAULT_TRADE_SETTINGS) {
  if (!tryAcquireSubmitLock(row.slug)) {
    log(`손절 제출 잠금 중 — 건너뜀 ${row.slug}`);
    return false;
  }
  try {
    beginSubmit();
    try {
      return await executeStopLossSellLocked(row, current, cfg);
    } finally {
      endSubmit();
    }
  } finally {
    releaseSubmitLock(row.slug);
  }
}

async function executeStopLossSellLocked(row, current, cfg = DEFAULT_TRADE_SETTINGS) {
  const slug = row.slug;
  const entry = Number(row.avg_price);
  // 매도 수량은 CLOB 조건부 토큰 잔고가 정본 (Data API 수량은 표시값일 수 있다)
  let shares = Number(row.current_size);
  try {
    const bal = await state.client.getBalanceAllowance({
      asset_type: AssetType.CONDITIONAL,
      token_id: row.token_id,
    });
    const exact = Number(BigInt(bal.balance || "0")) / 1e6;
    if (Number.isFinite(exact) && exact > 0) shares = exact;
  } catch {}
  // 시장가 주문은 SDK가 주식수를 소수 2자리로 내림한다 — 0.01 미만은 먼지라 건너뜐다
  if (!Number.isFinite(shares) || shares < 0.01) {
    log(`손절 건너뜀 ${slug} — 매도 가능 수량 부족 (${shares})`);
    return false;
  }
  const book = await state.client.getOrderBook(row.token_id);
  const tick = Number(book.tick_size || (await state.client.getTickSize(row.token_id)));
  const floorPrice = Math.min(
    Math.max(tick, roundTick(current - cfg.slippageCap, tick)),
    1 - tick,
  );
  let signed;
  try {
    signed = await state.client.createMarketOrder(
      {
        tokenID: row.token_id,
        side: Side.SELL,
        amount: shares,
        price: floorPrice,
        orderType: OrderType.FOK,
      },
      { tickSize: String(tick), negRisk: Boolean(book.neg_risk) },
    );
  } catch (e) {
    log(`손절 매도 서명 실패 ${slug}: ${String(e.message ?? e).slice(0, 120)}`);
    return false;
  }
  let resp;
  try {
    resp = await state.client.postOrder(signed, OrderType.FOK);
  } catch (e) {
    // 제출됐으나 결과를 모른다 — 체결됐을 수 있어도 다음 사이클이 포지션
    // 실재로 다시 판정한다 (남아 있으면 재시도, 없으면 종료). 실제로
    // 체결된 경우에 대비해 시도 토큰을 남겨 정산 판정 오염을 막는다.
    recordStopLossAttempt(row.token_id, row.slug);
    log(`손절 매도 결과 불명 ${slug}: ${String(e.message ?? e).slice(0, 120)} — 다음 사이클에 재확인`);
    return false;
  }
  const verdict = judgeFill(resp);
  if (!verdict.filled) {
    // 전송 실패 형태면 실제로는 체결됐을 수 있다 — 시도 토큰을 남긴다
    if (verdict.kind === "unknown") recordStopLossAttempt(row.token_id, row.slug);
    const msg = verdict.error || `미체결 (status: ${verdict.status || "없음"})`;
    log(`손절 매도 실패 ${slug}: ${String(msg).slice(0, 120)} · 응답 원문: ${JSON.stringify(resp).slice(0, 200)}`);
    return false;
  }
  const soldShares = Number(resp.makingAmount) || shares;
  const proceeds = round2(Number(resp.takingAmount) || soldShares * current);
  const exitPrice = soldShares > 0 ? proceeds / soldShares : current;
  const realized = round2(proceeds - soldShares * entry);
  recordStopLoss({
    slug,
    condition_id: row.condition_id ?? null,
    token_id: row.token_id,
    outcome: row.outcome ?? null,
    entry_price: entry,
    exit_price: round4(exitPrice),
    shares: soldShares,
    proceeds,
    realized_pnl: realized,
    ts: new Date().toISOString(),
  });
  log(
    `손절 매도 ${slug} · 진입 ${entry} → 매도 ${round4(exitPrice)} · ` +
    `회수 $${proceeds} · 손익 ${realized < 0 ? "-" : "+"}$${Math.abs(realized)}`,
  );
  notifyMoney("손절 실행", `진입 ${entry} · 청산 ${round4(exitPrice)} · 실현 ${signedUsd(realized)}`);
  return true;
}

async function checkStopLossPosition(row, cfg = DEFAULT_TRADE_SETTINGS) {
  const slug = row?.slug;
  // 기온 No 페이드는 메인 폭이 아닌 기온 전용 폭으로 손절한다.
  // 단, 기온 폭은 매수 기록에 temp_no 태그가 있는 건만 — 메인 전략이 밴드
  // 겹침으로 산 기온 시장은 태그가 없어 메인 폭이 그대로 적용되고, 기록을
  // 찾을 수 없는 경우도 메인으로 간주한다 (안전 기본값).
  const stopDrop = isTempNoSlug(slug) && boughtStrategyOf(slug) === "temp_no"
    ? cfg.tempStopDrop
    : cfg.stopDrop;
  const entry = Number(row?.avg_price);
  const size = Number(row?.current_size);
  if (!slug || !row?.token_id || !Number.isFinite(entry)) return false;
  if (!Number.isFinite(size) || size <= 0) return false;
  // 진입가가 0·음수면 발화 비교가 영원히 거짓이 돼 손절이 조용히
  // 무력화된다 — 이상 데이터로 드러내 둔다.
  if (entry <= 0) {
    log(`손절 판정 불가 ${slug} — 진입가 이상 (${entry})`);
    return false;
  }
  const first = await fetchGammaPrices(slug).catch(() => null);
  if (!first) return false; // 조회 실패 — 0으로 취급하지 않고 스킵
  const idx = pickOutcomeIndex(first.outcomes, row);
  if (idx < 0 || idx >= first.prices.length) {
    log(`손절 판정 불가 ${slug} — 보유 쪽(outcome)을 가격 목록에서 찾지 못함`);
    return false;
  }
  if (stopLossVerdict(entry, first.prices[idx], stopDrop, cfg.decidedHigh, cfg.decidedLow) !== "triggered") return false;
  // 매도 직전 재검증 — 같은 가드로 다시 읽어 여전히 발화 상태일 때만 판다
  const second = await fetchGammaPrices(slug).catch(() => null);
  if (!second) return false;
  const idx2 = pickOutcomeIndex(second.outcomes, row);
  if (idx2 < 0 || idx2 >= second.prices.length) return false;
  const current = second.prices[idx2];
  if (stopLossVerdict(entry, current, stopDrop, cfg.decidedHigh, cfg.decidedLow) !== "triggered") {
    log(`손절 재검증 탈락 ${slug} · 현재가 ${current} (진입 ${entry})`);
    return false;
  }
  return executeStopLossSell(row, current, cfg);
}

async function runStopLoss(gen, cfg = DEFAULT_TRADE_SETTINGS) {
  let sold = 0;
  for (const row of state.lastOpenRows) {
    if (!state.running || gen !== state.gen) break;
    beatProgress();
    try {
      if (await checkStopLossPosition(row, cfg)) sold += 1;
    } catch (e) {
      // 한 건의 실패가 사이클 전체를 죽이지 않는다 — 다음 사이클에 재시도
      log(`손절 처리 오류 ${row?.slug ?? "?"}: ${String(e.message ?? e).slice(0, 120)}`);
    }
  }
  return sold;
}

// ── 정산 기준선 (F5) ────────────────────────────────────────
// 정산 확정 알림의 비교 기준선은 영속 저장한다 — 페이지 메모리에만 두면
// 재로드·재시작 때마다 첫 사이클이 그 시점 누계를 조용히 흡수해, 엔진이
// 죽어 있던 동안의 정산은 알림이 영영 가지 않았다.
export function parseSettledBaseline(raw) {
  if (!raw) return null;
  let o = raw;
  if (typeof raw === "string") {
    try {
      o = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!o || typeof o !== "object") return null;
  const field = (v) => (v === null || Number.isFinite(v) ? v : undefined);
  const count = field(o.count);
  const realized = field(o.realized);
  if (count === undefined || realized === undefined) return null;
  return { count, realized };
}

export function settledNoticeFor(base, snap) {
  if (!base || !snap) return null;
  if (snap.settledCount == null || base.count == null) return null;
  if (snap.settledCount <= base.count) return null;
  if (snap.settledRealizedPnl == null || base.realized == null) return null;
  return {
    count: snap.settledCount - base.count,
    realizedDelta: round2(snap.settledRealizedPnl - base.realized),
  };
}

export function loadSettledBaseline() {
  return parseSettledBaseline(durableRead(LS.settledBase));
}

export function saveSettledBaseline(base) {
  durableWrite(LS.settledBase, JSON.stringify(base));
}

async function runCycle(gen) {
  // 매매 설정은 사이클마다 새로 읽는다 — 설정 변경은 다음 사이클부터 적용.
  const tradeCfg = parseTradeSettings(durableRead(LS.settings));
  setStage("snapshot");
  let snap;
  try {
    snap = await snapshot(tradeCfg);
  } catch (e) {
    // 스냅샷 실패가 손절까지 막지 않게 한다 — 손절은 포지션 목록만으로
    // 돌 수 있는데, 스냅샷 전체 성공에 결합돼 있으면 Data API 하나만
    // 죽어도 보유 전 건의 손절이 그 사이클에 사라진다 (2026-11-15 감사).
    // 포지션만 다시 읽어 손절을 시도한 뒤 원래 오류로 사이클을 끝낸다.
    try {
      const rows = await fetchPositions(state.funder);
      state.lastOpenRows = rows.filter(isOpenRow);
      setStage("stoploss");
      await runStopLoss(gen, tradeCfg);
      log(`스냅샷 실패로 손절만 단독 실행함 — 원래 오류: ${String(e?.message ?? e).slice(0, 100)}`);
    } catch (e2) {
      log(`스냅샷 실패 + 손절 단독 실행도 실패: ${String(e2?.message ?? e2).slice(0, 100)}`);
    }
    throw e;
  }
  state.onState(snap);
  log(
    `사이클 ${state.cycle} · 잔고 $${snap.cash} · 평가 $${snap.equity} · ` +
    `건당 $${snap.bet} · 슬롯 ${snap.slots} · 보유 ${snap.openCount}`,
  );
  // 정산 확정 감지 — 기준선(영속) 대비 신규 정산분.
  // 손절 기록분은 이 스냅샷에 이미 들어 있고 기준선에도 반영되므로 여기서
  // 다시 세지 않는다 (손절 알림은 실행 시점에 따로 간다).
  if (!state.settledBaselineLoaded) {
    state.settledBaseline = loadSettledBaseline();
    state.settledBaselineLoaded = true;
  }
  const settleNotice = settledNoticeFor(state.settledBaseline, snap);
  beatProgress();
  // 손절은 신규 매수보다 먼저 — 팔린 건이 있으면 잔고·슬롯을 다시 읽는다
  setStage("stoploss");
  const soldCount = await runStopLoss(gen, tradeCfg);
  beatProgress();
  if (soldCount > 0) {
    snap = await snapshot(tradeCfg);
    state.onState(snap);
  }
  // 리딤은 손절 다음·신규 매수 이전 — 회수액이 생기면 잔고를 다시 읽는다
  setStage("redeem");
  const recovered = await runRedeems(gen);
  beatProgress();
  if (recovered > 0) {
    snap = await snapshot(tradeCfg);
    state.onState(snap);
  }
  if (settleNotice) {
    notifyMoney(
      "정산 확정",
      `실현 ${signedUsd(settleNotice.realizedDelta)}${recovered > 0 ? ` · 회수 $${recovered}` : ""}`,
    );
  }
  // 정산을 읽지 못한 사이클은 기준선을 건드리지 않는다 — 모르는 값으로
  // 덮으면 다음 사이클이 그 구간 정산을 흡수해 버린다 (F5).
  if (snap.settledCount != null) {
    state.settledBaseline = { count: snap.settledCount, realized: snap.settledRealizedPnl };
    saveSettledBaseline(state.settledBaseline);
  }
  // 체결로 기록한 건은 다음 사이클 스냅샷에 포지션으로 실재해야 한다 —
  // 어긋나면 조용히 넘기지 않고 경고로 드러낸다 (실행당 1회).
  if (state.sessionFills > 0 && !state.posGapWarned) {
    const found = snap.openCount + (snap.settledCount ?? 0);
    if (found < state.sessionFills) {
      state.posGapWarned = true;
      log(`보유 대조 경고: 체결 기록 ${state.sessionFills}건인데 포지션은 ${found}건만 확인됨`);
    }
  }
  if (snap.slots < 1) {
    log("슬롯 없음 — 이번 사이클 매수 없음");
    return;
  }
  // 기매수 기록이 손상된 사이클은 매수를 건너뛴다 — 빈 기록으로 읽어
  // 닫힌 종목을 다시 사는 것보다 한 사이클 쉬는 게 안전하다.
  if (boughtCorrupted()) {
    log("기매수 기록 손상 감지 — 이번 사이클 매수를 건너뜁니다 (재매수 방벽 보호)");
    return;
  }
  if (!state.boughtReconciled) await reconcileBoughtOnce(snap);
  setStage("scan");
  const markets = await fetchMarkets();
  const scanStats = {};
  // 재매수 차단은 bought ∪ 손절 원장 — 손절한 종목은 bought 기록이
  // 없어도(앱 밖 매수분 등) 다시 사지 않는다. 결과 불명 손절 시도의
  // slug도 같은 이유로 차단한다 (v0.3.35 감사 축 2).
  const boughtAll = boughtSlugs();
  for (const s of snap.stopLossSlugs || []) boughtAll.add(s);
  for (const s of stopLossAttemptedSlugs()) boughtAll.add(s);
  const candidates = scanCandidates(markets, snap.heldSlugs, boughtAll, scanStats, tradeCfg);
  // 기온 No 페이드 — 설정이 켜졌을 때만 별도 스캔을 붙인다. 후보는 같은
  // 선정 패스(슬롯·검증 캡 공유)를 타고, 메인 후보와 slug가 겹치면
  // 메인 쪽이 이긴다 (excludeSlugs). 기온 스캔 통계는 요약에 섞지 않는다.
  const tempSettings = parseTempSettings(durableRead(LS.settings));
  let tempEventsList = null;
  // 기온 이벤트 조회 전수 실패 여부 — 실패와 "오늘 이벤트 없음"을
  // 구분해, 전수 실패 사이클은 예약을 유지하는 쪽으로 판정한다
  // (안전 방향, v0.3.39 감사 E).
  let tempFetchFailed = false;
  if (tempSettings.enabled) {
    try {
      const events = await fetchTempEvents(Date.now());
      if (events == null) {
        tempFetchFailed = true;
        log("기온 이벤트 조회 전수 실패 — 이번 사이클은 기온 예약을 유지합니다");
      } else {
        tempEventsList = events;
        const mainSlugs = new Set(candidates.map((c) => c.slug));
        const tempCands = scanTempCandidates(
          events, snap.heldSlugs, boughtAll, tempSettings, Date.now(), null, mainSlugs,
        );
        if (tempCands.length) {
          log(`기온 후보 ${tempCands.length}건 (밴드 ${tempSettings.bandLo}–${tempSettings.bandHi})`);
          candidates.push(...tempCands);
        }
      }
    } catch (e) {
      log(`기온 스캔 오류: ${String(e?.message ?? e).slice(0, 120)}`);
    }
  }
  state.lastScan = { stats: scanStats, fetched: markets.length, ts: Date.now() };
  let slotsLeft = snap.slots;
  let verified = 0;
  const rejects = {};
  let filledCount = 0;
  let unfilledCount = 0;
  let failedCount = 0;
  const tallyReject = (reason) => {
    rejects[reason] = (rejects[reason] || 0) + 1;
  };
  // 후보 1건 처리 — true를 돌려주면 사이클을 즉시 중단한다 (아래 본문의
  // 중단 지점). 호출 순서와 예산 판정은 선정 드라이버가 맡는다.
  const attemptCandidate = async (cand) => {
    beatProgress();
    verified += 1;
    let detail;
    try {
      detail = await verifyCandidate(cand, snap.bet, tradeCfg, tempSettings.entryHourUtc);
    } catch (e) {
      tallyReject("verify_error");
      log(`검증 오류 ${cand.slug}: ${e.message ?? e}`);
      return false;
    }
    if (!detail.ok) {
      tallyReject(detail.reason);
      return false;
    }
    if (!state.running || gen !== state.gen) return true;
    // 제출 잠금 — 서명 시작부터 결과 확정까지 같은 slug 재진입을 막는다.
    // 세대 가드와 별개로 동작해, 이 지점을 지난 뒤 재시작·재개가 끼어도
    // 두 번째 루프는 잠금에서 걸려 같은 후보를 다시 제출하지 않는다.
    if (!tryAcquireSubmitLock(cand.slug)) {
      log(`제출 잠금 — 중복 제출 차단 ${cand.slug}`);
      return false;
    }
    try {
    // 제출 직전 bought 재확인 (이중 방어 — 먼저 끝난 제출이 기록했을 수 있다)
    if (boughtSlugs().has(cand.slug)) {
      log(`제출 직전 재확인 — 이미 매수 기록 있음 ${cand.slug}`);
      return false;
    }
    // 제출 진행 가드 (F1) — 서명부터 체결 기록까지 워치독 재로드를 막는다
    beginSubmit();
    try {
      let signed;
      try {
        signed = await state.client.createMarketOrder(
          {
            tokenID: detail.tokenId,
            side: Side.BUY,
            amount: snap.bet,
            price: detail.limitPrice,
            orderType: OrderType.FOK,
          },
          { tickSize: detail.tickSize, negRisk: detail.negRisk },
        );
      } catch (e) {
        failedCount += 1;
        log(`주문 서명 실패 ${cand.slug}: ${String(e.message ?? e).slice(0, 120)}`);
        return false;
      }
      // 제출 직전 세대 재검사 — 서명 대기가 긴 사이 정지→재실행이 끼면
      // 구 사이클의 주문이 새 사이클 슬롯 계산 밖에서 체결된다.
      if (!state.running || gen !== state.gen) return true;
      let resp;
      try {
        resp = await state.client.postOrder(signed, OrderType.FOK);
      } catch (e) {
        // 제출됐으나 결과를 모르는 상태 — 체결됐을 수 있어 중복 매수 방지를 위해
        // 매수한 것으로 기록하고 슬롯도 소모한 것으로 본다 (체결됐는데
        // 슬롯이 남아 같은 사이클에 추가 매수가 나가면 사이징 상한을 넘는다)
        failedCount += 1;
        markBought(cand.slug, cand.strategy);
        slotsLeft -= 1;
        const msg = String(e.message ?? e);
        log(`주문 결과 불명 ${cand.slug}: ${msg.slice(0, 120)} — 중복 방지 기록${cand.strategy === "temp_no" ? " · 전략 temp_no" : ""}`);
        if (isFailsafe(msg)) {
          stop();
          log("failsafe 감지 — 엔진 정지. 정지 사유를 확인한 뒤 다시 실행하세요.");
          return true;
        }
        return false;
      }
      const verdict = judgeFill(resp);
      if (!verdict.filled && verdict.kind === "unknown") {
        // 전송 단계 실패로 응답만 유실된 경우 — throw 경로와 동일하게
        // 체결됐을 수 있는 것으로 취급한다.
        failedCount += 1;
        markBought(cand.slug, cand.strategy);
        slotsLeft -= 1;
        log(`주문 결과 불명 ${cand.slug}: ${String(verdict.error || "").slice(0, 120)} — 중복 방지 기록${cand.strategy === "temp_no" ? " · 전략 temp_no" : ""}`);
        return false;
      }
      if (!verdict.filled && verdict.kind !== "unfilled") {
        failedCount += 1;
        const msg = verdict.error || "주문 거부 (success=false)";
        // 다음 진단이 추측이 되지 않게 원본 응답을 같은 줄에 남긴다.
        // failsafe는 메시지 외에 HTTP 코드(예: 지역 제한 403)도 검사한다.
        log(`주문 실패 ${cand.slug}: ${String(msg).slice(0, 120)} · 응답 원문: ${JSON.stringify(resp).slice(0, 200)}`);
        if (isFailsafe(String(msg)) || isFailsafe(verdict.status)) {
          stop();
          log("failsafe 감지 — 엔진 정지. 정지 사유를 확인한 뒤 다시 실행하세요.");
          return true;
        }
        return false;
      }
      if (!verdict.filled) {
        // 미체결 — delayed는 서버가 접수한 주문이라 뒤늦게 체결될 수 있어
        // 기록과 슬롯을 체결 쪽으로 둔다. unmatched·live는 접수 자체가
        // 무산된 것으로 보고 기록하지 않는다.
        unfilledCount += 1;
        if (verdict.status === "delayed") {
          markBought(cand.slug, cand.strategy);
          slotsLeft -= 1;
          log(`미체결(지연 접수) ${cand.slug} — 뒤늦은 체결에 대비해 기록하고 슬롯을 소모합니다${cand.strategy === "temp_no" ? " · 전략 temp_no" : ""}`);
        } else {
          log(`미체결 ${cand.slug} · 응답 status: ${resp.status ?? "(없음)"} · 응답 원문: ${JSON.stringify(resp).slice(0, 200)}`);
        }
        return false;
      }
      markBought(cand.slug, cand.strategy);
      state.sessionFills += 1;
      filledCount += 1;
      slotsLeft -= 1;
      const fill = resp.makingAmount && resp.takingAmount
        ? ` 체결가 ${round2(Number(resp.makingAmount) / Number(resp.takingAmount))}`
        : "";
      log(`매수 ${cand.slug} · $${snap.bet} @ ${detail.limitPrice}${fill} · 상태 ${resp.status ?? "matched"}${cand.strategy === "temp_no" ? " · 전략 temp_no" : ""}${cand.strategy === "temp_no" && cand.feeRate != null ? ` · 수수료율 ${cand.feeRate}` : ""}`);
      notifyMoney(
        "매수 체결",
        `건당 $${snap.bet} · 체결가 ${resp.makingAmount && resp.takingAmount ? round2(Number(resp.makingAmount) / Number(resp.takingAmount)) : detail.limitPrice}`,
      );
    } finally {
      endSubmit();
    }
    } finally {
      releaseSubmitLock(cand.slug);
    }
    return false;
  };
  // 선정 드라이버 — 기온 슬롯 홀드. 토글 ON + 예약 슬롯 N>0이고,
  // 오늘·내일 기온 이벤트 중 진입 창이 남은 것이 있거나(후보가 있으면
  // 그 이벤트가 여기에 걸린다) 조회가 전수 실패했을 때 홀드가 켜진다.
  // 홀드가 켜지면 메인의 유효 용량을 전체에서 N슬롯 줄여 실제로 비워
  // 둔다: 메인 후보는 남은 슬롯이 N개 이하이거나 검증이 캡−N에 닿으면
  // 그 사이클에서 건너뛰고, 다시 태우지 않는다 — 다시 태우면 비워 둔
  // 슬롯이 같은 사이클에 사라져 창이 열릴 때 남는 자리가 없다. slots는
  // 건수 상한과 현금 기준 슬롯 수의 최소값이라, 이 차단은 건당 베팅액
  // × N만큼의 현금도 함께 비운다. 기온 후보는 홀드와 무관하게 전
  // 용량을 쓴다 (예약은 하한이라 기온이 N개를 넘어 사는 것도 막지
  // 않는다). 홀드 조건이 없으면 차단되는 후보가 없어 기존 단일 패스와
  // 동일하다. 해당하는 이벤트가 없거나 전부 종료됐으면 홀드가 풀려
  // 메인이 전 용량을 쓴다.
  // 홀드 실효값은 전체 포지션 상한으로 제한한다 (감사 축 3).
  const tempReserveSlots = effectiveTempReserveSlots(tempSettings.reserveSlots, tradeCfg.maxPositions);
  const tempHold = tempSettings.enabled
    && tempReserveSlots > 0
    && (tempFetchFailed
      || tempHasUpcomingWindow(tempEventsList, Date.now(), tempSettings.entryHourUtc, snap.heldSlugs, boughtAll));
  const heldMain = [];
  let aborted = false;
  for (const cand of candidates) {
    if (!state.running || gen !== state.gen) { aborted = true; break; }
    if (tempHold && tempReserveBlocksMain(cand.strategy, slotsLeft, verified, tempReserveSlots, tradeCfg.verifyCap)) {
      heldMain.push(cand);
      continue;
    }
    if (slotsLeft < 1 || verified >= tradeCfg.verifyCap) break;
    if (await attemptCandidate(cand)) { aborted = true; break; }
  }
  if (tempHold && heldMain.length) {
    if (tempFetchFailed) {
      log(`기온 예약 — 조회 전수 실패로 메인 ${heldMain.length}건을 미루고 슬롯을 남깁니다`);
    } else {
      log(`기온 예약 — 진입 창이 남은 이벤트가 있어 메인 ${heldMain.length}건을 미루고 슬롯을 남깁니다`);
    }
  }
  if (aborted) return;
  log(summarizeCycle({
    candidates: candidates.length,
    verified,
    rejects,
    filled: filledCount,
    unfilled: unfilledCount,
    failed: failedCount,
    scan: scanStats,
  }));
}

// 단계 추적 — 사이클이 어느 단계에 있는지를 영속 기록한다. 엔진이 중간에
// 죽으면(웹뷰 종료·무응답) 평소 로그만으로는 중단 지점을 특정할 수 없어,
// 다음 복원 때 마지막 기록 단계로 지점을 드러낸다. 정상 정지·정상 사이클
// 완료는 값을 지우거나 idle로 남겨 오탐을 막는다.
function setStage(stage) {
  state.stage = stage;
  try {
    localStorage.setItem(
      LS.stage,
      JSON.stringify({ stage, cycle: state.cycle, ts: Date.now() }),
    );
  } catch {}
}

function readStage() {
  try {
    const o = JSON.parse(localStorage.getItem(LS.stage) || "null");
    if (o && typeof o.stage === "string") return o;
  } catch {}
  return null;
}

// ── 루프 자가 감시 + 오류 에스컬레이션 ─────────────────────
// 사이클 완료 후 다음 사이클을 거는 setTimeout 체인이 끊기면 stage는
// idle로 남고 하트비트 타이머는 살아 있어, JS 안에서는 아무도 알아채지
// 못하는 정지가 된다 (v0.3.25 실측). 하트비트와 분리된 감시 타이머가
// 마지막 사이클 완료 시각을 보고 체인을 스스로 다시 건다. 감시 타이머
// 자체가 죽는 경우까지는 네이티브 워치독의 사이클 정체 판정이 덮는다.
export const LOOP_WATCH_INTERVAL_MS = 60_000;
export const LOOP_STALL_MS = 5 * 60_000;

export function isLoopStalled(lastCycleDoneMs, nowMs) {
  if (!Number.isFinite(lastCycleDoneMs) || lastCycleDoneMs <= 0) return false;
  return nowMs - lastCycleDoneMs > LOOP_STALL_MS;
}

function checkLoopAlive() {
  if (!state.running || state.cycleInFlight) return;
  if (!isLoopStalled(state.lastCycleDoneMs, Date.now())) return;
  // 예약된 타이머가 남아 있으면(발화만 안 된 경우) 지우고, gen을 올려
  // 뒤늦게 도는 구 체인 콜백이 새 체인과 겹치지 않게 한 뒤 다시 건다.
  // 진행 중이던 구 사이클은 gen 경계마다 스스로 멈춘다.
  if (state.timer) clearTimeout(state.timer);
  state.timer = null;
  state.gen += 1;
  const idleMin = Math.floor((Date.now() - state.lastCycleDoneMs) / 60_000);
  log(`루프 자가 재시작 — 사이클 완료 후 ${idleMin}분간 다음 사이클이 돌지 않았습니다`);
  // 재시작 직후 감시가 곧바로 또 발화하지 않게 기준 시각을 당겨 둔다
  state.lastCycleDoneMs = Date.now();
  loop(state.gen);
}

// 네트워크 단절처럼 사이클 오류만 반복되는 구간에서는 루프가 살아
// 있어도 아무 진전이 없다 (2026-11-15 오전 실측 — 105분간 매분 실패).
// 오류가 연속 임계에 닿으면 페이지를 자가 재로드한다: 실행 의도가
// 영속이라 재로드 뒤 복원이 자동 재개한다. 재로드가 남발되지 않게
// 직전 재로드 시각을 영속으로 남겨 쿨다운 안에서는 다시 걸지 않는다.
export const CYCLE_ERROR_RELOAD_THRESHOLD = 10;
export const SELF_RELOAD_COOLDOWN_MS = 10 * 60_000;

export function shouldSelfReload(consecutiveErrors, lastReloadMs, nowMs) {
  if (!Number.isFinite(consecutiveErrors)
      || consecutiveErrors < CYCLE_ERROR_RELOAD_THRESHOLD) return false;
  if (Number.isFinite(lastReloadMs) && lastReloadMs > 0
      && nowMs - lastReloadMs < SELF_RELOAD_COOLDOWN_MS) return false;
  return true;
}

function lastSelfReloadMs() {
  try {
    return Number(localStorage.getItem(LS.selfReload) || 0) || 0;
  } catch {
    return 0;
  }
}

// 재로드하면 true — 호출자(loop)는 이 페이지에서 더 진행하지 않는다.
function maybeSelfReload() {
  const now = Date.now();
  if (!shouldSelfReload(state.cycleErrors, lastSelfReloadMs(), now)) return false;
  try {
    localStorage.setItem(LS.selfReload, String(now));
  } catch {}
  log(`사이클 오류 ${state.cycleErrors}회 연속 — 페이지 자가 재로드로 복구를 시도합니다`);
  try {
    window.location.reload();
  } catch {}
  return true;
}

async function loop(gen) {
  if (!state.running || gen !== state.gen) return;
  state.cycle += 1;
  state.cycleInFlight = true;
  beatProgress();
  let cycleFailed = false;
  try {
    await runCycle(gen);
  } catch (e) {
    cycleFailed = true;
    log(`사이클 오류: ${String(e.message ?? e).slice(0, 160)}`);
  }
  // 완료 기록은 현재 세대일 때만 남긴다 — 정지→재시작 뒤에 끝난 구
  // 사이클이 새 체인의 감시 기준을 흔들지 않게 한다.
  if (gen === state.gen) {
    state.cycleInFlight = false;
    state.lastCycleDoneMs = Date.now();
    if (cycleFailed) {
      state.cycleErrors += 1;
      if (maybeSelfReload()) return;
    } else if (state.running) {
      state.cycleErrors = 0;
    }
  }
  // 정지된 뒤에 끝난 사이클이 "stopped" 기록을 "idle"로 덮지 않게 한다
  if (state.running && gen === state.gen) setStage("idle");
  if (state.cycle % UPDATE_CHECK_EVERY_CYCLES === 0) void checkForUpdate();
  if (state.running && gen === state.gen) {
    state.timer = setTimeout(() => loop(gen), CYCLE_MS);
  }
}

// 실행 의도의 단일 기록 지점 — localStorage(재개 판정)와 네이티브 브리지
// (포그라운드 서비스 기동·종료 + 알림 정지 시 재개 차단 미러)에 함께 남긴다.
function setRunIntent(on) {
  try {
    if (on) localStorage.setItem(LS.runIntent, "1");
    else localStorage.removeItem(LS.runIntent);
  } catch {}
  try {
    window.AndroidBridge?.setRunIntent(Boolean(on));
  } catch {}
}

// ── 호스트 모드 ─────────────────────────────────────────────
// 같은 페이지가 두 곳에서 뜬다: 서비스 WebView(엔진 호스트 — 사이클 구동)와
// 액티비티 WebView(뷰어 — 표시·조작만). 네이티브 브리지가 모드를 동기 반환
// 하므로, 페이지 스크립트는 부팅 즉시 자기 역할을 확정할 수 있다.
// 브리지가 없거나(브라우저 미리보기) 알 수 없는 값이면 뷰어가 기본값이다 —
// 뷰어는 루프를 시작하지 않으므로 안전한 쪽으로 기운다.
export function resolveHostMode(bridgeMode) {
  return bridgeMode === "engine" ? "engine" : "viewer";
}

function hostMode() {
  try {
    return resolveHostMode(window.AndroidBridge?.hostMode?.());
  } catch {
    return "viewer";
  }
}

// 실행 의도 판정의 단일 지점 — mirror는 네이티브 미러(SharedPreferences)의
// 삼진 값이다: true/false(브리지로 읽음) 또는 null(브리지 부재 = 모름).
function runIntentState() {
  let saved = false;
  try {
    saved = localStorage.getItem(LS.runIntent) === "1";
  } catch {}
  let mirror = null;
  try {
    if (window.AndroidBridge) mirror = Boolean(window.AndroidBridge.getRunIntent());
  } catch {}
  return { saved, mirror };
}

// 자동 재개 판정. 엔진 호스트에서는 네이티브 미러가 정본이다 — 뷰어에서
// 실행을 눌러 서비스가 처음 뜬 경우처럼 LS 플래그가 아직 없어도 미러가
// 켜져 있으면 시작하고, 미러가 꺼져 있으면 LS 플래그가 남아 있어도
// 시작하지 않는다. 그 외 모드에서는 기존 규칙(LS 저장 + 미러 명시 off 아님).
export function shouldAutoStart(mode, saved, mirror) {
  if (mode === "engine") return mirror === true;
  return Boolean(saved) && mirror !== false;
}

// 부작용 없는 조회 — 부팅 화면의 잠정 표시 판정에 쓴다.
function peekRunIntent() {
  const s = runIntentState();
  return shouldAutoStart("viewer", s.saved, s.mirror);
}

// ── 워치독 하트비트 ─────────────────────────────────────────
// JS가 사이클마다 브리지로 하트비트를 남기고(SharedPreferences), 서비스가
// 30초마다 확인해 stale이면 엔진 페이지를 재로드한다. stale 기준은
// 사이클 3개분 + 여유 — 네이티브 워치독과 같은 상수를 쓴다.
// 하트비트가 아예 없는(<=0) 경우는 stale이 아니다: 페이지 로드 직후
// 복원이 도는 동안은 네이티브가 로드 경과 시간으로 따로 판정한다.
export const HEARTBEAT_STALE_MS = CYCLE_MS * 3 + 20_000;

export function isHeartbeatStale(lastBeatMs, nowMs) {
  if (!Number.isFinite(lastBeatMs) || lastBeatMs <= 0) return false;
  return nowMs - lastBeatMs > HEARTBEAT_STALE_MS;
}

// 뷰어의 "응답 없음" 판정 (F2) — 하트비트가 있으면 stale 기준을 쓰고,
// 한 번도 없으면(<=0) 관찰 시작(뷰어 부팅) 후 유예를 넘겼을 때만
// 무응답으로 본다. 하트비트가 없다는 이유만으로 거짓 "실행 중"을
// 유지하던 종전 판정의 반대쪽 거짓(막 켠 엔진의 오탐)은 유예가 막는다.
export function isEngineUnresponsive(heartbeatMs, nowMs, observedSinceMs) {
  if (Number.isFinite(heartbeatMs) && heartbeatMs > 0) {
    return nowMs - heartbeatMs > HEARTBEAT_STALE_MS;
  }
  if (!Number.isFinite(observedSinceMs)) return false;
  return nowMs - observedSinceMs > NO_HEARTBEAT_GRACE_MS;
}

function beatHeartbeat() {
  try {
    window.AndroidBridge?.heartbeat?.(state.cycle);
  } catch {}
}

// 사이클 내부 진행 신호 — 30초 타이머 하트비트와 달리, 루프가 실제로
// 단계를 전진할 때(사이클 시작·단계 사이·손절 행별·검증 후보별)만
// 나간다. 네이티브 워치독은 이 신호가 최근이면 사이클 번호가 바뀌지
// 않아도 정체로 판정하지 않는다 — 검증만 긴 단일 사이클의 오재로드
// 방지. 단계 내부까지 멈추면 이 신호도 멈추므로 그때는 걸린다.
function beatProgress() {
  beatHeartbeat();
  try {
    window.AndroidBridge?.cycleProgress?.(state.cycle);
  } catch {}
}

// 워치독이 재로드한 사실은 복원 직후 로그로 남긴다 — 조용한 복구 금지.
// 사이클 정체가 사유였던 재로드는 별도 카운터로 구분해 함께 남긴다.
function reportWatchdogRecovery() {
  try {
    const n = Number(window.AndroidBridge?.getWatchdogReloads?.() || 0);
    if (Number.isFinite(n) && n > 0) {
      const seen = Number(localStorage.getItem(LS.wdSeen) || 0);
      if (n > seen) {
        log(`워치독 복구 — 멈춘 엔진 페이지를 재로드했습니다 (누적 ${n}회)`);
        localStorage.setItem(LS.wdSeen, String(n));
      }
    }
    const cyc = Number(window.AndroidBridge?.getWatchdogCycleReloads?.() || 0);
    if (Number.isFinite(cyc) && cyc > 0) {
      const seenCyc = Number(localStorage.getItem(LS.wdCycleSeen) || 0);
      if (cyc > seenCyc) {
        log(`워치독 복구 — 사이클 정체(번호 무전진)로 재로드했습니다 (누적 ${cyc}회)`);
        localStorage.setItem(LS.wdCycleSeen, String(cyc));
      }
    }
  } catch {}
}

// ── 리딤 (자동 상환) ─────────────────────────────────────────
// 레포 live/live_executor.py의 검증된 방식을 이식: condition 단위 계획 →
// deposit wallet 배치 제출(relayer gasless) → pUSD 잔고 델타 검증
// (허용 오차 max($0.05, 2%)). 건별 실패는 pending 유지·영구 래치 없음,
// 제출 후 잔고 측정 실패는 submitted_unverified로 남긴다.
// 제출 인증은 Relayer API 키가 필수다 (공식 SDK가 키 없으면 제출 자체를
// 거부한다). 앱에 키가 없으면 감지·계획만 돌고 제출은 보류한다 — 우회 없음.
const RELAYER_URL = "https://relayer-v2.polymarket.com";
const DEPOSIT_WALLET_FACTORY = "0x00000000000Fb5C9ADea0298D729A0CB3823Cc07";
const COLLATERAL_ADAPTER = "0xAdA100Db00Ca00073811820692005400218FcE1f";
const NEG_RISK_COLLATERAL_ADAPTER = "0xadA2005600Dec949baf300f4C6120000bDB6eAab";
const PROTOCOL_V2_ROUTER = "0x12121212006e4CD160D18e3f00711DA5c3372600";
const POSITION_MANAGER = "0x006F54F7f9A22e0000CC2AB60031000000ae9fEF";
// v1(CTF) 포지션 토큰의 ERC1155 발행처 — 잔고 0 확인(이미 상환됨 판정)에 쓴다
const CTF_CONTRACT = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045";
const ZERO_BYTES32 = `0x${"0".repeat(64)}`;
const REDEEM_TOL_ABS = 0.05;
const REDEEM_TOL_REL = 0.02;
const REDEEM_SUBMIT_ATTEMPTS = 2;
const REDEEM_POLL_MS = 2000;
const REDEEM_POLL_MAX_MS = 120_000;

const CTF_REDEEM_ABI = parseAbi([
  "function redeemPositions(address collateralToken, bytes32 parentCollectionId, bytes32 conditionId, uint256[] indexSets)",
]);
const ROUTER_REDEEM_ABI = parseAbi([
  "function redeem(bytes31 conditionId, uint256 outcomeIndex, uint256 amount)",
]);

export function buildCtfRedeemCall(conditionId, negRisk) {
  return {
    target: negRisk ? NEG_RISK_COLLATERAL_ADAPTER : COLLATERAL_ADAPTER,
    value: "0",
    data: encodeFunctionData({
      abi: CTF_REDEEM_ABI,
      functionName: "redeemPositions",
      args: [PUSD, ZERO_BYTES32, conditionId, [1n, 2n]],
    }),
  };
}

// protocol v2: bytes31은 32바이트 condition id 끝의 binary outcome 바이트를 뗀 것
export function toBytes31(conditionId) {
  let hex = String(conditionId || "").toLowerCase();
  if (hex.startsWith("0x")) hex = hex.slice(2);
  if (hex.length === 64 && (hex.endsWith("00") || hex.endsWith("01"))) hex = hex.slice(0, 62);
  if (hex.length !== 62 || !/^[0-9a-f]+$/.test(hex)) {
    throw new Error("protocol v2 condition id를 bytes31로 바꿀 수 없음");
  }
  return `0x${hex}`;
}

export function buildV2RedeemCall(conditionId, outcomeIndex, amount) {
  return {
    target: PROTOCOL_V2_ROUTER,
    value: "0",
    data: encodeFunctionData({
      abi: ROUTER_REDEEM_ABI,
      functionName: "redeem",
      args: [toBytes31(conditionId), BigInt(outcomeIndex), BigInt(amount)],
    }),
  };
}

// condition_id 정규화 — 0x 없는 64자리 hex 소문자로. 형식이 아니면 null.
export function normalizeConditionId(raw) {
  let cid = String(raw || "").toLowerCase();
  if (cid.startsWith("0x")) cid = cid.slice(2);
  return /^[0-9a-f]{64}$/.test(cid) ? cid : null;
}

// 정산 행 → condition 단위 계획 (live_executor.plan_redeems 이식).
// 승자 행(REDEEMABLE·가치>0)이 있는 condition만 회수 대상이고,
// 패배 행만 남은 condition은 worthless로 종결한다.
export function planRedeems(entries) {
  const groups = new Map();
  const order = [];
  const errors = [];
  for (const e of entries || []) {
    const cid = normalizeConditionId(e?.condition_id);
    if (!cid) {
      errors.push({ entry: e, reason: "condition_id 형식 오류" });
      continue;
    }
    const key = `0x${cid}`;
    if (!groups.has(key)) {
      groups.set(key, { condition_id: key, wins: [], losses: [] });
      order.push(key);
    }
    const g = groups.get(key);
    const won = e.status === "REDEEMABLE" && Number(e.current_value || 0) > 0;
    (won ? g.wins : g.losses).push(e);
  }
  const targets = [];
  const worthless = [];
  for (const key of order) {
    const g = groups.get(key);
    if (g.wins.length) {
      targets.push({
        ...g,
        expected_usd: round2(g.wins.reduce((s, e) => s + Number(e.current_value || 0), 0)),
      });
    } else {
      worthless.push(...g.losses);
    }
  }
  return { targets, worthless, errors };
}

export function verifyRecovery(beforeCash, afterCash, expectedUsd) {
  const delta = round2(afterCash - beforeCash);
  const expected = expectedUsd || 0;
  const tolerance = Math.max(REDEEM_TOL_ABS, REDEEM_TOL_REL * expected);
  return {
    delta_usd: delta,
    expected_usd: expected,
    tolerance: round2(tolerance),
    status: Math.abs(delta - expected) <= tolerance ? "verified" : "mismatch",
  };
}

const REDEEM_PENDING_CAP = 300;

function redeemPendingKey(e) {
  return `${e?.condition_id}|${e?.token_id}`;
}

/**
 * 리딤 pending 로드 — 본 목록과 절단 아카이브를 병합해 돌려준다.
 * 아카이브된 항목도 리딤 사이클이 다시 회수해 재평가한다 (중복은
 * condition_id|token_id 키로 제거하고, 본 목록 쪽 항목이 우선한다).
 */
export function loadRedeemPending() {
  const pending = loadDurableJsonList(LS.redeemPending);
  const archive = loadDurableJsonList(LS.redeemPendingArchive);
  if (!archive.length) return pending;
  const pendingKeys = new Set(pending.map(redeemPendingKey));
  return [...archive.filter((e) => !pendingKeys.has(redeemPendingKey(e))), ...pending];
}

/**
 * 리딤 pending 저장 — 인자는 전체 목록이다. 300건을 넘는 앞부분은
 * 버리지 않고 아카이브 키에 보존하고(load가 다시 병합한다), 전체
 * 목록에서 빠진(해소된) 항목은 아카이브에서도 함께 사라진다
 * (v0.3.38 감사 축 3 — 절단으로 인한 영구 리딤 누락 방지).
 */
export function saveRedeemPending(list) {
  const overflow = list.length > REDEEM_PENDING_CAP
    ? list.slice(0, list.length - REDEEM_PENDING_CAP)
    : [];
  durableWrite(LS.redeemPendingArchive, JSON.stringify(overflow));
  durableWrite(LS.redeemPending, JSON.stringify(list.slice(-REDEEM_PENDING_CAP)));
}

// status 승격 순위 — REDEEMABLE(승자 확인) > REDEEMABLE_LOST(패배 관측).
// 기존 항목의 status는 이 순위 상승 방향으로만 갱신한다 (강등 없음).
function redeemStatusRank(status) {
  if (status === "REDEEMABLE") return 2;
  if (status === "REDEEMABLE_LOST") return 1;
  return 0;
}

/**
 * 정산 행 수집 순수 로직 (네이티브 RedeemPlan.ingest와 같은 규칙) —
 * pending 목록에 승자·패배·CLOSED 승 행을 합치고 { pending, changed }를
 * 돌려준다. 이미 수집된 항목은 가치와 status를 갱신하되, status는
 * 승격 방향으로만 바꾼다 — REDEEMABLE_LOST로 먼저 수집된 토큰이 나중에
 * 승자로 와도 status가 고정돼 있으면 planRedeems의 승자 판정에서
 * 영원히 빠진다 (v0.3.39 감사 C).
 */
export function collectRedeemPending(pendingIn, done, winRows, lostRows, closedRows) {
  const pending = [...(pendingIn || [])];
  const byKey = new Map(pending.map((e) => [`${e.condition_id}|${e.token_id}`, e]));
  let changed = false;
  const addRows = (rows, status) => {
    for (const r of rows || []) {
      if (!r.condition_id || done.has(r.condition_id)) continue;
      const k = `${r.condition_id}|${r.token_id}`;
      const value = Number(r.current_value || 0);
      const existing = byKey.get(k);
      if (existing) {
        // 이미 수집된 항목도 가치는 최신 행으로 갱신한다 — 최초 수집
        // 시점 값(정산 직후 과도기의 0 등)으로 고정되면 승자가 전액
        // 손실로 종결돼 회수금이 동결된다 (2026-11-15 감사).
        if (Number.isFinite(value) && existing.current_value !== value) {
          existing.current_value = value;
          changed = true;
        }
        if (redeemStatusRank(status) > redeemStatusRank(existing.status)) {
          existing.status = status;
          changed = true;
        }
        continue;
      }
      const entry = {
        condition_id: r.condition_id,
        token_id: r.token_id,
        slug: r.slug ?? null,
        status,
        current_value: value,
      };
      byKey.set(k, entry);
      pending.push(entry);
      changed = true;
    }
  };
  addRows(winRows, "REDEEMABLE");
  addRows(lostRows, "REDEEMABLE_LOST");
  // REDEEMABLE 구간을 관측하지 못한 채 CLOSED로 전이한 승자도 수집한다 —
  // 수집원이 REDEEMABLE 두 목록뿐이면 앱이 꺼진 사이의 전이를 영영
  // 놓친다. CLOSED 승 행은 current_value가 0으로 오므로 회수 예정액을
  // 진입원가 + 실현손익으로 추정해 넣는다 (잔고 게이트가 실회수를 막는
  // 이중 회수는 온체인에서 차단된다).
  const closedWins = (closedRows || []).filter((r) => {
    if (Number(r?.current_price) >= 1) return true;
    return false;
  }).map((r) => {
    const est = round2(Number(r.entry_cost_usdc || 0) + Number(r.realized_pnl || 0));
    return { ...r, current_value: Number(r.current_value) > 0 ? r.current_value : est };
  }).filter((r) => Number(r.current_value) > 0);
  addRows(closedWins, "REDEEMABLE");
  return { pending, changed };
}

// 스냅샷의 정산 행(REDEEMABLE/REDEEMABLE_LOST)을 pending에 수집한다.
// 이미 회수했거나 worthless로 종결한 condition은 다시 넣지 않는다.
function ingestRedeemPending() {
  const done = new Set([
    ...loadDurableJsonList(LS.redeemHistory).map((h) => h.condition_id),
    ...loadDurableJsonList(LS.redeemClosed),
    // 캡에서 밀려나 아카이브된 done 근거도 같은 무게로 본다 (v0.3.35 감사 축 3).
    ...loadDurableJsonList(LS.redeemDoneArchive),
  ]);
  const { pending, changed } = collectRedeemPending(
    loadRedeemPending(), done,
    state.lastWinRows, state.lastLostRows, state.lastClosedRows,
  );
  if (changed) saveRedeemPending(pending);
}

function removeRedeemPending(conditionId) {
  saveRedeemPending(loadRedeemPending().filter((e) => e.condition_id !== conditionId));
}

function archiveRedeemDoneIds(ids) {
  const fresh = ids.filter((id) => id != null);
  if (!fresh.length) return;
  const archive = loadDurableJsonList(LS.redeemDoneArchive);
  for (const id of fresh) {
    if (!archive.includes(id)) archive.push(id);
  }
  durableWrite(LS.redeemDoneArchive, JSON.stringify(archive.slice(-10000)));
}

function closeWorthless(entries) {
  const closed = loadDurableJsonList(LS.redeemClosed);
  const set = new Set(closed);
  for (const e of entries) {
    set.add(e.condition_id);
    log(`리딤 종결 (전액 손실) ${String(e.condition_id).slice(0, 10)}…`);
  }
  const all = [...set];
  // 캡에서 밀려난 종결 id는 done 아카이브로 보존한다 (v0.3.35 감사 축 3).
  if (all.length > 500) archiveRedeemDoneIds(all.slice(0, all.length - 500));
  durableWrite(LS.redeemClosed, JSON.stringify(all.slice(-500)));
  const ids = new Set(entries.map((e) => e.condition_id));
  saveRedeemPending(loadRedeemPending().filter((e) => !ids.has(e.condition_id)));
}

function recordRedeemHistory(rec) {
  const list = loadDurableJsonList(LS.redeemHistory);
  list.push(rec);
  // 캡에서 밀려난 기록의 condition_id는 done 아카이브로 보존한다.
  if (list.length > 100) {
    archiveRedeemDoneIds(list.slice(0, list.length - 100).map((h) => h.condition_id));
  }
  durableWrite(LS.redeemHistory, JSON.stringify(list.slice(-100)));
}

function relayerHeaders(creds) {
  return {
    "RELAYER_API_KEY": creds.key,
    "RELAYER_API_KEY_ADDRESS": creds.address,
    "Content-Type": "application/json",
  };
}

async function relayerGet(path, creds) {
  const url = `${RELAYER_URL}${path}`;
  const bridge = window.AndroidBridge;
  if (bridge?.getSync) {
    const out = JSON.parse(bridge.getSync(url, JSON.stringify(relayerHeaders(creds))));
    if (out.error) throw new Error(`relayer 조회 실패: ${out.error}`);
    if (out.status >= 400) throw new Error(`relayer 조회 HTTP ${out.status}: ${String(out.body || "").slice(0, 160)}`);
    return JSON.parse(out.body || "{}");
  }
  const resp = await fetchWithTimeout(url, { headers: relayerHeaders(creds) });
  if (!resp.ok) throw new Error(`relayer 조회 HTTP ${resp.status}`);
  return resp.json();
}

async function relayerPost(path, bodyObj, creds) {
  const url = `${RELAYER_URL}${path}`;
  const body = JSON.stringify(bodyObj);
  const bridge = window.AndroidBridge;
  if (bridge?.postSync) {
    const out = JSON.parse(bridge.postSync(url, JSON.stringify(relayerHeaders(creds)), body));
    if (out.error) throw new Error(String(out.error).slice(0, 200));
    if (out.status >= 400) throw new Error(`relayer 제출 HTTP ${out.status}: ${String(out.body || "").slice(0, 200)}`);
    return JSON.parse(out.body || "{}");
  }
  const resp = await fetchWithTimeout(url, { method: "POST", headers: relayerHeaders(creds), body });
  const text = await resp.text();
  if (!resp.ok) throw new Error(`relayer 제출 HTTP ${resp.status}: ${text.slice(0, 200)}`);
  return JSON.parse(text || "{}");
}

// Gamma에서 마켓 컨텍스트(negRisk·version·positionIds·clobTokenIds)를 읽는다 (closed 포함)
async function fetchMarketContext(conditionId) {
  const page = await fetchJson(
    `${GAMMA_MARKETS_URL}?${new URLSearchParams({ condition_ids: conditionId, closed: "true" })}`,
  );
  const m = Array.isArray(page) ? page[0] : null;
  if (!m) return null;
  let tokenIds = null;
  try {
    const t = JSON.parse(m.clobTokenIds || "[]");
    if (Array.isArray(t) && t.length >= 2) tokenIds = t.map(String);
  } catch {}
  return {
    negRisk: Boolean(m.negRisk),
    version: m.version || null,
    positionIds: Array.isArray(m.positionIds) ? m.positionIds : null,
    tokenIds,
  };
}

// 온체인 잔고 목록으로 상환 제출 여부를 가른다 (F3) — 전부 0이면 이미
// 상환이 끝난 건이라 제출 없이 종결할 수 있다. 잔고를 하나도 못 읽은
// 경우(빈 목록)는 단정하지 않고 "unknown" — 호출자가 제출 쪽으로 기운다.
export function redeemBalanceGate(balances) {
  if (!Array.isArray(balances) || !balances.length) return "unknown";
  return balances.every((b) => BigInt(b) === 0n) ? "already" : "submit";
}

async function erc1155Balance(token, owner, tokenId) {
  const data =
    `0x00fdd58e${owner.slice(2).toLowerCase().padStart(64, "0")}` +
    BigInt(tokenId).toString(16).padStart(64, "0");
  const result = await rpcCall("eth_call", [{ to: token, data }, "latest"]);
  return BigInt(result);
}

const BATCH_TYPES = {
  Batch: [
    { name: "wallet", type: "address" },
    { name: "nonce", type: "uint256" },
    { name: "deadline", type: "uint256" },
    { name: "calls", type: "Call[]" },
  ],
  Call: [
    { name: "target", type: "address" },
    { name: "value", type: "uint256" },
    { name: "data", type: "bytes" },
  ],
};

// deposit wallet 배치 1건을 제출하고 종단 상태까지 폴링한다.
// nonce 불일치 오류가 오면 오류 문구의 on-chain nonce로 1회 재제출한다.
async function submitRedeemBatch(conditionId, calls, creds) {
  const params = await relayerGet(
    `/v1/account/transactions/params?address=${state.eoa}&type=WALLET`,
    creds,
  );
  let nonce = String(params.nonce);
  let lastError = null;
  for (let attempt = 0; attempt < REDEEM_SUBMIT_ATTEMPTS; attempt += 1) {
    const deadline = String(Math.floor(Date.now() / 1000) + 600);
    const signature = await state.account.signTypedData({
      domain: { name: "DepositWallet", version: "1", chainId: 137, verifyingContract: state.funder },
      types: BATCH_TYPES,
      primaryType: "Batch",
      message: {
        wallet: state.funder,
        nonce: BigInt(nonce),
        deadline: BigInt(deadline),
        calls: calls.map((c) => ({ target: c.target, value: 0n, data: c.data })),
      },
    });
    let resp;
    try {
      resp = await relayerPost(
        "/submit",
        {
          type: "WALLET",
          from: state.eoa,
          to: DEPOSIT_WALLET_FACTORY,
          nonce,
          signature,
          metadata: `Redeem positions for condition ${conditionId}`,
          depositWalletParams: {
            depositWallet: state.funder,
            deadline,
            calls: calls.map((c) => ({ target: c.target, value: "0", data: c.data })),
          },
        },
        creds,
      );
    } catch (e) {
      lastError = e;
      const m = /batch nonce (\d+) does not match on-chain nonce (\d+)/.exec(String(e.message ?? e));
      if (m && BigInt(m[2]) > BigInt(nonce)) {
        nonce = m[2];
        continue;
      }
      throw e;
    }
    const txId = resp.transactionID;
    const started = Date.now();
    while (Date.now() - started < REDEEM_POLL_MAX_MS) {
      await sleep(REDEEM_POLL_MS);
      const tx = await relayerGet(`/v1/account/transactions/${txId}`, creds).catch(() => null);
      if (!tx) continue;
      if (tx.state === "STATE_CONFIRMED") {
        return { transactionId: txId, txHash: tx.transaction_hash || resp.transactionHash || null };
      }
      if (tx.state === "STATE_FAILED" || tx.state === "STATE_INVALID") {
        throw new Error(`relayer 거래 실패 (${tx.state})${tx.error_msg ? `: ${tx.error_msg}` : ""}`);
      }
    }
    throw new Error("relayer 거래 확인 시간 초과 — 제출은 됐을 수 있어 다음 사이클에 잔고로 재확인");
  }
  throw lastError || new Error("relayer 제출 실패");
}

async function executeRedeemTarget(target, creds) {
  const ctx = await fetchMarketContext(target.condition_id);
  if (!ctx) throw new Error("마켓 컨텍스트 조회 실패");
  if (ctx.version === "v2") {
    if (!ctx.positionIds || ctx.positionIds.length < 2) throw new Error("v2 포지션 ID 없음");
    const balances = [];
    for (let i = 0; i < 2; i += 1) {
      balances.push(await erc1155Balance(POSITION_MANAGER, state.funder, ctx.positionIds[i]));
    }
    // 잔고가 전부 0이면 이미 상환된 건 — 제출 없이 종결한다 (F3).
    // 종전에는 "상환할 잔고 없음" 오류로 빠져 매 사이클 재시도됐다.
    if (redeemBalanceGate(balances) === "already") return { alreadyRedeemed: true };
    const calls = [];
    for (let i = 0; i < 2; i += 1) {
      if (balances[i] > 0n) calls.push(buildV2RedeemCall(target.condition_id, i, balances[i]));
    }
    return submitRedeemBatch(target.condition_id, calls, creds);
  }
  // v1(CTF) — 토큰 잔고를 읽을 수 있으면 같은 종결 판정을 적용한다 (F3).
  // 식별자가 없으면 종전대로 제출해 판단을 미룬다 (지급 0 상환은 무해하다).
  if (ctx.tokenIds) {
    const balances = [];
    for (const tid of ctx.tokenIds.slice(0, 2)) {
      balances.push(await erc1155Balance(CTF_CONTRACT, state.funder, tid));
    }
    if (redeemBalanceGate(balances) === "already") return { alreadyRedeemed: true };
  }
  return submitRedeemBatch(
    target.condition_id,
    [buildCtfRedeemCall(target.condition_id, ctx.negRisk)],
    creds,
  );
}

// 사이클 단계: 손절 다음·신규 매수 이전. 돌려주는 값은 이번 사이클 회수액 합계.
async function runRedeems(gen) {
  ingestRedeemPending();
  const plan = planRedeems(loadRedeemPending());
  if (plan.errors.length) {
    // 형식 오류 항목은 영원히 유효해지지 않는다 — 로그를 남기고 대기
    // 목록에서 제거한다 (부수3: 종전에는 무로깅으로 매 사이클 재파싱됐다)
    const bad = new Set(plan.errors.map((x) => x.entry));
    saveRedeemPending(loadRedeemPending().filter((e) => !bad.has(e)));
    log(`리딤 계획 제외 ${plan.errors.length}건 — condition_id 형식 오류로 대기 목록에서 제거`);
  }
  // worthless streak는 이번 사이클에 정산 조회가 성공해 새 증거가 있을
  // 때만 올린다 — 조회 실패로 win/lost/closed가 전부 null인 사이클에서
  // 저장된 항목만으로 streak가 올라 종결까지 가면, 가치 갱신이 늦은
  // 승자 건이 영구 누락된다 (네이티브 NativeCycle과 같은 가드, v0.3.39 경화).
  const settlementKnown = state.lastWinRows != null
    || state.lastLostRows != null || state.lastClosedRows != null;
  if (plan.worthless.length && settlementKnown) {
    // 전액 손실 종결은 3사이클 연속 확인 후에만 — 정산 직후 과도기에
    // 가치가 0으로 보이는 승자를 한 번의 관측으로 영구 종결하면 회수금이
    // 동결된다. 사이클마다 pending 항목에 연속 0 확인 횟수를 쌓는다.
    const pendingNow = loadRedeemPending();
    const byCond = new Map(pendingNow.map((e) => [e.condition_id, e]));
    const ripe = [];
    const worthlessConds = new Set(plan.worthless.map((e) => e.condition_id));
    let streakChanged = false;
    for (const cid of worthlessConds) {
      const entry = byCond.get(cid);
      if (!entry) continue;
      entry.zero_streak = (entry.zero_streak || 0) + 1;
      streakChanged = true;
      if (entry.zero_streak >= 3) ripe.push(...plan.worthless.filter((e) => e.condition_id === cid));
    }
    // 승이 생긴 condition은 연속 확인을 리셋한다
    for (const t of plan.targets) {
      const entry = byCond.get(t.condition_id);
      if (entry && entry.zero_streak) {
        entry.zero_streak = 0;
        streakChanged = true;
      }
    }
    if (streakChanged) saveRedeemPending(pendingNow);
    if (ripe.length) closeWorthless(ripe);
  }
  if (!plan.targets.length) return 0;
  const creds = readRelayerCreds();
  if (!creds) {
    // 경고는 엔진 수명당 1회가 아니라 하루 1회 — 묶인 금액이 커져도
    // 조용히 동결되지 않게, 대기 금액 합계와 함께 다시 알린다.
    const today = new Date().toISOString().slice(0, 10);
    if (state.redeemKeyWarnedDay !== today) {
      state.redeemKeyWarnedDay = today;
      const held = round2(plan.targets.reduce((s, t) => s + (t.expected_usd || 0), 0));
      log(`리딤 대기 ${plan.targets.length}건 (약 $${held}) — Relayer API 키가 없어 제출을 보류합니다 (키 등록 후 자동 실행)`);
    }
    return 0;
  }
  let recoveredTotal = 0;
  for (const target of plan.targets) {
    if (!state.running || gen !== state.gen) break;
    const lockKey = `redeem:${target.condition_id}`;
    if (!tryAcquireSubmitLock(lockKey)) continue;
    beginSubmit();
    try {
      const before = await clobCash();
      const res = await executeRedeemTarget(target, creds);
      if (res?.alreadyRedeemed) {
        // 제출은 됐으나 확인이 늦어 실제로는 확정됐던 건 — 이중 회수는
        // 온체인 잔고 0이 막고, 여기서는 종결 기록만 남긴다 (F3)
        recordRedeemHistory({
          ts: new Date().toISOString(),
          condition_id: target.condition_id,
          expected_usd: target.expected_usd,
          recovered_usd: 0,
          tx_hash: null,
          transaction_id: null,
          verify_status: "already_redeemed",
        });
        removeRedeemPending(target.condition_id);
        log(`리딤 종결 ${target.condition_id.slice(0, 10)}… · 이미 상환됨 (온체인 잔고 0 확인)`);
        continue;
      }
      let recovered = null;
      let verifyStatus;
      try {
        const ver = verifyRecovery(before, await clobCash(), target.expected_usd);
        recovered = ver.delta_usd;
        verifyStatus = ver.status;
      } catch (e) {
        verifyStatus = "submitted_unverified";
        log(`리딤 제출 후 잔고 측정 실패 ${target.condition_id.slice(0, 10)}…: ${String(e.message ?? e).slice(0, 120)}`);
      }
      recordRedeemHistory({
        ts: new Date().toISOString(),
        condition_id: target.condition_id,
        expected_usd: target.expected_usd,
        recovered_usd: recovered,
        tx_hash: res.txHash,
        transaction_id: res.transactionId,
        verify_status: verifyStatus,
      });
      removeRedeemPending(target.condition_id);
      if (recovered) recoveredTotal = round2(recoveredTotal + recovered);
      log(
        `리딤 회수 ${target.condition_id.slice(0, 10)}… · 예상 $${target.expected_usd} · ` +
        `회수 ${recovered == null ? "미확인" : `$${recovered}`} · ${verifyStatus}`,
      );
    } catch (e) {
      // 건별 실패는 pending을 유지하고 다음 사이클에 재시도한다 (영구 래치 없음)
      log(`리딤 실패 ${target.condition_id.slice(0, 10)}…: ${String(e.message ?? e).slice(0, 140)} — 다음 사이클에 재시도`);
    } finally {
      endSubmit();
      releaseSubmitLock(lockKey);
    }
  }
  return recoveredTotal;
}

// ── 셀프 업데이트 ─────────────────────────────────────────────
// 감지는 GitHub 최신 릴리즈 조회뿐이고, 설치는 네이티브 브리지가
// DownloadManager로 받아 패키지 인스톨러를 띄우는 지점이 마지막이다.
// 자동 설치는 안드로이드 정책상 불가하므로 그 이상 우회하지 않는다.
// 감지 실패(네트워크·레이트리밋 등)는 전부 조용히 삼킨다 — 업데이트
// 확인이 엔진 동작에 영향을 주면 안 된다.
const RELEASES_LATEST_URL =
  "https://api.github.com/repos/roomedia/boringmech/releases/latest";
const RELEASES_PAGE_URL = "https://github.com/roomedia/boringmech/releases/latest";
const UPDATE_CHECK_EVERY_CYCLES = 30;

export function parseVersion(v) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(String(v ?? "").trim());
  if (!m) return null;
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

// a가 b보다 크면 1, 같으면 0, 작으면 -1. 어느 한쪽이라도 형식이 깨지면 null.
export function compareVersions(a, b) {
  const pa = parseVersion(a);
  const pb = parseVersion(b);
  if (!pa || !pb) return null;
  for (let i = 0; i < 3; i += 1) {
    if (pa[i] !== pb[i]) return pa[i] < pb[i] ? -1 : 1;
  }
  return 0;
}

// GitHub releases/latest 응답에서 설치에 필요한 것만 뽑는다.
// digest는 "sha256:<hex>" 형식으로 오며, 없으면 빈 문자열 (대조 생략).
export function parseLatestRelease(json) {
  if (!json || typeof json !== "object") return null;
  const tag = String(json.tag_name || "");
  if (!parseVersion(tag)) return null;
  const assets = Array.isArray(json.assets) ? json.assets : [];
  const apk = assets.find((a) => /^boringmech-v.+\.apk$/.test(a?.name || ""));
  if (!apk?.browser_download_url) return null;
  return {
    tag,
    assetName: apk.name,
    url: apk.browser_download_url,
    digest: typeof apk.digest === "string" ? apk.digest : "",
  };
}

async function checkForUpdate() {
  try {
    if (!APP_VERSION) return;
    const rel = parseLatestRelease(await fetchJson(RELEASES_LATEST_URL));
    if (!rel) return;
    if (compareVersions(rel.tag, APP_VERSION) !== 1) return;
    state.updateInfo = rel;
    state.onUpdate(rel);
    if (state.updateNotifiedTag !== rel.tag) {
      state.updateNotifiedTag = rel.tag;
      log(`새 버전 ${rel.tag} 사용 가능 — 업데이트 버튼으로 설치하세요`);
    }
  } catch {
    // 조용히 넘어간다
  }
}

function startUpdate() {
  const info = state.updateInfo;
  if (!info) return;
  try {
    if (window.AndroidBridge?.downloadAndInstall) {
      window.AndroidBridge.downloadAndInstall(info.url, info.assetName, info.digest);
      log(`업데이트 다운로드 시작 · ${info.tag}`);
      return;
    }
  } catch {}
  // 네이티브 브리지가 없는 환경(브라우저 미리보기)에서는 릴리즈 페이지를 연다
  window.open(RELEASES_PAGE_URL, "_blank");
}

function start() {
  if (!state.client || state.running) return;
  state.running = true;
  setRunIntent(true);
  state.gen += 1;
  state.cycle = 0;
  state.posGapWarned = false;
  // 사이클 진행 중에도 주기적으로 하트비트를 남긴다 (F1) — 긴 사이클이
  // stale로 오인돼 건강한 페이지가 재로드되는 것을 막는 본체.
  // JS 메인 스레드가 진짜로 멈추면 이 타이머도 멈추므로 워치독은 여전히 유효하다.
  if (state.hbTimer) clearInterval(state.hbTimer);
  state.hbTimer = setInterval(beatHeartbeat, HEARTBEAT_INTERVAL_MS);
  // 루프 자가 감시 — 하트비트와 분리된 타이머로 체인 단절을 본다.
  state.lastCycleDoneMs = Date.now();
  state.cycleInFlight = false;
  state.cycleErrors = 0;
  if (state.watchTimer) clearInterval(state.watchTimer);
  state.watchTimer = setInterval(checkLoopAlive, LOOP_WATCH_INTERVAL_MS);
  log("엔진 실행 시작");
  state.onState(state.lastSnapshot);
  loop(state.gen);
}

function stop() {
  setRunIntent(false);
  state.gen += 1; // 진행 중 사이클 무효화 — 정지→재실행이 겹쳐도 구 사이클이 이어 사지 않게
  if (!state.running) return;
  state.running = false;
  if (state.timer) clearTimeout(state.timer);
  state.timer = null;
  if (state.hbTimer) clearInterval(state.hbTimer);
  state.hbTimer = null;
  if (state.watchTimer) clearInterval(state.watchTimer);
  state.watchTimer = null;
  setStage("stopped");
  log("엔진 정지");
  state.onState(state.lastSnapshot);
}

async function link(privateKeyRaw) {
  const hex = normalizePrivateKey(privateKeyRaw);
  const { account, wallet } = makeSigner(hex);
  const eoa = account.address;
  const funder = await detectFunder(eoa);
  if (!funder) {
    throw new Error(
      "Polymarket 웹사이트에서 지갑을 한 번 활성화한 뒤 다시 연동하세요.",
    );
  }
  const l1 = withClientTimeout(new ClobClient({ host: CLOB_HOST, chain: Chain.POLYGON, signer: wallet }));
  const creds = await l1.createOrDeriveApiKey();
  state.client = buildClient(wallet, creds, funder);
  state.account = account;
  state.eoa = eoa;
  state.funder = funder;
  const snap = await snapshot();
  if (!storeSecret(LS.key, hex) || !storeSecret(LS.creds, JSON.stringify(creds))) {
    throw new Error("키 암호화 저장에 실패했습니다 — 다시 시도하세요.");
  }
  localStorage.setItem(LS.funder, funder);
  // 네이티브 엔진용 미러 — funder·creds·개인키·Relayer 키 암호문을
  // SharedPreferences에도 남긴다. 실패해도 연동 동작에는 영향 없다.
  try { window.AndroidBridge?.setFunderMirror?.(funder); } catch {}
  try { window.AndroidBridge?.setCredsMirror?.(eoa, localStorage.getItem(LS.creds)); } catch {}
  try { window.AndroidBridge?.setPkMirror?.(localStorage.getItem(LS.key)); } catch {}
  try { window.AndroidBridge?.setRelayerMirror?.(localStorage.getItem(LS.relayer)); } catch {}
  log(`연동 완료 · funder ${funder.slice(0, 10)}…`);
  void checkForUpdate();
  return snap;
}

// 동기 부팅 판정 — 네트워크를 전혀 타지 않는다. 키가 저장돼 있으면
// 첫 화면은 본 화면이어야 하므로, 화면 결정에 필요한 것(주소·마지막
// 스냅샷 캐시·실행 의도)만 로컬에서 읽어 돌려준다. 클라이언트 구축과
// 새 스냅샷은 restore()가 이어서 한다.
function boot() {
  migrateSecrets();
  let raw = null;
  try {
    raw = localStorage.getItem(LS.key);
  } catch {}
  if (!raw) return { view: "link", snap: null, eoa: null, funder: null };
  const pk = readSecret(LS.key);
  // 저장분은 있는데 복호화에 실패하면 지어내지 않고 연동 화면으로 떨어진다
  if (!pk) return { view: "link", snap: null, eoa: null, funder: null };
  const { account } = makeSigner(pk);
  state.account = account;
  let funder = null;
  try {
    funder = localStorage.getItem(LS.funder);
  } catch {}
  // 저장된 funder가 주소 형식이 아니면 채택하지 않는다 — 손상된 값을
  // 그대로 쓰면 잔고·포지션 조회가 엉뚱한 대상을 향한다.
  if (funder && !/^0x[0-9a-fA-F]{40}$/.test(funder)) funder = null;
  state.eoa = account.address;
  state.funder = funder;
  // 네이티브 엔진용 미러 (link와 같은 지점) — boot는 뷰어 새로고침마다
  // 불리므로 미러가 항상 최신 저장분을 따라간다. 값은 멱등하게 덮는다.
  // 단 creds는 LS에 값이 있을 때만 덮는다 — 네이티브 엔진이 파생해
  // 미러에 직접 남긴 creds를 낡은 LS 상태로 지우지 않기 위해서다.
  try { window.AndroidBridge?.setFunderMirror?.(funder); } catch {}
  try {
    const credsRaw = localStorage.getItem(LS.creds);
    if (credsRaw) {
      window.AndroidBridge?.setCredsMirror?.(account.address, credsRaw);
    }
  } catch {}
  try { window.AndroidBridge?.setPkMirror?.(localStorage.getItem(LS.key)); } catch {}
  try { window.AndroidBridge?.setRelayerMirror?.(localStorage.getItem(LS.relayer)); } catch {}
  return {
    view: bootView(true, peekRunIntent()),
    snap: cachedSnapshot(),
    eoa: account.address,
    funder,
  };
}

async function restore() {
  migrateSecrets();
  // 사이클 오류 에스컬레이션으로 자가 재로드한 직후면 그 흔적을 남긴다
  const selfReloadAt = lastSelfReloadMs();
  if (selfReloadAt > 0 && Date.now() - selfReloadAt < SELF_RELOAD_COOLDOWN_MS) {
    log("사이클 오류 연속으로 자가 재로드한 페이지입니다 — 복원을 이어갑니다");
  }
  // 이전 실행이 사이클 중간 단계에서 끝난 흔적이 있으면 중단 지점을 남긴다
  const prevStage = readStage();
  if (prevStage && prevStage.stage !== "idle" && prevStage.stage !== "stopped") {
    log(
      `이전 실행 중단 지점: ${prevStage.stage} 단계 (사이클 ${prevStage.cycle ?? "?"}) — ` +
        "정상 완료 없이 종료된 흔적입니다",
    );
  }
  // (재)로드된 페이지는 제출 중이 아니다 — 이전 페이지가 남긴 워치독
  // 제출 가드를 해제해 워치독이 다시 일할 수 있게 한다 (F1)
  setNativeSubmitFlag(false);
  const pk = readSecret(LS.key);
  if (!pk) return null;
  const { account, wallet } = makeSigner(pk);
  // 복원 단계는 네트워크 오류 1회에 무너지지 않게 단계마다 재시도한다 (F2).
  // 끝내 실패하면 시작 판정에 도달하지 못한 채 조용히 죽지 않고,
  // 사유를 로그로 남기고 null을 돌려준다 (복구는 워치독 재로드가 이어받는다).
  let funder = null;
  try {
    funder = localStorage.getItem(LS.funder);
  } catch {}
  if (funder && !/^0x[0-9a-fA-F]{40}$/.test(funder)) funder = null;
  if (!funder) {
    funder = await retryAsync(() => detectFunder(account.address)).catch(() => null);
  }
  if (!funder) {
    log("복원 실패 — deposit wallet을 찾지 못했습니다 (네트워크 상태 확인 필요)");
    return null;
  }
  // 복원 진행 신호 — 단계가 전진하는 동안은 네이티브 부팅 감시의
  // 기한이 미뤄진다 (느린 복원을 부팅 실패로 오인하지 않게).
  beatProgress();
  let creds = null;
  try {
    creds = JSON.parse(readSecret(LS.creds) || "null");
  } catch {
    creds = null;
  }
  if (!creds) {
    const l1 = withClientTimeout(new ClobClient({ host: CLOB_HOST, chain: Chain.POLYGON, signer: wallet }));
    creds = await retryAsync(() => l1.createOrDeriveApiKey()).catch(() => null);
    if (!creds) {
      log("복원 실패 — API 키를 만들지 못했습니다 (네트워크 상태 확인 필요)");
      return null;
    }
    storeSecret(LS.creds, JSON.stringify(creds));
  }
  state.client = buildClient(wallet, creds, funder);
  state.account = account;
  state.eoa = account.address;
  state.funder = funder;
  beatProgress();
  // 스냅샷이 끝내 실패해도 클라이언트가 있으면 엔진은 시작할 수 있다 —
  // 첫 사이클이 스냅샷부터 다시 시도하므로 복원 전체를 막지 않는다 (F2).
  const snap = await retryAsync(() => snapshot()).catch((e) => {
    log(`복원 중 스냅샷 실패: ${String(e.message ?? e).slice(0, 120)} — 사이클에서 재시도합니다`);
    return null;
  });
  void checkForUpdate();
  reportWatchdogRecovery();
  const intent = runIntentState();
  if (intent.mirror === false && intent.saved) {
    // 알림·뷰어에서 정지했으면(네이티브 미러 해제) LS 플래그를 정리한다
    try {
      localStorage.removeItem(LS.runIntent);
    } catch {}
  }
  if (shouldAutoStart(hostMode(), intent.saved, intent.mirror)) {
    log("이전 실행 의도 복원 — 엔진 자동 재개");
    start();
  }
  return snap;
}

function unlink() {
  stop();
  state.client = null;
  state.eoa = null;
  state.funder = null;
  state.lastSnapshot = null;
  state.settledBaseline = null;
  state.settledBaselineLoaded = false;
  // 영속 미러(부수1)까지 비운다 — localStorage만 지우면 미러에서 되살아난다
  for (const k of Object.values(LS)) {
    try {
      localStorage.removeItem(k);
    } catch {}
    try {
      window.AndroidBridge?.removeDurable?.(k);
    } catch {}
  }
  // 비밀 미러(개인키·creds·Relayer·funder·eoa)도 비운다 — 네이티브
  // 엔진이 이전 계정의 자격증명으로 복원되지 않게 한다.
  try { window.AndroidBridge?.clearSecretMirrors?.(); } catch {}
}

window.BM = {
  boot,
  link,
  restore,
  unlink,
  snapshot,
  start,
  stop,
  sizing,
  betUsdFor,
  parseTradeSettings,
  settingsSummaryText,
  checkForUpdate,
  startUpdate,
  hostMode,
  isEngineUnresponsive,
  compareVersions,
  parseSnapCache: snapshotFromCache,
  readSnapCache: cachedSnapshot,
  hasRelayerKey: () => !!readRelayerCreds(),
  setRelayerKey(key, address) {
    const k = String(key || "").trim();
    const a = String(address || "").trim();
    if (!k || !/^0x[0-9a-fA-F]{40}$/.test(a)) return false;
    const ok = storeSecret(LS.relayer, JSON.stringify({ key: k, address: a }));
    if (ok) {
      state.redeemKeyWarnedDay = "";
      try { window.AndroidBridge?.setRelayerMirror?.(localStorage.getItem(LS.relayer)); } catch {}
    }
    return ok;
  },
  clearRelayerKey() {
    try {
      localStorage.removeItem(LS.relayer);
    } catch {}
    try { window.AndroidBridge?.setRelayerMirror?.(null); } catch {}
  },
  get version() {
    return APP_VERSION;
  },
  get updateInfo() {
    return state.updateInfo;
  },
  get running() {
    return state.running;
  },
  get linked() {
    return Boolean(state.client);
  },
  get eoa() {
    return state.eoa;
  },
  get funder() {
    return state.funder;
  },
  set onLog(fn) {
    state.onLog = fn;
  },
  set onState(fn) {
    state.onState = fn;
  },
  set onUpdate(fn) {
    state.onUpdate = fn;
  },
};
