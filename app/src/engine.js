import {
  AssetType,
  Chain,
  ClobClient,
  OrderType,
  Side,
  SignatureTypeV2,
} from "@polymarket/clob-client-v2";
import { createWalletClient, http } from "viem";
import { privateKeyToAccount } from "viem/accounts";
import { polygon } from "viem/chains";
import { deriveBeaconDepositWallet, deriveUupsDepositWallet } from "./depositWallet.js";

const CLOB_HOST = "https://clob.polymarket.com";
const GAMMA_MARKETS_URL = "https://gamma-api.polymarket.com/markets";
const DATA_API_V2 = "https://data-api.polymarket.com/v2";
const PUSD = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB";
const POLYGON_RPCS = [
  "https://polygon-bor-rpc.publicnode.com",
  "https://1rpc.io/matic",
  "https://polygon.drpc.org",
  "https://polygon-mainnet.public.blastapi.io",
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
};

const round2 = (x) => Math.round((x + Number.EPSILON) * 100) / 100;

export function betUsdFor(equity) {
  return round2(Math.max(BET_MIN_USD, (equity || 0) * BET_PCT));
}

export function sizing(cash, equity, openCount) {
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
      const resp = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
      });
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
  const resp = await fetch(url, { headers: { "User-Agent": "boringmech-app/0.3" } });
  if (!resp.ok) throw new Error(`HTTP ${resp.status} ${url.split("?")[0]}`);
  return resp.json();
}

async function fetchPositions(funder) {
  const out = [];
  let cursor = null;
  for (;;) {
    const params = new URLSearchParams({ user: funder, limit: "100" });
    if (cursor) params.set("cursor", cursor);
    const page = await fetchJson(`${DATA_API_V2}/positions?${params}`);
    out.push(...(page.data || []));
    cursor = page.pagination?.next_cursor;
    if (!cursor) return out;
  }
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

function scanCandidates(markets, heldSlugs, boughtSlugs) {
  const today = new Date();
  const iso = (d) => d.toISOString().slice(0, 10);
  const todayIso = iso(today);
  const endMaxIso = iso(new Date(today.getTime() + 45 * 86400_000));
  const out = [];
  for (const m of markets) {
    if (!m.active || m.closed) continue;
    const slug = m.slug || "";
    if (SHORT_TERM_RE.test(slug)) continue;
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
  // 매수 우선순위: 0.70–0.80 구간 먼저, 그 안에서 만기 먼 순 (동률은 입력 순서 유지)
  const bandGroup = (p) => (p >= 0.7 && p < 0.8 ? 0 : 1);
  out.sort((a, b) =>
    bandGroup(a.price) - bandGroup(b.price) ||
    b.endDate.localeCompare(a.endDate));
  return out;
}

function roundTick(price, tick) {
  const q = Math.floor(price / tick + 0.5);
  return Number((q * tick).toFixed(6));
}

const state = {
  client: null,
  eoa: null,
  funder: null,
  running: false,
  timer: null,
  cycle: 0,
  onLog: () => {},
  onState: () => {},
  lastSnapshot: null,
};

function log(line) {
  const t = new Date();
  const p = (n) => String(n).padStart(2, "0");
  state.onLog(`[${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}] ${line}`);
}

function boughtSlugs() {
  try {
    return new Set(JSON.parse(localStorage.getItem(LS.bought) || "[]"));
  } catch {
    return new Set();
  }
}

function markBought(slug) {
  const set = boughtSlugs();
  set.add(slug);
  localStorage.setItem(LS.bought, JSON.stringify([...set]));
}

function buildClient(signer, creds, funder) {
  return new ClobClient({
    host: CLOB_HOST,
    chain: Chain.POLYGON,
    signer,
    creds,
    signatureType: SignatureTypeV2.POLY_1271,
    funderAddress: funder,
  });
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
      transport: http(POLYGON_RPCS[0]),
    }),
  };
}

