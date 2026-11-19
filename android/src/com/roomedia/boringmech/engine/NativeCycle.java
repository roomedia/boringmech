package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.crypto.CallData;
import com.roomedia.boringmech.crypto.Hex;
import com.roomedia.boringmech.crypto.JsNumber;
import com.roomedia.boringmech.crypto.Redeem;
import com.roomedia.boringmech.write.ClobWriteClient;
import com.roomedia.boringmech.write.JudgeFill;
import com.roomedia.boringmech.write.ManualSell;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.RedeemSubmitter;
import com.roomedia.boringmech.write.SignedOrder;
import com.roomedia.boringmech.write.StopLossLedger;
import com.roomedia.boringmech.write.StopLossSeller;
import com.roomedia.boringmech.write.SubmitGuard;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Supplier;

/**
 * 네이티브 사이클 오케스트레이션 — engine.js의 runCycle/loop/runStopLoss/runRedeems/
 * reconcileBoughtOnce/summarizeCycle를 그대로 이식한 순수 Java.
 *
 * 환경(시계·로그·하트비트·영속 저장소·알림)은 Env로, 네트워크는 Net으로 주입한다.
 * 운영 구현은 NativeEngine/NativeNet, 테스트는 가짜 구현을 쓴다.
 * 로그 문구는 JS 원본과 동일하게 유지한다 (뷰어의 오류 연속 판정·손절 실패 카운터가
 * 로그 문구 패턴에 걸려 있다).
 */
public final class NativeCycle {

    public static final int VERIFY_CAP = 60;
    public static final int ERROR_SELF_RESTART_THRESHOLD = 10;
    public static final long SELF_RESTART_COOLDOWN_MS = 600_000L;
    public static final int UPDATE_CHECK_EVERY_CYCLES = 30;

    private static final String POSITION_MANAGER = "0x006F54F7f9A22e0000CC2AB60031000000ae9fEF";
    private static final String CTF_CONTRACT = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045";

    // ── 환경 어댑터 ──────────────────────────────────────────────────────────

    public interface Env {
        boolean isRunning(int gen);

        int generation();

        long nowMs();

        /** 접두사 없는 로그 본문 한 줄 ([HH:mm:ss] 접두사는 어댑터가 붙인다). */
        void log(String body);

        void beatHeartbeat(int cycle);

        void beatProgress(int cycle);

        void setStage(String stage, int cycle);

        void putSnapCache(String json);

        void notifyMoney(String title, String body);

        String durableRead(String key);

        void durableWrite(String key, String value);

        /** stop() 등가 — 실행 의도 해제 + "엔진 정지" 로그 + 세대 무효화 + 서비스 종료. */
        void requestStop();

        long lastSelfReloadMs();

        void saveSelfReloadMs(long ms);

        /** 앱 versionName (예: "0.3.28"). 못 읽으면 null. */
        String appVersion();

        void saveUpdateInfo(String json);
    }

    public interface Net {
        /** Data API 포지션 전 페이지 (status null = 무지정). */
        List<Object> fetchPositions(String status) throws Exception;

        double fetchPositionsValue() throws Exception;

        BoughtReconcile.BuySlugs fetchBuySlugs() throws Exception;

        List<Object> fetchMarkets() throws Exception;

        /** Gamma slug 페이지 모델 (배열). 실패 시 예외 — 호출자가 JS .catch 의미론으로 감싼다. */
        Object gammaPageBySlug(String slug) throws Exception;

        /** Gamma condition_ids 조회 → 마켓 컨텍스트. 마켓이 없으면 null. */
        MarketContext fetchMarketContext(String conditionId) throws Exception;

        /** CLOB 담보 잔고 (SDK GET 의미론 — 오류 형태 응답은 0으로 읽는다). */
        double clobCash() throws Exception;

        /** CLOB 오더북 모델 (SDK GET 의미론 — 오류 형태 응답도 모델로 온다). */
        Object orderBook(String tokenId) throws Exception;

        double tickSize(String tokenId) throws Exception;

        BigInteger conditionalBalance(String tokenId) throws Exception;

        BigInteger erc1155Balance(String token, String owner, String tokenId) throws Exception;

        /** GitHub 최신 릴리스 JSON 모델. */
        Object fetchLatestReleaseJson() throws Exception;
    }

    public static final class MarketContext {
        public final boolean negRisk;
        public final String version;
        public final List<String> positionIds;
        public final List<String> tokenIds;

        public MarketContext(boolean negRisk, String version, List<String> positionIds, List<String> tokenIds) {
            this.negRisk = negRisk;
            this.version = version;
            this.positionIds = positionIds;
            this.tokenIds = tokenIds;
        }
    }

    public static final class Snap {
        public double cash;
        public double positionsValue;
        public double equity;
        public int openCount;
        public Integer settledCount;
        public Integer settledWins;
        public Integer settledLosses;
        public Integer settledStopLoss;
        public Integer settledRealizedExcluded;
        public Double settledRealizedPnl;
        public Double positionsPnlPct;
        public Set<String> heldSlugs = new LinkedHashSet<>();
        public Set<String> stopLossSlugs = new LinkedHashSet<>();
        public double cashFloor;
        public double available;
        public double bet;
        public int slots;
        /** 뷰어 종목 목록용 보유 행 요약 (스냅샷 캐시에만 실린다). */
        public List<Object> positions = new ArrayList<>();
    }

    public enum Result {OK, FAILED, ABORTED, RESTART}

    // ── 상태 (JS state 대응 — 페이지 수명 필드는 생성자, 세션 필드는 resetSession) ──

    private final Env env;
    private final Net net;
    private final ClobWriteClient client;
    private final RedeemSubmitter redeemSubmitter;
    private final SubmitGuard guard;
    private final StopLossLedger.Store ledgerStore;
    private final String funder;
    private final Supplier<RedeemSubmitter.RelayerCreds> relayerCreds;
    private final StopLossSeller seller;
    private final CandidateVerifier.GammaSource gammaSource;
    private final CandidateVerifier.BookSource bookSource;

    private int cycle = 0;
    private int cycleErrors = 0;
    private int sessionFills = 0;
    private boolean posGapWarned = false;
    /** slug → Gamma feeType 캐시 — 손절 판정의 Gamma 조회에서 채워지고 스냅샷의 수수료율 표시에 쓰인다. */
    private final Map<String, String> feeTypeBySlug = new HashMap<>();

