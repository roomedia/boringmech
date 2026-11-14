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
const DATA_API_V2 = "https://data-api.polymarket.com/v2";
const PUSD = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB";
const POLYGON_RPCS = [
  "https://polygon-bor-rpc.publicnode.com",
  "https://1rpc.io/matic",
  "https://polygon.drpc.org",
  "https://polygon.api.onfinality.io/public",
];

const BET_PCT = 0.01;
const BET_MIN_USD = 5.0;
const CASH_FLOOR_PCT = 0.3;
const RESERVE_USD = 2.87;
const MAX_POSITIONS = 70;
const BAND_LO = 0.6;
const BAND_HI = 0.9;
const SPREAD_MAX = 0.02;
const ASK_DEPTH_MIN_USD = 10.0;
const SLIPPAGE_CAP = 0.015;
const SNAPSHOT_MATCH_TOL = 0.03;
const VERIFY_CAP = 60;
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
  stopLoss: "bm_stoploss",
  stopLossArchive: "bm_stoploss_archive",
  settledBase: "bm_settled_base",
  relayer: "bm_relayer",
  redeemPending: "bm_redeem_pending",
  redeemHistory: "bm_redeem_history",
  redeemClosed: "bm_redeem_closed",
  stage: "bm_stage",
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

export function betUsdFor(equity) {
  return round2(Math.max(BET_MIN_USD, (equity || 0) * BET_PCT));
}

export function sizing(cash, equity, openCount) {
  // 비유한 입력(NaN·Infinity)이면 사이징을 닫는다 — 측정값을 못 믿으면 사지 않는다
  if (!Number.isFinite(cash) || !Number.isFinite(equity) || !Number.isFinite(openCount)) {
    return { cashFloor: RESERVE_USD, available: 0, bet: betUsdFor(0), slots: 0 };
  }
  const cashFloor = round2(Math.max(RESERVE_USD, equity * CASH_FLOOR_PCT));
  const available = round2(Math.max(0, cash - cashFloor));
  const bet = betUsdFor(equity);
  const cashSlots = Math.floor((available + 1e-9) / bet);
  const slots = Math.max(0, Math.min(cashSlots, MAX_POSITIONS - openCount));
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
    out.push(...(data.data || []));
    cursor = data.pagination?.next_cursor;
    if (!cursor) return out;
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
// REDEEMABLE로 보존되므로(문서 명시), 조회 필터 결과로만 가른다
// (status=REDEEMABLE 조회 = 승, status=REDEEMABLE_LOST 조회 = 패).
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
export function summarizeBook(allRows, winRows, lostRows, closedRows, stopLossRecords) {
  const open = (allRows || []).filter((p) => p.status === "OPEN");
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
          entries.set(key, { verdict: verdictOf(p), pnl: realizedOf(kind, p), kind });
        }
      }
    };
    add(winRows, "win", () => "win");
    add(lostRows, "loss", () => "loss");
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
    add(closedRows, "closed", judgeClosedRow);
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
  };
}