async function snapshot() {
  const bal = await state.client.getBalanceAllowance({
    asset_type: AssetType.COLLATERAL,
  });
  const cash = round2(Number(BigInt(bal.balance || "0")) / 1e6);
  const positions = await fetchPositions(state.funder);
  const positionsValue = round2(await fetchPositionsValue(state.funder));
  const equity = round2(cash + positionsValue);
  const open = positions.filter((p) => p.status === "OPEN");
  const settled = positions.filter(
    (p) => p.status === "REDEEMABLE" || p.status === "REDEEMABLE_LOST",
  );
  const s = sizing(cash, equity, open.length);
  const snap = {
    cash,
    positionsValue,
    equity,
    openCount: open.length,
    settledCount: settled.length,
    heldSlugs: new Set(open.map((p) => p.slug).filter(Boolean)),
    ...s,
  };
  state.lastSnapshot = snap;
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
  let tokens, prices;
  try {
    tokens = JSON.parse(m.clobTokenIds || "[]");
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
  const shares = Math.floor((bet / limitPrice) * 100) / 100;
  const minSize = Number(book.min_order_size || 0);
  if (shares < minSize) return reject("below_min_order_size");
  return {
    ok: true,
    tokenId,
    limitPrice,
    shares,
    tickSize: String(tick),
    negRisk: Boolean(book.neg_risk),
  };
}

const isFailsafe = (text) => {
  const t = (text || "").toLowerCase();
  return FAILSAFE_KEYWORDS.some((k) => t.includes(k));
};

async function runCycle() {
  const snap = await snapshot();
  state.onState(snap);
  log(
    `사이클 ${state.cycle} · 잔고 $${snap.cash} · 평가 $${snap.equity} · ` +
    `건당 $${snap.bet} · 슬롯 ${snap.slots} · 보유 ${snap.openCount}`,
  );
  if (snap.slots < 1) {
    log("슬롯 없음 — 이번 사이클 매수 없음");
    return;
  }
  const markets = await fetchMarkets();
  const candidates = scanCandidates(markets, snap.heldSlugs, boughtSlugs());
  let slotsLeft = snap.slots;
  let verified = 0;
  for (const cand of candidates) {
    if (!state.running) return;
    if (slotsLeft < 1 || verified >= VERIFY_CAP) break;
    verified += 1;
    let detail;
    try {
      detail = await verifyCandidate(cand, snap.bet);
    } catch (e) {
      log(`검증 오류 ${cand.slug}: ${e.message ?? e}`);
      continue;
    }
    if (!detail.ok) continue;
    if (!state.running) return;
    try {
      const signed = await state.client.createOrder(
        {
          tokenID: detail.tokenId,
          price: detail.limitPrice,
          size: detail.shares,
          side: Side.BUY,
        },
        { tickSize: detail.tickSize, negRisk: detail.negRisk },
      );
      const resp = await state.client.postOrder(signed, OrderType.FOK);
      if (resp.errorMsg) throw new Error(resp.errorMsg);
      markBought(cand.slug);
      slotsLeft -= 1;
      const fill = resp.makingAmount && resp.takingAmount
        ? ` 체결가 ${round2(Number(resp.makingAmount) / Number(resp.takingAmount))}`
        : "";
      log(`매수 ${cand.slug} · $${snap.bet} @ ${detail.limitPrice}${fill}`);
    } catch (e) {
      const msg = String(e.message ?? e);
      log(`주문 실패 ${cand.slug}: ${msg.slice(0, 120)}`);
      if (isFailsafe(msg)) {
        stop();
        log("failsafe 감지 — 엔진 정지. 정지 사유를 확인한 뒤 다시 실행하세요.");
        return;
      }
    }
  }
}

async function loop() {
  if (!state.running) return;
  state.cycle += 1;
  try {
    await runCycle();
  } catch (e) {
    log(`사이클 오류: ${String(e.message ?? e).slice(0, 160)}`);
  }
  if (state.running) {
    state.timer = setTimeout(loop, CYCLE_MS);
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

function runIntentSaved() {
  let saved = false;
  try {
    saved = localStorage.getItem(LS.runIntent) === "1";
  } catch {}
  if (!saved) return false;
  try {
    // 알림에서 정지했으면(네이티브 미러 해제) 재개하지 않고 플래그를 정리한다
    if (window.AndroidBridge && !window.AndroidBridge.getRunIntent()) {
      localStorage.removeItem(LS.runIntent);
      return false;
    }
  } catch {}
  return true;
}

function start() {
  if (!state.client || state.running) return;
  state.running = true;
  setRunIntent(true);
  state.cycle = 0;
  log("엔진 실행 시작");
  state.onState(state.lastSnapshot);
  loop();
}

function stop() {
  setRunIntent(false);
  if (!state.running) return;
  state.running = false;
  if (state.timer) clearTimeout(state.timer);
  state.timer = null;
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
  const l1 = new ClobClient({ host: CLOB_HOST, chain: Chain.POLYGON, signer: wallet });
  const creds = await l1.createOrDeriveApiKey();
  state.client = buildClient(wallet, creds, funder);
  state.eoa = eoa;
  state.funder = funder;
  const snap = await snapshot();
  localStorage.setItem(LS.key, hex);
  localStorage.setItem(LS.creds, JSON.stringify(creds));
  localStorage.setItem(LS.funder, funder);
  log(`연동 완료 · funder ${funder.slice(0, 10)}…`);
  return snap;
}

async function restore() {
  const pk = localStorage.getItem(LS.key);
  if (!pk) return null;
  const { account, wallet } = makeSigner(pk);
  const funder = localStorage.getItem(LS.funder) || (await detectFunder(account.address));
  if (!funder) return null;
  let creds = null;
  try {
    creds = JSON.parse(localStorage.getItem(LS.creds) || "null");
  } catch {
    creds = null;
  }
  if (!creds) {
    const l1 = new ClobClient({ host: CLOB_HOST, chain: Chain.POLYGON, signer: wallet });
    creds = await l1.createOrDeriveApiKey();
    localStorage.setItem(LS.creds, JSON.stringify(creds));
  }
  state.client = buildClient(wallet, creds, funder);
  state.eoa = account.address;
  state.funder = funder;
  const snap = await snapshot();
  if (runIntentSaved()) {
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
  for (const k of Object.values(LS)) localStorage.removeItem(k);
}

window.BM = {
  link,
  restore,
  unlink,
  snapshot,
  start,
  stop,
  sizing,
  betUsdFor,
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
};