    private List<Object> lastOpenRows = new ArrayList<>();
    private List<Object> lastWinRows;
    private List<Object> lastLostRows;
    private List<Object> lastClosedRows;
    private Snap lastSnapshot;

    private boolean boughtReconciled = false;
    private boolean reconcileFailLogged = false;
    private SettledBaseline.Baseline settledBaseline;
    private boolean settledBaselineLoaded = false;
    private String redeemKeyWarnedDay;
    private String updateNotifiedTag;

    public NativeCycle(Env env, Net net, ClobWriteClient client, RedeemSubmitter redeemSubmitter,
                       SubmitGuard guard, StopLossLedger.Store ledgerStore,
                       String funder, Supplier<RedeemSubmitter.RelayerCreds> relayerCreds,
                       ClobWriteClient.TimeoutRunner timeoutRunner) {
        this.env = env;
        this.net = net;
        this.client = client;
        this.redeemSubmitter = redeemSubmitter;
        this.guard = guard;
        this.ledgerStore = ledgerStore;
        this.funder = funder;
        this.relayerCreds = relayerCreds;
        this.seller = new StopLossSeller(client,
                net::conditionalBalance,
                new StopLossSeller.OrderBookSource() {
                    @Override
                    public StopLossSeller.OrderBookInfo getOrderBook(String tokenId) throws Exception {
                        Object model = net.orderBook(tokenId);
                        Object ts = Js.field(model, "tick_size");
                        String tick = Js.truthy(ts) ? Js.str(ts) : null;
                        return new StopLossSeller.OrderBookInfo(tick, Js.truthy(Js.field(model, "neg_risk")));
                    }

                    @Override
                    public String getTickSize(String tokenId) throws Exception {
                        return JsNumber.toString(net.tickSize(tokenId));
                    }
                },
                guard, ledgerStore, env::log, env::notifyMoney, timeoutRunner, env::nowMs,
                new StopLossSeller.ManualSellRecorder() {
                    @Override
                    public void record(Map<String, Object> rec) {
                        List<Object> list = loadDurableJsonList(ManualSell.LEDGER_KEY);
                        env.durableWrite(ManualSell.LEDGER_KEY,
                                ModelJson.stringify(ManualSell.appendRecord(list, rec)));
                    }

                    @Override
                    public void recordAttempt(String tokenId) {
                        List<Object> list = loadDurableJsonList(ManualSell.ATTEMPTED_KEY);
                        env.durableWrite(ManualSell.ATTEMPTED_KEY,
                                ModelJson.stringify(ManualSell.appendAttempted(list, tokenId)));
                    }
                });
        this.gammaSource = net::gammaPageBySlug;
        this.bookSource = new CandidateVerifier.BookSource() {
            @Override
            public Object getOrderBook(String tokenId) throws Exception {
                return timeoutRunner.run("getOrderBook", () -> net.orderBook(tokenId));
            }

            @Override
            public double getTickSize(String tokenId) throws Exception {
                return timeoutRunner.run("getTickSize", () -> net.tickSize(tokenId));
            }
        };
    }

    /** start() 등가 — JS start()가 리셋하는 범위와 같다 (cycle·posGapWarned·cycleErrors). */
    public void resetSession() {
        cycle = 0;
        cycleErrors = 0;
        posGapWarned = false;
    }

    public int cycle() {
        return cycle;
    }

    public int cycleErrors() {
        return cycleErrors;
    }

    public Snap lastSnapshot() {
        return lastSnapshot;
    }

    private void log(String body) {
        env.log(body);
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return m != null ? m : String.valueOf(e);
    }