async function fetchPositionsValue(funder) {
  const data = await fetchJson(
    `${DATA_API_V2}/value?${new URLSearchParams({ user: funder })}`,
  );
  return Number(data.data?.value || 0);
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

export function scanCandidates(markets, heldSlugs, boughtSlugs) {
  const today = new Date();
  const iso = (d) => d.toISOString().slice(0, 10);
  const todayIso = iso(today);
  const endMaxIso = iso(new Date(today.getTime() + 45 * 86400_000));
  const out = [];
  for (const m of markets) {
    if (!m.active || m.closed) continue;
    if (m.acceptingOrders === false || m.archived === true) continue; // 주문 수용 중단·보관 마켓 (문서 Market Details — Market Status)
    const slug = m.slug || "";
    if (SHORT_TERM_RE.test(slug)) continue;
    if (m.sportsMarketType) continue; // 스포츠는 목록 단계에서 제외 (검증 단계에도 가드로 남겨 둠)
    if (heldSlugs.has(slug) || boughtSlugs.has(slug)) continue;
    const end = (m.endDate || "").slice(0, 10);
    if (!(todayIso < end && end <= endMaxIso)) continue;
    if (Number(m.volume || 0) < 10000) continue;
    let prices;
    try {
      prices = JSON.parse(m.outcomePrices || "[]").map(Number);
    } catch {
      continue;
    }
    if (prices.length < 2) continue;
    const side = prices[0] >= prices[1] ? "yes" : "no";
    const price = side === "yes" ? prices[0] : prices[1];
    if (price < BAND_LO || price > BAND_HI) continue;
    out.push({ slug, question: m.question || "", side, price, endDate: m.endDate || "" });
  }
  // 매수 우선순위: 0.70–0.80 구간 먼저, 그 안에서 만기 가까운 순
  // (동률은 입력 순서 유지 — Array.sort는 안정 정렬이다)
  const bandGroup = (p) => (p >= 0.7 && p < 0.8 ? 0 : 1);
  out.sort((a, b) =>
    bandGroup(a.price) - bandGroup(b.price) ||
    a.endDate.localeCompare(b.endDate));
  return out;
}

function roundTick(price, tick) {
  const q = Math.floor(price / tick + 0.5);
  return Number((q * tick).toFixed(6));
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
  updateInfo: null,
  updateNotifiedTag: "",
  onLog: () => {},
  onState: () => {},
  onUpdate: () => {},
  lastSnapshot: null,
  lastOpenRows: [],
  lastWinRows: null,
  lastLostRows: null,
  redeemKeyWarned: false,
  settledBaseline: null,
  settledBaselineLoaded: false,
  submitCount: 0,
  hbTimer: null,
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

function boughtSlugs() {
  try {
    return new Set(JSON.parse(durableRead(LS.bought) || "[]"));
  } catch {
    return new Set();
  }
}

function markBought(slug) {
  const set = boughtSlugs();
  set.add(slug);
  durableWrite(LS.bought, JSON.stringify([...set]));
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

async function snapshot() {
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
  const book = summarizeBook(positions, winRows, lostRows, closedRows, stopLossRecords());
  const s = sizing(cash, equity, book.openCount);
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
    ...s,
  };
  state.lastSnapshot = snap;
  state.lastOpenRows = positions.filter((p) => p.status === "OPEN");
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

async function verifyCandidate(cand, bet) {
  const reject = (reason) => ({ ok: false, reason });
  const page = await fetchJson(
    `${GAMMA_MARKETS_URL}?${new URLSearchParams({ slug: cand.slug })}`,
  );
  const m = Array.isArray(page) ? page[0] : null;
  if (!m) return reject("gamma_market_missing");
  if (m.sportsMarketType) return reject("sports");
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
  if (Math.abs(prices[idx] - cand.price) > SNAPSHOT_MATCH_TOL) {
    return reject("snapshot_mismatch");
  }
  const tokenId = tokens[idx];
  const book = await state.client.getOrderBook(tokenId);
  const asks = (book.asks || [])
    .map((a) => ({ price: Number(a.price), size: Number(a.size) }))
    .sort((a, b) => a.price - b.price);
  const bids = (book.bids || [])
    .map((b) => ({ price: Number(b.price), size: Number(b.size) }))
    .sort((a, b) => b.price - a.price);
  if (!asks.length) return reject("no_asks");
  if (!bids.length) return reject("no_bids");
  const bestAsk = asks[0].price;
  const bestBid = bids[0].price;
  if (bestAsk < BAND_LO || bestAsk > BAND_HI) return reject("ask_out_of_band");
  if (bestAsk - bestBid > SPREAD_MAX + 1e-9) return reject("spread_too_wide");
  if (asks[0].size * bestAsk < ASK_DEPTH_MIN_USD) return reject("ask_depth_low");
  const tick = Number(book.tick_size || (await state.client.getTickSize(tokenId)));
  const limitPrice = Math.min(roundTick(bestAsk + SLIPPAGE_CAP, tick), BAND_HI);
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
};

// 사이클 결과를 한 줄로 집계한다. 탈락을 건별로 찍으면 로그가 폭발하므로
// 사유별 카운트만 남긴다 — "왜 안 샀는지"가 사이클마다 보이게 하는 게 목적.
export function summarizeCycle({ candidates, verified, rejects, filled, unfilled, failed }) {
  const parts = [`사이클 요약 · 후보 ${candidates} · 검증 ${verified}`];
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
    return {
      filled: false,
      kind: "error",
      status: String(resp.status ?? ""),
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
export function stopLossVerdict(entry, current) {
  if (!Number.isFinite(entry) || !Number.isFinite(current)) return "skip";
  if (current >= DECIDED_HIGH || current <= DECIDED_LOW) return "decided";
  if (current <= entry - STOP_DROP) return "triggered";
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
    archive.push({ token_id: old.token_id, realized_pnl: Number(old.realized_pnl) });
  }
  if (archive.length > STOPLOSS_ARCHIVE_CAP) {
    archive = archive.slice(-STOPLOSS_ARCHIVE_CAP);
  }
  durableWrite(LS.stopLoss, JSON.stringify(recent));
  durableWrite(LS.stopLossArchive, JSON.stringify(archive));
}

async function executeStopLossSell(row, current) {
  if (!tryAcquireSubmitLock(row.slug)) {
    log(`손절 제출 잠금 중 — 건너뜀 ${row.slug}`);
    return false;
  }
  try {
    beginSubmit();
    try {
      return await executeStopLossSellLocked(row, current);
    } finally {
      endSubmit();
    }
  } finally {
    releaseSubmitLock(row.slug);
  }
}

async function executeStopLossSellLocked(row, current) {
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
  if (!Number.isFinite(shares) || shares < 0.01) return false;
  const book = await state.client.getOrderBook(row.token_id);
  const tick = Number(book.tick_size || (await state.client.getTickSize(row.token_id)));
  const floorPrice = Math.min(
    Math.max(tick, roundTick(current - SLIPPAGE_CAP, tick)),
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
    // 실재로 다시 판정한다 (남아 있으면 재시도, 없으면 종료).
    log(`손절 매도 결과 불명 ${slug}: ${String(e.message ?? e).slice(0, 120)} — 다음 사이클에 재확인`);
    return false;
  }
  const verdict = judgeFill(resp);
  if (!verdict.filled) {
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

async function checkStopLossPosition(row) {
  const slug = row?.slug;
  const entry = Number(row?.avg_price);
  const size = Number(row?.current_size);
  if (!slug || !row?.token_id || !Number.isFinite(entry)) return false;
  if (!Number.isFinite(size) || size <= 0) return false;
  const first = await fetchGammaPrices(slug).catch(() => null);
  if (!first) return false; // 조회 실패 — 0으로 취급하지 않고 스킵
  const idx = pickOutcomeIndex(first.outcomes, row);
  if (idx < 0 || idx >= first.prices.length) return false;
  if (stopLossVerdict(entry, first.prices[idx]) !== "triggered") return false;
  // 매도 직전 재검증 — 같은 가드로 다시 읽어 여전히 발화 상태일 때만 판다
  const second = await fetchGammaPrices(slug).catch(() => null);
  if (!second) return false;
  const idx2 = pickOutcomeIndex(second.outcomes, row);
  if (idx2 < 0 || idx2 >= second.prices.length) return false;
  const current = second.prices[idx2];
  if (stopLossVerdict(entry, current) !== "triggered") {
    log(`손절 재검증 탈락 ${slug} · 현재가 ${current} (진입 ${entry})`);
    return false;
  }
  return executeStopLossSell(row, current);
}

async function runStopLoss(gen) {
  let sold = 0;
  for (const row of state.lastOpenRows) {
    if (!state.running || gen !== state.gen) break;
    beatHeartbeat();
    try {
      if (await checkStopLossPosition(row)) sold += 1;
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
  setStage("snapshot");
  let snap = await snapshot();
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
  beatHeartbeat();
  // 손절은 신규 매수보다 먼저 — 팔린 건이 있으면 잔고·슬롯을 다시 읽는다
  setStage("stoploss");
  const soldCount = await runStopLoss(gen);
  beatHeartbeat();
  if (soldCount > 0) {
    snap = await snapshot();
    state.onState(snap);
  }
  // 리딤은 손절 다음·신규 매수 이전 — 회수액이 생기면 잔고를 다시 읽는다
  setStage("redeem");
  const recovered = await runRedeems(gen);
  beatHeartbeat();
  if (recovered > 0) {
    snap = await snapshot();
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
  setStage("scan");
  const markets = await fetchMarkets();
  const candidates = scanCandidates(markets, snap.heldSlugs, boughtSlugs());
  let slotsLeft = snap.slots;
  let verified = 0;
  const rejects = {};
  let filledCount = 0;
  let unfilledCount = 0;
  let failedCount = 0;
  const tallyReject = (reason) => {
    rejects[reason] = (rejects[reason] || 0) + 1;
  };
  for (const cand of candidates) {
    if (!state.running || gen !== state.gen) return;
    if (slotsLeft < 1 || verified >= VERIFY_CAP) break;
    beatHeartbeat();
    verified += 1;
    let detail;
    try {
      detail = await verifyCandidate(cand, snap.bet);
    } catch (e) {
      tallyReject("verify_error");
      log(`검증 오류 ${cand.slug}: ${e.message ?? e}`);
      continue;
    }
    if (!detail.ok) {
      tallyReject(detail.reason);
      continue;
    }
    if (!state.running || gen !== state.gen) return;
    // 제출 잠금 — 서명 시작부터 결과 확정까지 같은 slug 재진입을 막는다.
    // 세대 가드와 별개로 동작해, 이 지점을 지난 뒤 재시작·재개가 끼어도
    // 두 번째 루프는 잠금에서 걸려 같은 후보를 다시 제출하지 않는다.
    if (!tryAcquireSubmitLock(cand.slug)) {
      log(`제출 잠금 — 중복 제출 차단 ${cand.slug}`);
      continue;
    }
    try {
    // 제출 직전 bought 재확인 (이중 방어 — 먼저 끝난 제출이 기록했을 수 있다)
    if (boughtSlugs().has(cand.slug)) {
      log(`제출 직전 재확인 — 이미 매수 기록 있음 ${cand.slug}`);
      continue;
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
        continue;
      }
      let resp;
      try {
        resp = await state.client.postOrder(signed, OrderType.FOK);
      } catch (e) {
        // 제출됐으나 결과를 모르는 상태 — 체결됐을 수 있어 중복 매수 방지를 위해
        // 매수한 것으로 기록한다 (결과 불명 건은 로그로 드러나게 남긴다)
        failedCount += 1;
        markBought(cand.slug);
        const msg = String(e.message ?? e);
        log(`주문 결과 불명 ${cand.slug}: ${msg.slice(0, 120)} — 중복 방지 기록`);
        if (isFailsafe(msg)) {
          stop();
          log("failsafe 감지 — 엔진 정지. 정지 사유를 확인한 뒤 다시 실행하세요.");
          return;
        }
        continue;
      }
      const verdict = judgeFill(resp);
      if (!verdict.filled && verdict.kind !== "unfilled") {
        failedCount += 1;
        const msg = verdict.error || "주문 거부 (success=false)";
        // 다음 진단이 추측이 되지 않게 원본 응답을 같은 줄에 남긴다.
        // failsafe는 메시지 외에 HTTP 코드(예: 지역 제한 403)도 검사한다.
        log(`주문 실패 ${cand.slug}: ${String(msg).slice(0, 120)} · 응답 원문: ${JSON.stringify(resp).slice(0, 200)}`);
        if (isFailsafe(String(msg)) || isFailsafe(verdict.status)) {
          stop();
          log("failsafe 감지 — 엔진 정지. 정지 사유를 확인한 뒤 다시 실행하세요.");
          return;
        }
        continue;
      }
      if (!verdict.filled) {
        // 미체결 (unmatched·delayed·live 등) — 매수 기록을 남기지 않고 슬롯도 쓰지 않는다
        unfilledCount += 1;
        log(`미체결 ${cand.slug} · 응답 status: ${resp.status ?? "(없음)"} · 응답 원문: ${JSON.stringify(resp).slice(0, 200)}`);
        continue;
      }
      markBought(cand.slug);
      state.sessionFills += 1;
      filledCount += 1;
      slotsLeft -= 1;
      const fill = resp.makingAmount && resp.takingAmount
        ? ` 체결가 ${round2(Number(resp.makingAmount) / Number(resp.takingAmount))}`
        : "";
      log(`매수 ${cand.slug} · $${snap.bet} @ ${detail.limitPrice}${fill} · 상태 ${resp.status ?? "matched"}`);
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
  }
  log(summarizeCycle({
    candidates: candidates.length,
    verified,
    rejects,
    filled: filledCount,
    unfilled: unfilledCount,
    failed: failedCount,
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

async function loop(gen) {
  if (!state.running || gen !== state.gen) return;
  state.cycle += 1;
  beatHeartbeat();
  try {
    await runCycle(gen);
  } catch (e) {
    log(`사이클 오류: ${String(e.message ?? e).slice(0, 160)}`);
  }
  setStage("idle");
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

// 워치독이 재로드한 사실은 복원 직후 로그로 남긴다 — 조용한 복구 금지.
function reportWatchdogRecovery() {
  try {
    const n = Number(window.AndroidBridge?.getWatchdogReloads?.() || 0);
    if (!Number.isFinite(n) || n <= 0) return;
    const seen = Number(localStorage.getItem(LS.wdSeen) || 0);
    if (n > seen) {
      log(`워치독 복구 — 멈춘 엔진 페이지를 재로드했습니다 (누적 ${n}회)`);
      localStorage.setItem(LS.wdSeen, String(n));
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

function loadRedeemPending() {
  return loadDurableJsonList(LS.redeemPending);
}

function saveRedeemPending(list) {
  durableWrite(LS.redeemPending, JSON.stringify(list.slice(-300)));
}

// 스냅샷의 정산 행(REDEEMABLE/REDEEMABLE_LOST)을 pending에 수집한다.
// 이미 회수했거나 worthless로 종결한 condition은 다시 넣지 않는다.
function ingestRedeemPending() {
  const done = new Set([
    ...loadDurableJsonList(LS.redeemHistory).map((h) => h.condition_id),
    ...loadDurableJsonList(LS.redeemClosed),
  ]);
  const pending = loadRedeemPending();
  const seen = new Set(pending.map((e) => `${e.condition_id}|${e.token_id}`));
  let changed = false;
  const addRows = (rows, status) => {
    for (const r of rows || []) {
      if (!r.condition_id || done.has(r.condition_id)) continue;
      const k = `${r.condition_id}|${r.token_id}`;
      if (seen.has(k)) continue;
      seen.add(k);
      pending.push({
        condition_id: r.condition_id,
        token_id: r.token_id,
        slug: r.slug ?? null,
        status,
        current_value: Number(r.current_value || 0),
      });
      changed = true;
    }
  };
  addRows(state.lastWinRows, "REDEEMABLE");
  addRows(state.lastLostRows, "REDEEMABLE_LOST");
  if (changed) saveRedeemPending(pending);
}

function removeRedeemPending(conditionId) {
  saveRedeemPending(loadRedeemPending().filter((e) => e.condition_id !== conditionId));
}

function closeWorthless(entries) {
  const closed = loadDurableJsonList(LS.redeemClosed);
  const set = new Set(closed);
  for (const e of entries) {
    set.add(e.condition_id);
    log(`리딤 종결 (전액 손실) ${String(e.condition_id).slice(0, 10)}…`);
  }
  durableWrite(LS.redeemClosed, JSON.stringify([...set].slice(-500)));
  const ids = new Set(entries.map((e) => e.condition_id));
  saveRedeemPending(loadRedeemPending().filter((e) => !ids.has(e.condition_id)));
}

function recordRedeemHistory(rec) {
  const list = loadDurableJsonList(LS.redeemHistory);
  list.push(rec);
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
  if (plan.worthless.length) closeWorthless(plan.worthless);
  if (!plan.targets.length) return 0;
  const creds = readRelayerCreds();
  if (!creds) {
    if (!state.redeemKeyWarned) {
      state.redeemKeyWarned = true;
      log(`리딤 대기 ${plan.targets.length}건 — Relayer API 키가 없어 제출을 보류합니다 (키 등록 후 자동 실행)`);
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
  state.eoa = account.address;
  state.funder = funder;
  return {
    view: bootView(true, peekRunIntent()),
    snap: cachedSnapshot(),
    eoa: account.address,
    funder,
  };
}

async function restore() {
  migrateSecrets();
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
  if (!funder) {
    funder = await retryAsync(() => detectFunder(account.address)).catch(() => null);
  }
  if (!funder) {
    log("복원 실패 — deposit wallet을 찾지 못했습니다 (네트워크 상태 확인 필요)");
    return null;
  }
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
    if (ok) state.redeemKeyWarned = false;
    return ok;
  },
  clearRelayerKey() {
    try {
      localStorage.removeItem(LS.relayer);
    } catch {}
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
