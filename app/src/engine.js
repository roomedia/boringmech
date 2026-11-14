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
  gen: 0,
  sessionFills: 0,
  posGapWarned: false,
  updateInfo: null,
  updateNotifiedTag: "",
  onLog: () => {},
  onState: () => {},
  onUpdate: () => {},
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

async function runCycle(gen) {
  const snap = await snapshot();
  state.onState(snap);
  log(
    `사이클 ${state.cycle} · 잔고 $${snap.cash} · 평가 $${snap.equity} · ` +
    `건당 $${snap.bet} · 슬롯 ${snap.slots} · 보유 ${snap.openCount}`,
  );
  // 체결로 기록한 건은 다음 사이클 스냅샷에 포지션으로 실재해야 한다 —
  // 어긋나면 조용히 넘기지 않고 경고로 드러낸다 (실행당 1회).
  if (state.sessionFills > 0 && !state.posGapWarned) {
    const found = snap.openCount + snap.settledCount;
    if (found < state.sessionFills) {
      state.posGapWarned = true;
      log(`보유 대조 경고: 체결 기록 ${state.sessionFills}건인데 포지션은 ${found}건만 확인됨`);
    }
  }
  if (snap.slots < 1) {
    log("슬롯 없음 — 이번 사이클 매수 없음");
    return;
  }
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
    let signed;
    try {
      signed = await state.client.createOrder(
        {
          tokenID: detail.tokenId,
          price: detail.limitPrice,
          size: detail.shares,
          side: Side.BUY,
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

async function loop(gen) {
  if (!state.running || gen !== state.gen) return;
  state.cycle += 1;
  try {
    await runCycle(gen);
  } catch (e) {
    log(`사이클 오류: ${String(e.message ?? e).slice(0, 160)}`);
  }
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
  void checkForUpdate();
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
  void checkForUpdate();
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
  checkForUpdate,
  startUpdate,
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