    private static String slice(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static String n(double v) {
        return JsNumber.toString(v);
    }

    // ── 스냅샷 ──────────────────────────────────────────────────────────────

    /** snapshot() 이식. 복원 단계의 선행 스냅샷 시도에서도 쓴다. */
    public Snap snapshot() throws Exception {
        double cash = net.clobCash();
        List<Object> positions = net.fetchPositions(null);
        double positionsValue = Js.round2(net.fetchPositionsValue());
        double equity = Js.round2(cash + positionsValue);
        List<Object> winRows = null;
        List<Object> lostRows = null;
        List<Object> closedRows = null;
        try {
            winRows = net.fetchPositions("REDEEMABLE");
            lostRows = net.fetchPositions("REDEEMABLE_LOST");
            closedRows = net.fetchPositions("CLOSED");
        } catch (Exception e) {
            winRows = null;
            lostRows = null;
            closedRows = null;
        }
        PositionBook.Book book = PositionBook.summarizeBook(positions, winRows, lostRows, closedRows,
                StopLossLedger.records(ledgerStore), attemptedObjects());
        lastClosedRows = closedRows;
        PositionBook.Sizing sizing = PositionBook.sizing(cash, equity, book.openCount);
        Snap snap = new Snap();
        snap.cash = cash;
        snap.positionsValue = positionsValue;
        snap.equity = equity;
        snap.openCount = book.openCount;
        snap.settledCount = book.settledCount;
        snap.settledWins = book.settledWins;
        snap.settledLosses = book.settledLosses;
        snap.settledStopLoss = book.settledStopLoss;
        snap.settledRealizedExcluded = book.settledRealizedExcluded;
        snap.settledRealizedPnl = book.settledRealizedPnl;
        snap.positionsPnlPct = book.positionsPnlPct;
        snap.heldSlugs = book.heldSlugs;
        snap.stopLossSlugs = book.stopLossSlugs;
        snap.cashFloor = sizing.cashFloor;
        snap.available = sizing.available;
        snap.bet = sizing.bet;
        snap.slots = (int) sizing.slots;
        lastSnapshot = snap;
        List<Object> open = new ArrayList<>();
        for (Object p : positions) {
            if ("OPEN".equals(Js.field(p, "status"))) {
                open.add(p);
            }
        }
        lastOpenRows = open;
        lastWinRows = winRows;
        lastLostRows = lostRows;
        snap.positions = positionSummaries(open);
        env.putSnapCache(snapCacheJson(snap));
        return snap;
    }

    /** 뷰어 종목 목록용 보유 행 요약 — 표시와 예상 수수료 계산에 필요한 필드만 추린다. */
    private List<Object> positionSummaries(List<Object> open) {
        List<Object> out = new ArrayList<>();
        for (Object p : open) {
            Map<String, Object> m = new LinkedHashMap<>();
            Object slug = Js.field(p, "slug");
            String slugStr = Js.truthy(slug) ? Js.str(slug) : null;
            m.put("slug", slugStr);
            Object token = Js.field(p, "token_id");
            m.put("token_id", Js.truthy(token) ? Js.str(token) : null);
            Object title = Js.field(p, "title");
            m.put("title", Js.truthy(title) ? Js.str(title) : slugStr);
            Object outcome = Js.field(p, "outcome");
            m.put("outcome", Js.truthy(outcome) ? Js.str(outcome) : null);
            m.put("avg_price", numOrNullFinite(Js.fieldNum(p, "avg_price")));
            m.put("current_price", numOrNullFinite(Js.fieldNum(p, "current_price")));
            m.put("current_size", numOrNullFinite(Js.fieldNum(p, "current_size")));
            m.put("current_value", numOrNullFinite(Js.fieldNum(p, "current_value")));
            m.put("fee_rate", slugStr == null ? null
                    : ManualSell.feeRateForFeeType(feeTypeBySlug.get(slugStr)));
            out.add(m);
        }
        return out;
    }

    private static Object numOrNullFinite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private Set<Object> attemptedObjects() {
        return new HashSet<>(StopLossLedger.attemptedTokens(ledgerStore));
    }

    private static Object numOrNull(Double v) {
        return v == null || !Double.isFinite(v) ? null : v;
    }

    private static Object numOrNull(Integer v) {
        return v == null ? null : (double) v;
    }

    /** snapshotCacheOf 이식 — 필드 순서·비유한 수 null 처리가 JS와 같다. */
    static String snapCacheJson(Snap snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cash", snap.cash);
        m.put("positionsValue", snap.positionsValue);
        m.put("equity", snap.equity);
        m.put("openCount", (double) snap.openCount);
        m.put("settledCount", numOrNull(snap.settledCount));
        m.put("settledWins", numOrNull(snap.settledWins));
        m.put("settledLosses", numOrNull(snap.settledLosses));
        m.put("settledStopLoss", numOrNull(snap.settledStopLoss));
        m.put("settledRealizedPnl", numOrNull(snap.settledRealizedPnl));
        m.put("positionsPnlPct", numOrNull(snap.positionsPnlPct));
        m.put("heldSlugs", new ArrayList<>(snap.heldSlugs));
        m.put("cashFloor", snap.cashFloor);
        m.put("available", snap.available);
        m.put("bet", snap.bet);
        m.put("slots", (double) snap.slots);
        m.put("positions", snap.positions);
        return ModelJson.stringify(m);
    }

    // ── 영속 상태 (bm_durable) ──────────────────────────────────────────────

    private List<Object> loadDurableJsonList(String key) {
        String raw = env.durableRead(key);
        if (raw == null) {
            return new ArrayList<>();
        }
        try {
            Object v = JsonText.parse(raw);
            if (v instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) v;
                return new ArrayList<>(list);
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    private Set<Object> boughtSlugsObjects() {
        Set<Object> out = new LinkedHashSet<>();
        for (Object v : loadDurableJsonList("bm_bought")) {
            out.add(v);
        }
        return out;
    }

    private Set<String> boughtSlugStrings() {
        Set<String> out = new LinkedHashSet<>();
        for (Object v : boughtSlugsObjects()) {
            if (v instanceof String) {
                out.add((String) v);
            }
        }
        return out;
    }

    private void markBought(String slug) {
        Set<Object> set = boughtSlugsObjects();
        set.add(slug);
        env.durableWrite("bm_bought", ModelJson.stringify(new ArrayList<>(set)));
        if (!boughtSlugsObjects().contains(slug)) {
            log("매수 기록 저장 확인 실패 " + slug + " — 저장소 쓰기가 반영되지 않았습니다");
        }
    }

    private void saveSettledBaseline() {
        if (settledBaseline == null) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", settledBaseline.count);
        m.put("realized", settledBaseline.realized);
        env.durableWrite("bm_settled_base", ModelJson.stringify(m));
    }

    // ── 손절 ────────────────────────────────────────────────────────────────

    private Object fetchGammaPageQuiet(String slug) {
        try {
            return net.gammaPageBySlug(slug);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean checkStopLossPosition(Map<String, Object> row, int gen) throws Exception {
        Object slugRaw = Js.field(row, "slug");
        String slug = slugRaw instanceof String ? (String) slugRaw : null;
        Object page1 = slug == null ? null : fetchGammaPageQuiet(slug);
        if (slug != null) {
            // 수수료 카테고리 캐시 — 뷰어 수동 매도의 예상 수수료 표시에 쓰인다.
            String feeType = ManualSell.feeTypeOfPage(page1);
            if (feeType != null) {
                feeTypeBySlug.put(slug, feeType);
            }
        }
        // JS는 1차 판정이 triggered일 때만 2차 조회를 한다 — 조회 횟수·시점을 맞춘다.
        boolean firstTriggered = false;
        StopLoss.GammaPrices p1 = StopLoss.parseGammaPrices(page1);
        if (p1 != null) {
            int idx = StopLoss.pickOutcomeIndex(p1.outcomes, row);
            double entry = Js.fieldNum(row, "avg_price");
            if (idx >= 0 && idx < p1.prices.length
                    && "triggered".equals(StopLoss.verdict(entry, p1.prices[idx]))) {
                firstTriggered = true;
            }
        }
        Object page2 = firstTriggered ? fetchGammaPageQuiet(slug) : null;
        StopLoss.Eval ev = StopLoss.evaluate(row, page1, page2);
        String slugStr = Js.truthy(slugRaw) ? Js.str(slugRaw) : "?";
        double entry = Js.fieldNum(row, "avg_price");
        switch (ev.action) {
            case "bad_entry":
                log("손절 판정 불가 " + slugStr + " — 진입가 이상 (" + n(entry) + ")");
                return false;
            case "no_index":
                log("손절 판정 불가 " + slugStr + " — 보유 쪽(outcome)을 가격 목록에서 찾지 못함");
                return false;
            case "recheck_dropped":
                log("손절 재검증 탈락 " + slugStr + " · 현재가 " + n(ev.current) + " (진입 " + n(entry) + ")");
                return false;
            case "triggered":
                return seller.executeStopLossSell(row, ev.current, gen);
            default:
                return false;
        }
    }

    /**
     * 뷰어 수동 매도 요청 처리 — 요청은 읽는 즉시 소비(키를 비움)하므로
     * 같은 요청이 다음 사이클에 다시 제출될 수 없다. 대상 행은 방금
     * 스냅샷의 보유 목록에서 토큰으로 찾고, 없거나 현재가를 모르면
     * 제출하지 않는다. 반환값은 매도 성공 건수(0 또는 1).
     */
    public int processManualSell(int gen) {
        String raw = env.durableRead(ManualSell.REQUEST_KEY);
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        env.durableWrite(ManualSell.REQUEST_KEY, "");
        Object model;
        try {
            model = JsonText.parse(raw);
        } catch (Exception e) {
            log("수동 매도 요청 형식 오류 — 버립니다");
            return 0;
        }
        ManualSell.Request req = ManualSell.parseRequest(model);
        if (req == null) {
            log("수동 매도 요청 형식 오류 — 버립니다");
            return 0;
        }
        String label = req.slug.isEmpty() ? req.tokenId : req.slug;
        if (ManualSell.isStale(req, env.nowMs())) {
            log("수동 매도 요청 만료 — 오래된 요청이라 버립니다 (" + label + ")");
            return 0;
        }
        Map<String, Object> row = null;
        for (Object o : lastOpenRows) {
            if (o instanceof Map && req.tokenId.equals(Js.field(o, "token_id"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                row = m;
                break;
            }
        }
        if (row == null) {
            log("수동 매도 건너뜀 — 보유 중인 종목이 아닙니다 (" + label + ")");
            return 0;
        }
        double current = Js.fieldNum(row, "current_price");
        if (!Double.isFinite(current) || current <= 0) {
            log("수동 매도 건너뜀 — 현재가를 확인할 수 없습니다 (" + label + ")");
            return 0;
        }
        return seller.executeManualSell(row, current, gen) ? 1 : 0;
    }

    /** runStopLoss 이식 — 보유 행마다 예외를 격리한다. 반환값은 매도 성공 건수. */
    public int runStopLoss(int gen) {
        int sold = 0;
        for (Object rowObj : lastOpenRows) {
            if (!env.isRunning(gen)) {
                break;
            }
            env.beatProgress(cycle);
            try {
                if (rowObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) rowObj;
                    if (checkStopLossPosition(row, gen)) {
                        sold++;
                    }
                }
            } catch (Exception e) {
                Object slugRaw = Js.field(rowObj, "slug");
                log("손절 처리 오류 " + (Js.truthy(slugRaw) ? Js.str(slugRaw) : "?") + ": " + slice(msg(e), 120));
            }
        }
        return sold;
    }

    // ── 리딤 ────────────────────────────────────────────────────────────────

    private void saveRedeemPending(List<Object> list) {
        List<Object> tail = list.size() > 300 ? new ArrayList<>(list.subList(list.size() - 300, list.size())) : list;
        env.durableWrite("bm_redeem_pending", ModelJson.stringify(tail));
    }

    private void recordRedeemHistory(Map<String, Object> rec) {
        List<Object> list = loadDurableJsonList("bm_redeem_history");
        list.add(rec);
        List<Object> tail = list.size() > 100 ? new ArrayList<>(list.subList(list.size() - 100, list.size())) : list;
        env.durableWrite("bm_redeem_history", ModelJson.stringify(tail));
    }

    private void removeRedeemPending(String cid) {
        List<Object> list = loadDurableJsonList("bm_redeem_pending");
        List<Object> kept = new ArrayList<>();
        for (Object e : list) {
            if (!cid.equals(Js.field(e, "condition_id"))) {
                kept.add(e);
            }
        }
        saveRedeemPending(kept);
    }

    private void closeWorthless(List<Object> ripe) {
        List<Object> closed = loadDurableJsonList("bm_redeem_closed");
        Set<Object> set = new LinkedHashSet<>(closed);
        Set<Object> ripeIds = new HashSet<>();
        for (Object e : ripe) {
            Object cid = Js.field(e, "condition_id");
            set.add(cid);
            ripeIds.add(cid);
            log("리딤 종결 (전액 손실) " + slice(Js.str(cid), 10) + "…");
        }
        List<Object> arr = new ArrayList<>(set);
        List<Object> tail = arr.size() > 500 ? new ArrayList<>(arr.subList(arr.size() - 500, arr.size())) : arr;
        env.durableWrite("bm_redeem_closed", ModelJson.stringify(tail));
        List<Object> pendingNow = loadDurableJsonList("bm_redeem_pending");
        List<Object> kept = new ArrayList<>();
        for (Object e : pendingNow) {
            if (!ripeIds.contains(Js.field(e, "condition_id"))) {
                kept.add(e);
            }
        }
        saveRedeemPending(kept);
    }

    private static final class RedeemOutcome {
        final boolean alreadyRedeemed;
        final String txHash;
        final String transactionId;

        private RedeemOutcome(boolean already, String txHash, String transactionId) {
            this.alreadyRedeemed = already;
            this.txHash = txHash;
            this.transactionId = transactionId;
        }

        static RedeemOutcome already() {
            return new RedeemOutcome(true, null, null);
        }

        static RedeemOutcome submitted(String txHash, String transactionId) {
            return new RedeemOutcome(false, txHash, transactionId);
        }
    }

    private RedeemOutcome submitRedeem(String conditionId, List<CallData> calls,
                                       RedeemSubmitter.RelayerCreds creds) throws Exception {
        RedeemSubmitter.SubmitResult res = redeemSubmitter.submitRedeemBatch(conditionId, calls, creds);
        return RedeemOutcome.submitted(res.txHash, res.transactionId);
    }

    /** executeRedeemTarget 이식. */
    private RedeemOutcome executeRedeemTarget(RedeemPlan.Target target,
                                              RedeemSubmitter.RelayerCreds creds) throws Exception {
        MarketContext ctx = net.fetchMarketContext(target.conditionId);
        if (ctx == null) {
            throw new Exception("마켓 컨텍스트 조회 실패");
        }
        if ("v2".equals(ctx.version)) {
            if (ctx.positionIds == null || ctx.positionIds.size() < 2) {
                throw new Exception("v2 포지션 ID 없음");
            }
            List<BigInteger> balances = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                balances.add(net.erc1155Balance(POSITION_MANAGER, funder, ctx.positionIds.get(i)));
            }
            if ("already".equals(RedeemPlan.redeemBalanceGate(balances))) {
                return RedeemOutcome.already();
            }
            List<CallData> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                if (balances.get(i).signum() > 0) {
                    calls.add(Redeem.v2Redeem(target.conditionId, BigInteger.valueOf(i), balances.get(i)));
                }
            }
            return submitRedeem(target.conditionId, calls, creds);
        }
        if (ctx.tokenIds != null) {
            List<BigInteger> balances = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                balances.add(net.erc1155Balance(CTF_CONTRACT, funder, ctx.tokenIds.get(i)));
            }
            if ("already".equals(RedeemPlan.redeemBalanceGate(balances))) {
                return RedeemOutcome.already();
            }
        }
        List<CallData> calls = new ArrayList<>();
        calls.add(Redeem.ctfRedeem(Redeem.PUSD, new byte[32], Hex.hexToBytes(target.conditionId),
                new BigInteger[]{BigInteger.ONE, BigInteger.valueOf(2)}, ctx.negRisk));
        return submitRedeem(target.conditionId, calls, creds);
    }

    private String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(env.nowMs()));
    }

    private String utcDay() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(env.nowMs()));
    }

    /** runRedeems 이식 — 회수액 합계(달러)를 돌려준다. */
    public double runRedeems(int gen) throws Exception {
        List<Object> pending = loadDurableJsonList("bm_redeem_pending");
        List<Object> history = loadDurableJsonList("bm_redeem_history");
        List<Object> closedList = loadDurableJsonList("bm_redeem_closed");
        Set<Object> done = new HashSet<>();
        for (Object h : history) {
            done.add(Js.field(h, "condition_id"));
        }
        done.addAll(closedList);
        RedeemPlan.Ingest ingest = RedeemPlan.ingest(pending, done, lastWinRows, lastLostRows, lastClosedRows);
        if (ingest.changed) {
            saveRedeemPending(ingest.pending);
            pending = ingest.pending;
        }
        RedeemPlan.Plan plan = RedeemPlan.planRedeems(pending);
        if (!plan.errors.isEmpty()) {
            List<Object> kept = new ArrayList<>();
            for (Object e : pending) {
                if (RedeemPlan.normalizeConditionId(Js.field(e, "condition_id")) != null) {
                    kept.add(e);
                }
            }
            saveRedeemPending(kept);
            pending = kept;
            log("리딤 계획 제외 " + plan.errors.size() + "건 — condition_id 형식 오류로 대기 목록에서 제거");
        }
        if (!plan.worthless.isEmpty()) {
            Map<Object, Map<String, Object>> byCond = new LinkedHashMap<>();
            for (Object e : pending) {
                if (e instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) e;
                    byCond.put(Js.field(entry, "condition_id"), entry);
                }
            }
            Set<String> worthlessConds = new LinkedHashSet<>();
            for (Object e : plan.worthless) {
                worthlessConds.add(Js.str(Js.field(e, "condition_id")));
            }
            boolean streakChanged = false;
            List<Object> ripe = new ArrayList<>();
            for (String cid : worthlessConds) {
                Map<String, Object> entry = null;
                for (Object e : pending) {
                    if (cid.equals(Js.field(e, "condition_id"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) e;
                        entry = m;
                        break;
                    }
                }
                if (entry == null) {
                    continue;
                }
                double streak = Js.numOrZero(Js.field(entry, "zero_streak")) + 1;
                entry.put("zero_streak", streak);
                streakChanged = true;
                if (streak >= 3) {
                    for (Object e : plan.worthless) {
                        if (cid.equals(Js.field(e, "condition_id"))) {
                            ripe.add(e);
                        }
                    }
                }
            }
            for (RedeemPlan.Target t : plan.targets) {
                Map<String, Object> entry = byCond.get(t.conditionId);
                if (entry != null && Js.truthy(Js.field(entry, "zero_streak"))) {
                    entry.put("zero_streak", 0.0);
                    streakChanged = true;
                }
            }
            if (streakChanged) {
                saveRedeemPending(pending);
            }
            if (!ripe.isEmpty()) {
                closeWorthless(ripe);
            }
        }
        if (plan.targets.isEmpty()) {
            return 0;
        }
        RedeemSubmitter.RelayerCreds creds = relayerCreds.get();
        if (creds == null) {
            String today = utcDay();
            if (!today.equals(redeemKeyWarnedDay)) {
                redeemKeyWarnedDay = today;
                double held = 0;
                for (RedeemPlan.Target t : plan.targets) {
                    held += t.expectedUsd;
                }
                log("리딤 대기 " + plan.targets.size() + "건 (약 $" + n(Js.round2(held))
                        + ") — Relayer API 키가 없어 제출을 보류합니다 (키 등록 후 자동 실행)");
            }
            return 0;
        }
        double recoveredTotal = 0;
        for (RedeemPlan.Target target : plan.targets) {
            if (!env.isRunning(gen)) {
                break;
            }
            String cid = target.conditionId;
            String lockKey = "redeem:" + cid;
            if (!guard.tryAcquireSubmitLock(lockKey, gen)) {
                continue;
            }
            if (!guard.beginSubmit(gen)) {
                guard.releaseSubmitLock(lockKey);
                continue;
            }
            try {
                double before = net.clobCash();
                RedeemOutcome res = executeRedeemTarget(target, creds);
                if (res.alreadyRedeemed) {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("ts", isoNow());
                    rec.put("condition_id", cid);
                    rec.put("expected_usd", target.expectedUsd);
                    rec.put("recovered_usd", 0.0);
                    rec.put("tx_hash", null);
                    rec.put("transaction_id", null);
                    rec.put("verify_status", "already_redeemed");
                    recordRedeemHistory(rec);
                    removeRedeemPending(cid);
                    log("리딤 종결 " + slice(cid, 10) + "… · 이미 상환됨 (온체인 잔고 0 확인)");
                    continue;
                }
                Double recovered = null;
                String verifyStatus;
                try {
                    RedeemPlan.Recovery ver = RedeemPlan.verifyRecovery(before, net.clobCash(), target.expectedUsd);
                    recovered = ver.deltaUsd;
                    verifyStatus = ver.status;
                } catch (Exception e) {
                    verifyStatus = "submitted_unverified";
                    log("리딤 제출 후 잔고 측정 실패 " + slice(cid, 10) + "…: " + slice(msg(e), 120));
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("ts", isoNow());
                rec.put("condition_id", cid);
                rec.put("expected_usd", target.expectedUsd);
                rec.put("recovered_usd", recovered);
                rec.put("tx_hash", res.txHash);
                rec.put("transaction_id", res.transactionId);
                rec.put("verify_status", verifyStatus);
                recordRedeemHistory(rec);
                removeRedeemPending(cid);
                if (recovered != null && recovered != 0) {
                    recoveredTotal = Js.round2(recoveredTotal + recovered);
                }
                log("리딤 회수 " + slice(cid, 10) + "… · 예상 $" + n(target.expectedUsd) + " · 회수 "
                        + (recovered == null ? "미확인" : "$" + n(recovered)) + " · " + verifyStatus);
            } catch (Exception e) {
                log("리딤 실패 " + slice(cid, 10) + "…: " + slice(msg(e), 140) + " — 다음 사이클에 재시도");
            } finally {
                guard.endSubmit();
                guard.releaseSubmitLock(lockKey);
            }
        }
        return recoveredTotal;
    }

    // ── 기매수 정화 ─────────────────────────────────────────────────────────

    private void reconcileBoughtOnce(Snap snap) {
        BoughtReconcile.BuySlugs fetched;
        try {
            fetched = net.fetchBuySlugs();
        } catch (Exception e) {
            if (!reconcileFailLogged) {
                reconcileFailLogged = true;
                log("기매수 정화 실패 — 매수 이력 조회 오류: " + slice(msg(e), 100) + " (다음 사이클 재시도)");
            }
            return;
        }
        Set<Object> cur = boughtSlugsObjects();
        if (!fetched.complete || (cur.size() > 0 && fetched.slugs.isEmpty())) {
            if (!reconcileFailLogged) {
                reconcileFailLogged = true;
                log("기매수 정화 보류 — 매수 이력이 불완전하거나 비어 있어 기록을 지우지 않습니다 (다음 사이클 재시도)");
            }
            return;
        }
        boughtReconciled = true;
        if (cur.isEmpty()) {
            return;
        }
        Set<Object> held = new HashSet<>(snap.heldSlugs);
        BoughtReconcile.Reconcile rec = BoughtReconcile.reconcileBought(cur, fetched.slugs, held);
        if (rec.dropped.isEmpty()) {
            return;
        }
        env.durableWrite("bm_bought", ModelJson.stringify(rec.kept));
        StringBuilder joined = new StringBuilder();
        int show = Math.min(5, rec.dropped.size());
        for (int i = 0; i < show; i++) {
            if (i > 0) {
                joined.append(", ");
            }
            joined.append(Js.str(rec.dropped.get(i)));
        }
        log("기매수 기록 정화 — 온체인 매수 이력 없는 " + rec.dropped.size() + "건 제거: " + joined
                + (rec.dropped.size() > 5 ? " 외" : ""));
    }

    // ── 사이클 요약 ─────────────────────────────────────────────────────────

    private static final Map<String, String> SCAN_DROP_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> REJECT_LABELS = new LinkedHashMap<>();

    static {
        // engine.js SCAN_DROP_LABELS 원문
        SCAN_DROP_LABELS.put("inactive", "비활성");
        SCAN_DROP_LABELS.put("not_accepting", "주문중단");
        SCAN_DROP_LABELS.put("short_term", "단기코인");
        SCAN_DROP_LABELS.put("sports", "스포츠");
        SCAN_DROP_LABELS.put("owned", "보유·기매수");
        SCAN_DROP_LABELS.put("end_out", "만기밖");
        SCAN_DROP_LABELS.put("volume_low", "거래량미달");
        SCAN_DROP_LABELS.put("price_bad", "가격형식");
        SCAN_DROP_LABELS.put("band_out", "밴드밖");

        // engine.js REJECT_LABELS 원문
        REJECT_LABELS.put("sports", "스포츠");
        REJECT_LABELS.put("spread_too_wide", "스프레드");
        REJECT_LABELS.put("ask_out_of_band", "밴드이탈");
        REJECT_LABELS.put("snapshot_mismatch", "가격변동");
        REJECT_LABELS.put("no_asks", "매도호가없음");
        REJECT_LABELS.put("no_bids", "매수호가없음");
        REJECT_LABELS.put("ask_depth_low", "호가깊이부족");
        REJECT_LABELS.put("below_min_order_size", "최소수량미달");
        REJECT_LABELS.put("gamma_market_missing", "마켓없음");
        REJECT_LABELS.put("market_not_accepting", "주문불가");
        REJECT_LABELS.put("gamma_parse_error", "응답파싱오류");
        REJECT_LABELS.put("gamma_shape_error", "응답형식오류");
        REJECT_LABELS.put("verify_error", "검증오류");
        REJECT_LABELS.put("side_flipped", "우세뒤집힘");
        REJECT_LABELS.put("ask_above_signal", "신호가초과");
        REJECT_LABELS.put("market_inactive", "마켓비활성");
        REJECT_LABELS.put("end_out_of_range", "만기범위이탈");
    }

    private static String labelOf(Map<String, String> labels, String key) {
        String v = labels.get(key);
        return v != null ? v : key;
    }

    private static String tallyText(Map<String, Integer> map, Map<String, String> labels, boolean excludeTotal) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (excludeTotal && "total".equals(e.getKey())) {
                continue;
            }
            if (e.getValue() != null && e.getValue() > 0) {
                entries.add(e);
            }
        }
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append("·");
            }
            sb.append(labelOf(labels, entries.get(i).getKey())).append(" ").append(entries.get(i).getValue());
        }
        return sb.toString();
    }

    private static void tally(Map<String, Integer> map, String key) {
        map.put(key, map.getOrDefault(key, 0) + 1);
    }

    /** summarizeCycle 이식. */
    public static String summarizeCycle(int candidateCount, int verified, Map<String, Integer> rejects,
                                 int filled, int unfilled, int failed, Map<String, Integer> scan) {
        StringBuilder sb = new StringBuilder();
        sb.append("사이클 요약 · 후보 ").append(candidateCount).append(" · 검증 ").append(verified);
        if (scan != null) {
            Integer total = scan.get("total");
            sb.append(" · 조회 ").append(total == null ? 0 : total);
            String dropText = tallyText(scan, SCAN_DROP_LABELS, true);
            if (!dropText.isEmpty()) {
                sb.append(" (").append(dropText).append(")");
            }
        }
        String rejectText = rejects == null ? "" : tallyText(rejects, REJECT_LABELS, false);
        if (!rejectText.isEmpty()) {
            sb.append(" · 탈락 ").append(rejectText);
        }
        sb.append(" · 제출 ").append(filled);
        if (unfilled > 0) {
            sb.append(" · 미체결 ").append(unfilled);
        }
        if (failed > 0) {
            sb.append(" · 실패 ").append(failed);
        }
        return sb.toString();
    }

    // ── 업데이트 확인 ───────────────────────────────────────────────────────

    /** checkForUpdate 이식 — 모든 오류를 삼킨다 (JS와 같다). */
    public void checkForUpdate() {
        try {
            String version = env.appVersion();
            if (version == null) {
                return;
            }
            Object json = net.fetchLatestReleaseJson();
            NativeUpdate.Release rel = NativeUpdate.parseLatestRelease(json);
            if (rel == null) {
                return;
            }
            Integer cmp = NativeUpdate.compareVersions(rel.tag, version);
            if (cmp == null || cmp != 1) {
                return;
            }
            env.saveUpdateInfo(rel.toJson());
            if (!rel.tag.equals(updateNotifiedTag)) {
                updateNotifiedTag = rel.tag;
                log("새 버전 " + rel.tag + " 사용 가능 — 업데이트 버튼으로 설치하세요");
            }
        } catch (Exception ignored) {
        }
    }

    // ── runCycle ────────────────────────────────────────────────────────────

    /** runCycle 이식. 예외는 호출자(runIteration)가 "사이클 오류"로 기록한다. */
    public void runCycle(int gen) throws Exception {
        env.setStage("snapshot", cycle);
        Snap snap;
        try {
            snap = snapshot();
        } catch (Exception e) {
            try {
                List<Object> rows = net.fetchPositions(null);
                List<Object> open = new ArrayList<>();
                for (Object p : rows) {
                    if ("OPEN".equals(Js.field(p, "status"))) {
                        open.add(p);
                    }
                }
                lastOpenRows = open;
                env.setStage("stoploss", cycle);
                runStopLoss(gen);
                log("스냅샷 실패로 손절만 단독 실행함 — 원래 오류: " + slice(msg(e), 100));
            } catch (Exception e2) {
                log("스냅샷 실패 + 손절 단독 실행도 실패: " + slice(msg(e2), 100));
            }
            throw e;
        }
        log("사이클 " + cycle + " · 잔고 $" + n(snap.cash) + " · 평가 $" + n(snap.equity)
                + " · 건당 $" + n(snap.bet) + " · 슬롯 " + snap.slots + " · 보유 " + snap.openCount);
        int manualSold = processManualSell(gen);
        if (manualSold > 0) {
            snap = snapshot();
        }
        if (!settledBaselineLoaded) {
            settledBaseline = SettledBaseline.parse(env.durableRead("bm_settled_base"));
            settledBaselineLoaded = true;
        }
        Map<String, Object> snapModel = new LinkedHashMap<>();
        snapModel.put("settledCount", numOrNull(snap.settledCount));
        snapModel.put("settledRealizedPnl", numOrNull(snap.settledRealizedPnl));
        SettledBaseline.Notice settleNotice = SettledBaseline.noticeFor(settledBaseline, snapModel);
        env.beatProgress(cycle);
        env.setStage("stoploss", cycle);
        int sold = runStopLoss(gen);
        env.beatProgress(cycle);
        if (sold > 0) {
            snap = snapshot();
        }
        env.setStage("redeem", cycle);
        double recovered = runRedeems(gen);
        env.beatProgress(cycle);
        if (recovered > 0) {
            snap = snapshot();
        }
        if (settleNotice != null) {
            env.notifyMoney("정산 확정", "실현 " + com.roomedia.boringmech.write.StopLoss.signedUsd(settleNotice.realizedDelta)
                    + (recovered > 0 ? " · 회수 $" + n(recovered) : ""));
        }
        if (snap.settledCount != null) {
            settledBaseline = new SettledBaseline.Baseline(snap.settledCount.doubleValue(), snap.settledRealizedPnl);
            saveSettledBaseline();
        }
        if (sessionFills > 0 && !posGapWarned) {
            int found = snap.openCount + (snap.settledCount == null ? 0 : snap.settledCount);
            if (found < sessionFills) {
                posGapWarned = true;
                log("보유 대조 경고: 체결 기록 " + sessionFills + "건인데 포지션은 " + found + "건만 확인됨");
            }
        }
        if (snap.slots < 1) {
            log("슬롯 없음 — 이번 사이클 매수 없음");
            return;
        }
        if (BoughtReconcile.boughtCorrupted(env.durableRead("bm_bought"))) {
            log("기매수 기록 손상 감지 — 이번 사이클 매수를 건너뜁니다 (재매수 방벽 보호)");
            return;
        }
        if (!boughtReconciled) {
            reconcileBoughtOnce(snap);
        }
        env.setStage("scan", cycle);
        runScanAndBuy(gen, snap);
    }

    private void runScanAndBuy(int gen, Snap snap) throws Exception {
        List<Object> markets = net.fetchMarkets();
        Map<String, Integer> scanStats = new LinkedHashMap<>();
        Set<String> boughtAll = boughtSlugStrings();
        boughtAll.addAll(snap.stopLossSlugs);
        List<ScanFilter.Candidate> candidates = ScanFilter.scanCandidates(
                markets, snap.heldSlugs, boughtAll, scanStats, env.nowMs());
        int slotsLeft = snap.slots;
        int verified = 0;
        Map<String, Integer> rejects = new LinkedHashMap<>();
        int filledCount = 0;
        int unfilledCount = 0;
        int failedCount = 0;
        for (ScanFilter.Candidate cand : candidates) {
            if (!env.isRunning(gen)) {
                return;
            }
            if (slotsLeft < 1 || verified >= VERIFY_CAP) {
                break;
            }
            env.beatProgress(cycle);
            verified++;
            CandidateVerifier.Result detail;
            try {
                detail = CandidateVerifier.verifyCandidate(cand, snap.bet, gammaSource, bookSource, env.nowMs());
            } catch (Exception e) {
                tally(rejects, "verify_error");
                log("검증 오류 " + cand.slug + ": " + msg(e));
                continue;
            }
            if (!detail.ok) {
                tally(rejects, detail.reason);
                continue;
            }
            if (!env.isRunning(gen)) {
                return;
            }
            if (!guard.tryAcquireSubmitLock(cand.slug, gen)) {
                log("제출 잠금 — 중복 제출 차단 " + cand.slug);
                continue;
            }
            try {
                if (boughtSlugStrings().contains(cand.slug)) {
                    log("제출 직전 재확인 — 이미 매수 기록 있음 " + cand.slug);
                    continue;
                }
                if (!guard.beginSubmit(gen)) {
                    log("세대 교체 — 제출 중단 " + cand.slug);
                    continue;
                }
                try {
                    SignedOrder signed;
                    try {
                        ClobWriteClient.MarketOrderRequest req =
                                new ClobWriteClient.MarketOrderRequest(detail.tokenId, true, snap.bet, detail.limitPrice);
                        req.tickSize = detail.tickSize;
                        req.negRisk = detail.negRisk;
                        signed = client.createMarketOrder(req);
                    } catch (Exception e) {
                        failedCount++;
                        log("주문 서명 실패 " + cand.slug + ": " + slice(msg(e), 120));
                        continue;
                    }
                    if (!env.isRunning(gen)) {
                        return;
                    }
                    Object resp;
                    try {
                        resp = client.postOrder(signed, "FOK");
                    } catch (Exception e) {
                        failedCount++;
                        markBought(cand.slug);
                        slotsLeft--;
                        String m = msg(e);
                        log("주문 결과 불명 " + cand.slug + ": " + slice(m, 120) + " — 중복 방지 기록");
                        if (com.roomedia.boringmech.engine.FillJudge.isFailsafe(m)) {
                            env.requestStop();
                            log("failsafe 감지 — 엔진 정지. 정지 사유를 확인한 뒤 다시 실행하세요.");
                            return;
                        }
                        continue;
                    }
                    JudgeFill.Verdict verdict = JudgeFill.judgeFill(resp);
                    if (!verdict.filled && "unknown".equals(verdict.kind)) {
                        failedCount++;
                        markBought(cand.slug);
                        slotsLeft--;
                        log("주문 결과 불명 " + cand.slug + ": " + slice(verdict.error == null ? "" : verdict.error, 120)
                                + " — 중복 방지 기록");
                        continue;
                    }
                    if (!verdict.filled && !"unfilled".equals(verdict.kind)) {
                        failedCount++;
                        String m = verdict.error != null ? verdict.error : "주문 거부 (success=false)";
                        log("주문 실패 " + cand.slug + ": " + slice(m, 120)
                                + " · 응답 원문: " + slice(ModelJson.stringify(resp), 200));
                        if (com.roomedia.boringmech.engine.FillJudge.isFailsafe(m)
                                || com.roomedia.boringmech.engine.FillJudge.isFailsafe(verdict.status)) {
                            env.requestStop();
                            log("failsafe 감지 — 엔진 정지. 정지 사유를 확인한 뒤 다시 실행하세요.");
                            return;
                        }
                        continue;
                    }
                    if (!verdict.filled) {
                        unfilledCount++;
                        if ("delayed".equals(verdict.status)) {
                            markBought(cand.slug);
                            slotsLeft--;
                            log("미체결(지연 접수) " + cand.slug + " — 뒤늦은 체결에 대비해 기록하고 슬롯을 소모합니다");
                        } else {
                            Object st = Js.field(resp, "status");
                            String statusText = (st == Js.UNDEFINED || st == null) ? "(없음)" : Js.str(st);
                            log("미체결 " + cand.slug + " · 응답 status: " + statusText
                                    + " · 응답 원문: " + slice(ModelJson.stringify(resp), 200));
                        }
                        continue;
                    }
                    markBought(cand.slug);
                    sessionFills++;
                    filledCount++;
                    slotsLeft--;
                    Object making = Js.field(resp, "makingAmount");
                    Object taking = Js.field(resp, "takingAmount");
                    boolean hasFillPrice = Js.truthy(making) && Js.truthy(taking);
                    double fillPrice = hasFillPrice ? Js.round2(Js.num(making) / Js.num(taking)) : 0;
                    String fill = hasFillPrice ? " 체결가 " + n(fillPrice) : "";
                    Object respStatus = Js.field(resp, "status");
                    log("매수 " + cand.slug + " · $" + n(snap.bet) + " @ " + n(detail.limitPrice) + fill
                            + " · 상태 " + (Js.truthy(respStatus) ? Js.str(respStatus) : "matched"));
                    env.notifyMoney("매수 체결", "건당 $" + n(snap.bet) + " · 체결가 "
                            + n(hasFillPrice ? fillPrice : detail.limitPrice));
                } finally {
                    guard.endSubmit();
                }
            } finally {
                guard.releaseSubmitLock(cand.slug);
            }
        }
        log(summarizeCycle(candidates.size(), verified, rejects, filledCount, unfilledCount, failedCount, scanStats));
    }

    // ── 루프 1회전 (loop() 이식 — 스케줄링과 재시작 실행은 드라이버 몫) ──────

    /** shouldSelfReload 이식. */
    public static boolean shouldSelfReload(int errors, long lastReloadMs, long nowMs) {
        return errors >= ERROR_SELF_RESTART_THRESHOLD
                && nowMs - lastReloadMs >= SELF_RESTART_COOLDOWN_MS;
    }

    public Result runIteration(int gen) {
        cycle++;
        env.beatProgress(cycle);
        boolean failed = false;
        try {
            runCycle(gen);
        } catch (Exception e) {
            failed = true;
            log("사이클 오류: " + slice(msg(e), 160));
        }
        if (gen != env.generation() || !env.isRunning(gen)) {
            return Result.ABORTED;
        }
        if (failed) {
            cycleErrors++;
            if (shouldSelfReload(cycleErrors, env.lastSelfReloadMs(), env.nowMs())) {
                env.saveSelfReloadMs(env.nowMs());
                log("사이클 오류 " + cycleErrors + "회 연속 — 페이지 자가 재로드로 복구를 시도합니다");
                return Result.RESTART;
            }
        } else {
            cycleErrors = 0;
        }
        env.setStage("idle", cycle);
        if (cycle % UPDATE_CHECK_EVERY_CYCLES == 0) {
            checkForUpdate();
        }
        return failed ? Result.FAILED : Result.OK;
    }
}
