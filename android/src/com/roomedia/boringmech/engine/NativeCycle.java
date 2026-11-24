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

        /** 마지막으로 저장된 스냅샷 캐시 JSON (없으면 null) — 손절 폴백의 장부 재료. */
        String getSnapCache();

        void notifyMoney(String title, String body);

        /** 오류 알림 (오류 채널) — key는 NotifyPolicy dedupe 사유 키. */
        void notifyError(String key, String title, String body);

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

        /**
         * Gamma 기온 이벤트를 슬러그로 조회한다 — 이벤트 모델 목록. 도시별
         * 실패는 구현이 건너뛰고, 전 도시 실패면 null을 돌려준다 (호출자는
         * 캐시하지 않는다 — JS fetchTempEvents의 전수 실패 미캐시와 같다).
         */
        List<Object> fetchTempEvents(List<String> eventSlugs) throws Exception;

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

    // 단계별 소요 (밀리초) — 사이클 요약에만 붙는 측정값이다. 단계 진입
    // 기록(setStage·StageDeadline 판정)은 건드리지 않는다. verify·order는
    // 스캔 단계 안의 누적, scan은 스캔 전체 경과다.
    private long stageMsSnapshot;
    private long stageMsStopLoss;
    private long stageMsScan;
    private long stageMsVerify;
    private long stageMsOrder;
    private long scanStartMs;
    /** slug → Gamma feeType 캐시 — 손절 판정의 Gamma 조회에서 채워지고 스냅샷의 수수료율 표시에 쓰인다. */
    private final Map<String, String> feeTypeBySlug = new HashMap<>();

    /** 기온 이벤트 조회 캐시 — 사이클마다 도시 전수를 호출하지 않게 15분 유지한다. 기준 UTC 날짜가 바뀌면 무효화한다. */
    private List<Object> tempEventsCache;
    private long tempEventsCacheAtMs;
    private String tempEventsCacheDayKey;

    private List<Object> lastOpenRows = new ArrayList<>();
    private List<Object> lastWinRows;
    private List<Object> lastLostRows;
    private List<Object> lastClosedRows;
    // 정산 표시 마지막 성공값 (engine.js state.lastSettledApi 대응).
    private boolean hasLastSettledApi = false;
    private Integer lastSettledCount;
    private Integer lastSettledWins;
    private Integer lastSettledLosses;
    private Integer lastSettledStopLoss;
    private Integer lastSettledRealizedExcluded;
    private Double lastSettledRealizedPnl;
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
                guard, ledgerStore, env::log, this::moneyNotify, timeoutRunner, env::nowMs,
                new StopLossSeller.ManualSellRecorder() {
                    @Override
                    public void record(Map<String, Object> rec) {
                        ManualSell.CappedLists r = ManualSell.appendRecordWithArchive(
                                loadDurableJsonList(ManualSell.ARCHIVE_KEY),
                                loadDurableJsonList(ManualSell.LEDGER_KEY), rec);
                        env.durableWrite(ManualSell.LEDGER_KEY,
                                ModelJson.stringify(r.recent));
                        env.durableWrite(ManualSell.ARCHIVE_KEY,
                                ModelJson.stringify(r.archive));
                    }

                    @Override
                    public void recordAttempt(String tokenId) {
                        ManualSell.CappedLists r = ManualSell.appendAttemptedWithArchive(
                                loadDurableJsonList(ManualSell.ATTEMPTED_ARCHIVE_KEY),
                                loadDurableJsonList(ManualSell.ATTEMPTED_KEY), tokenId);
                        env.durableWrite(ManualSell.ATTEMPTED_KEY,
                                ModelJson.stringify(r.recent));
                        env.durableWrite(ManualSell.ATTEMPTED_ARCHIVE_KEY,
                                ModelJson.stringify(r.archive));
                    }
                });
        this.seller.setErrorNotifier(this::errorNotify);
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

    /**
     * 오류 알림 발송 — 알림 경로는 거래 판정에 절대 닿지 않는다.
     * 어댑터가 삼키는 것이 규약이지만, 여기서도 한 번 더 감싸 어떤
     * 어댑터 결함도 사이클 흐름을 바꾸지 못하게 한다.
     */
    /** 체결 알림 래퍼 — 알림 예외가 체결 판정·원장 기록을 뒤집지 않게 삼킨다. */
    private void moneyNotify(String title, String body) {
        try {
            env.notifyMoney(title, body);
        } catch (Exception ignored) {
            // 알림 실패는 거래 로직에 영향을 주지 않는다.
        }
    }

    private void errorNotify(String key, String title, String body) {
        try {
            env.notifyError(key, title, body);
        } catch (Exception ignored) {
        }
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

    /**
     * snapshot() 이식. 복원 단계의 선행 스냅샷 시도에서도 쓴다.
     * 사이클 밖 진입점이라 여기서 설정을 새로 파싱한다 — 사이클 안에서는
     * runCycle이 파싱한 설정을 snapshot(TradeSettings)로 넘긴다
     * (사이클당 설정 1회 파싱, v0.3.38 감사).
     */
    public Snap snapshot() throws Exception {
        return snapshot(TradeSettings.parse(env.durableRead(TempNo.SETTINGS_KEY)));
    }

    /** snapshot 본체 — 사이징은 넘겨받은 설정으로만 계산한다. */
    public Snap snapshot(TradeSettings tradeCfg) throws Exception {
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
        } catch (Exception e) {
            winRows = null;
            lostRows = null;
        }
        try {
            closedRows = net.fetchPositions("CLOSED");
        } catch (Exception e) {
            closedRows = null;
        }
        PositionBook.Book book = PositionBook.summarizeBook(positions, closedRows,
                StopLossLedger.records(ledgerStore));
        lastClosedRows = closedRows;
        // 정산 표시 폴백 — engine.js snapshot()과 동일. CLOSED 조회 실패
        // 사이클은 마지막 성공 API 표시값을 유지하고, 내부 계산값으로
        // 대체하지 않는다.
        Integer settledCount = book.settledCount;
        Integer settledWins = book.settledWins;
        Integer settledLosses = book.settledLosses;
        Integer settledStopLoss = book.settledStopLoss;
        Integer settledRealizedExcluded = book.settledRealizedExcluded;
        Double settledRealizedPnl = book.settledRealizedPnl;
        if (closedRows != null) {
            hasLastSettledApi = true;
            lastSettledCount = settledCount;
            lastSettledWins = settledWins;
            lastSettledLosses = settledLosses;
            lastSettledStopLoss = settledStopLoss;
            lastSettledRealizedExcluded = settledRealizedExcluded;
            lastSettledRealizedPnl = settledRealizedPnl;
        } else if (hasLastSettledApi) {
            settledCount = lastSettledCount;
            settledWins = lastSettledWins;
            settledLosses = lastSettledLosses;
            settledStopLoss = lastSettledStopLoss;
            settledRealizedExcluded = lastSettledRealizedExcluded;
            settledRealizedPnl = lastSettledRealizedPnl;
        }
        PositionBook.Sizing sizing = PositionBook.sizing(cash, equity, book.openCount, tradeCfg);
        Snap snap = new Snap();
        snap.cash = cash;
        snap.positionsValue = positionsValue;
        snap.equity = equity;
        snap.openCount = book.openCount;
        snap.settledCount = settledCount;
        snap.settledWins = settledWins;
        snap.settledLosses = settledLosses;
        snap.settledStopLoss = settledStopLoss;
        snap.settledRealizedExcluded = settledRealizedExcluded;
        snap.settledRealizedPnl = settledRealizedPnl;
        snap.positionsPnlPct = book.positionsPnlPct;
        snap.heldSlugs = book.heldSlugs;
        snap.stopLossSlugs = book.stopLossSlugs;
        snap.cashFloor = sizing.cashFloor;
        snap.available = sizing.available;
        snap.bet = sizing.bet;
        snap.slots = (int) sizing.slots;
        lastSnapshot = snap;
        // open 판정은 PositionBook 단일 기준 — 장부 openCount와 종목 목록이
        // 어긋나지 않게 한다 (status 없는 실기기 행 누락 방지).
        List<Object> open = PositionBook.openRows(positions);
        lastOpenRows = open;
        // 장부를 다시 읽은 사이클 — 폴백 무효 집합은 역할을 다했다
        if (!StopLossFallback.parseDead(env.durableRead(StopLossFallback.DEAD_KEY)).isEmpty()) {
            env.durableWrite(StopLossFallback.DEAD_KEY, "[]");
        }
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
            Object endDate = Js.field(p, "end_date");
            m.put("end_date", Js.truthy(endDate) ? Js.str(endDate) : null);
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
            // 문자열 항목과 전략 태그 객체 항목 양쪽에서 slug를 뽑는다.
            String s = BoughtReconcile.slugOfEntry(v);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private void markBought(String slug, String strategy) {
        // 같은 slug의 기존 항목은 지우고 한 건만 남긴다. 기온 매수는 전략
        // 태그를 단 객체로, 메인 매수는 기존 문자열 형태로 기록한다.
        List<Object> entries = new ArrayList<>();
        for (Object v : boughtSlugsObjects()) {
            if (!slug.equals(BoughtReconcile.slugOfEntry(v))) {
                entries.add(v);
            }
        }
        if (TempNo.STRATEGY.equals(strategy)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("slug", slug);
            e.put("strategy", TempNo.STRATEGY);
            entries.add(e);
        } else {
            entries.add(slug);
        }
        env.durableWrite("bm_bought", ModelJson.stringify(entries));
        if (!boughtSlugStrings().contains(slug)) {
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

    private boolean checkStopLossPosition(Map<String, Object> row, int gen, TradeSettings tradeCfg) throws Exception {
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
        // 기온 No 페이드는 메인 폭(-0.25)이 아닌 기온 전용 폭(-0.15)으로
        // 손절한다 — 단, 기온 폭은 매수 기록에 temp_no 태그가 있는 건만
        // (메인 매수·기록 없음은 메인 폭 적용). 판정 폭은 evaluate에도
        // 같은 기준으로 들어간다.
        boolean tempTagged = slug != null
                && TempNo.isTempTagged(loadDurableJsonList("bm_bought"), slug);
        double stopDrop = tempTagged ? tradeCfg.tempStopDrop : tradeCfg.stopDrop;
        // JS는 1차 판정이 triggered일 때만 2차 조회를 한다 — 조회 횟수·시점을 맞춘다.
        boolean firstTriggered = false;
        StopLoss.GammaPrices p1 = StopLoss.parseGammaPrices(page1);
        if (p1 != null) {
            int idx = StopLoss.pickOutcomeIndex(p1.outcomes, row);
            double entry = Js.fieldNum(row, "avg_price");
            if (idx >= 0 && idx < p1.prices.length
                    && "triggered".equals(StopLoss.verdict(entry, p1.prices[idx], stopDrop,
                            tradeCfg.decidedHigh, tradeCfg.decidedLow))) {
                firstTriggered = true;
            }
        }
        Object page2 = firstTriggered ? fetchGammaPageQuiet(slug) : null;
        StopLoss.Eval ev = StopLoss.evaluate(row, page1, page2, tempTagged, tradeCfg);
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
                return seller.executeStopLossSell(row, ev.current, gen, tradeCfg.slippageCap);
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
        return processManualSell(gen, TradeSettings.parse(env.durableRead(TempNo.SETTINGS_KEY)));
    }

    /** processManualSell 본체 — 설정은 runCycle이 사이클당 1회 파싱한 것을 넘겨받는다. */
    public int processManualSell(int gen, TradeSettings tradeCfg) {
        if (!env.isRunning(gen)) {
            // 요청을 소비하기 전에 세대를 확인한다 — 구 세션이 키만 비우고
            // 실행은 세대 게이트에 막히면 요청이 소실된다. 새 세션이 이어서
            // 처리할 수 있게 남겨 둔다.
            return 0;
        }
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
            saveManualSellStatus("", "bad_format", "요청 형식 오류로 버려졌습니다");
            return 0;
        }
        ManualSell.Request req = ManualSell.parseRequest(model);
        if (req == null) {
            log("수동 매도 요청 형식 오류 — 버립니다");
            saveManualSellStatus("", "bad_format", "요청 형식 오류로 버려졌습니다");
            return 0;
        }
        String label = req.slug.isEmpty() ? req.tokenId : req.slug;
        if (ManualSell.isStale(req, env.nowMs())) {
            log("수동 매도 요청 만료 — 오래된 요청이라 버립니다 (" + label + ")");
            saveManualSellStatus(req.slug, "expired",
                    "요청이 만료돼 실행되지 않았습니다 — 다시 요청하세요");
            errorNotify(com.roomedia.boringmech.write.NotifyPolicy.KEY_MANUAL_EXPIRED,
                    "수동 매도 요청 만료",
                    label + " · 요청이 만료돼 실행되지 않았습니다");
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
            saveManualSellStatus(req.slug, "not_held", "보유 중인 종목이 아닙니다");
            return 0;
        }
        double current = Js.fieldNum(row, "current_price");
        if (!Double.isFinite(current) || current <= 0) {
            log("수동 매도 건너뜀 — 현재가를 확인할 수 없습니다 (" + label + ")");
            saveManualSellStatus(req.slug, "no_price", "현재가를 확인할 수 없습니다");
            return 0;
        }
        // 수동 매도도 손절 경로와 같은 slippageCap 설정을 탄다 — 설정은
        // runCycle이 파싱한 것을 넘겨받는다 (사이클당 1회 파싱 원칙,
        // v0.3.39 감사 B).
        boolean done = seller.executeManualSell(row, current, gen, tradeCfg.slippageCap);
        saveManualSellStatus(req.slug, done ? "filled" : "failed",
                done ? "매도가 체결됐습니다" : "체결되지 않았습니다 — 최근 로그를 확인하세요");
        return done ? 1 : 0;
    }

    /** 수동 매도 종단 결과를 뷰어 표시용으로 남긴다 — 실패해도 흐름에 영향 없다. */
    private void saveManualSellStatus(String slug, String outcome, String detail) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", (double) env.nowMs());
            m.put("slug", slug);
            m.put("outcome", outcome);
            m.put("detail", detail);
            env.durableWrite(ManualSell.STATUS_KEY, ModelJson.stringify(m));
        } catch (Exception ignored) {
        }
    }

    /** runStopLoss 이식 — 보유 행마다 예외를 격리한다. 반환값은 매도 성공 건수. */
    public int runStopLoss(int gen) {
        return runStopLoss(gen, TradeSettings.parse(env.durableRead(TempNo.SETTINGS_KEY)));
    }

    /** runStopLoss — 사이클이 파싱한 설정을 그대로 쓰는 본체. */
    public int runStopLoss(int gen, TradeSettings tradeCfg) {
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
                    if (checkStopLossPosition(row, gen, tradeCfg)) {
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

    /**
     * 손절 폴백 — 포지션 재조회까지 실패했을 때 마지막 스냅샷 캐시의
     * 장부로 손절만 돌린다 (engine.js runFallbackStopLoss 이식). 판정·
     * 실행은 checkStopLossPosition을 그대로 타고, 매수는 실행하지
     * 않는다 (호출한 runCycle이 원래 오류로 사이클을 끝낸다).
     */
    public int runFallbackStopLoss(int gen, TradeSettings tradeCfg) {
        long now = env.nowMs();
        Set<String> soldTokens = new HashSet<>();
        for (Object r : StopLossLedger.records(ledgerStore)) {
            Object t = Js.field(r, "token_id");
            if (t instanceof String) {
                soldTokens.add((String) t);
            }
        }
        for (String key : new String[]{ManualSell.ARCHIVE_KEY, ManualSell.LEDGER_KEY}) {
            for (Object r : StopLoss.parseStopLossList(env.durableRead(key))) {
                Object t = Js.field(r, "token_id");
                if (t instanceof String) {
                    soldTokens.add((String) t);
                }
            }
        }
        List<Object> dead = StopLossFallback.parseDead(
                env.durableRead(StopLossFallback.DEAD_KEY));
        Set<String> deadActive = StopLossFallback.deadActiveTokens(dead, now);
        Object cache = null;
        String cacheJson = env.getSnapCache();
        if (cacheJson != null && !cacheJson.isEmpty()) {
            try {
                cache = JsonText.parse(cacheJson);
            } catch (Exception ignored) {
                cache = null;
            }
        }
        List<Map<String, Object>> rows = StopLossFallback.rowsFromCache(cache, soldTokens, deadActive);
        log("폴백 손절 실행 — 마지막 스냅샷 장부 " + rows.size() + "건 기준 (포지션 조회 실패)");
        int sold = 0;
        boolean deadChanged = false;
        for (Map<String, Object> row : rows) {
            if (!env.isRunning(gen)) {
                break;
            }
            env.beatProgress(cycle);
            String tokenId = (String) row.get("token_id");
            // 처분 완료 토큰 차단 — 판정 전에 CLOB 잔고로 실재를 확인한다.
            // 조회 자체가 실패하면 이번 사이클은 그 건을 건너뛴다.
            double exact;
            try {
                BigInteger bal = net.conditionalBalance(tokenId);
                exact = bal == null ? Double.NaN : bal.doubleValue() / 1e6;
            } catch (Exception e) {
                continue;
            }
            if (!Double.isFinite(exact) || exact < 0.01) {
                // 잔고 없음 = 이미 처분된 토큰. 무효로 남기고 처음 한 번만
                // 알린다 — 폴백 사이클마다 실패 로그를 쌓지 않는다.
                boolean known = StopLossFallback.hasToken(dead, tokenId);
                dead = StopLossFallback.withDead(dead, tokenId, env.nowMs());
                deadChanged = true;
                if (!known) {
                    log("폴백 손절 건너뜀 " + row.get("slug") + " — 잔고 없음 (처분 완료로 간주)");
                }
                continue;
            }
            row.put("current_size", exact);
            try {
                if (checkStopLossPosition(row, gen, tradeCfg)) {
                    sold++;
                }
            } catch (Exception e) {
                log("폴백 손절 처리 오류 " + row.get("slug") + ": " + slice(msg(e), 120));
            }
        }
        if (deadChanged) {
            env.durableWrite(StopLossFallback.DEAD_KEY, ModelJson.stringify(dead));
        }
        return sold;
    }

    // ── 리딤 ────────────────────────────────────────────────────────────────

    private static final int REDEEM_PENDING_CAP = 300;
    private static final String REDEEM_PENDING_KEY = "bm_redeem_pending";
    private static final String REDEEM_PENDING_ARCHIVE_KEY = "bm_redeem_pending_archive";

    private static String redeemPendingKeyOf(Object e) {
        return Js.str(Js.field(e, "condition_id")) + "|" + Js.str(Js.field(e, "token_id"));
    }

    /**
     * 리딤 pending 로드 — 본 목록과 절단 아카이브를 병합한다. 아카이브된
     * 항목도 리딤 사이클이 다시 회수해 재평가하고, 중복은
     * condition_id|token_id 키로 제거한다 (본 목록 쪽 항목이 우선).
     */
    private List<Object> loadRedeemPending() {
        List<Object> pending = loadDurableJsonList(REDEEM_PENDING_KEY);
        List<Object> archive = loadDurableJsonList(REDEEM_PENDING_ARCHIVE_KEY);
        if (archive.isEmpty()) {
            return pending;
        }
        Set<String> pendingKeys = new HashSet<>();
        for (Object e : pending) {
            pendingKeys.add(redeemPendingKeyOf(e));
        }
        List<Object> merged = new ArrayList<>();
        for (Object e : archive) {
            if (!pendingKeys.contains(redeemPendingKeyOf(e))) {
                merged.add(e);
            }
        }
        merged.addAll(pending);
        return merged;
    }

    /**
     * 리딤 pending 저장 — 인자는 전체 목록이다. 캡(300건)을 넘는 앞부분은
     * 버리지 않고 아카이브 키에 보존하고(load가 다시 병합한다), 전체
     * 목록에서 빠진(해소된) 항목은 아카이브에서도 함께 사라진다
     * (v0.3.38 감사 축 3 — 절단으로 인한 영구 리딤 누락 방지).
     */
    private void saveRedeemPending(List<Object> list) {
        List<Object> overflow = list.size() > REDEEM_PENDING_CAP
                ? new ArrayList<>(list.subList(0, list.size() - REDEEM_PENDING_CAP))
                : new ArrayList<>();
        env.durableWrite(REDEEM_PENDING_ARCHIVE_KEY, ModelJson.stringify(overflow));
        List<Object> tail = list.size() > REDEEM_PENDING_CAP
                ? new ArrayList<>(list.subList(list.size() - REDEEM_PENDING_CAP, list.size())) : list;
        env.durableWrite(REDEEM_PENDING_KEY, ModelJson.stringify(tail));
    }

    private void recordRedeemHistory(Map<String, Object> rec) {
        List<Object> list = loadDurableJsonList("bm_redeem_history");
        list.add(rec);
        if (list.size() > 100) {
            // 캡에서 밀려난 기록의 condition_id는 done 아카이브로 옮겨
            // 보존한다 — 근거가 사라지면 회수 완료 건이 pending으로
            // 재수집돼 불필요한 재처리가 반복된다 (v0.3.35 감사 축 3).
            List<Object> overflowIds = new ArrayList<>();
            for (Object old : list.subList(0, list.size() - 100)) {
                Object cid = Js.field(old, "condition_id");
                if (cid != null) {
                    overflowIds.add(cid);
                }
            }
            archiveRedeemDoneIds(overflowIds);
            list = new ArrayList<>(list.subList(list.size() - 100, list.size()));
        }
        env.durableWrite("bm_redeem_history", ModelJson.stringify(list));
    }

    /** done 근거 아카이브 — history·closed 캡에서 밀려난 condition_id 보존. */
    private void archiveRedeemDoneIds(List<Object> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<Object> archive = loadDurableJsonList("bm_redeem_done_archive");
        for (Object id : ids) {
            if (!archive.contains(id)) {
                archive.add(id);
            }
        }
        if (archive.size() > 10_000) {
            archive = new ArrayList<>(archive.subList(archive.size() - 10_000, archive.size()));
        }
        env.durableWrite("bm_redeem_done_archive", ModelJson.stringify(archive));
    }

    private void removeRedeemPending(String cid) {
        List<Object> list = loadRedeemPending();
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
        if (arr.size() > 500) {
            // 캡에서 밀려난 종결 id도 done 아카이브로 보존한다 (위와 같은 이유).
            archiveRedeemDoneIds(new ArrayList<>(arr.subList(0, arr.size() - 500)));
            arr = new ArrayList<>(arr.subList(arr.size() - 500, arr.size()));
        }
        env.durableWrite("bm_redeem_closed", ModelJson.stringify(arr));
        List<Object> pendingNow = loadRedeemPending();
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
        if (ctx.tokenIds == null || ctx.tokenIds.size() < 2) {
            // 잔고 게이트를 걸 수 없으면 제출하지 않는다 — 제출 확인이 늦어
            // 다음 사이클에 재시도될 때, 이미 회수된 건을 게이트 없이 다시
            // 제출하는 중복 온체인 제출을 막기 위해서다 (v2 분기와 동일 원칙).
            throw new Exception("CTF 토큰 ID 없음 — 잔고 게이트 없이 제출 보류");
        }
        {
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
        List<Object> pending = loadRedeemPending();
        List<Object> history = loadDurableJsonList("bm_redeem_history");
        List<Object> closedList = loadDurableJsonList("bm_redeem_closed");
        Set<Object> done = new HashSet<>();
        for (Object h : history) {
            done.add(Js.field(h, "condition_id"));
        }
        done.addAll(closedList);
        // 캡에서 밀려나 아카이브된 done 근거도 같은 무게로 본다.
        done.addAll(loadDurableJsonList("bm_redeem_done_archive"));
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
        // worthless streak는 이번 사이클에 정산 조회가 성공해 새 증거가 있을
        // 때만 올린다. 조회 실패로 win/lost/closed가 전부 null인 사이클에서
        // 저장된 항목만으로 streak가 올라 종결(bm_redeem_closed, 비가역)까지
        // 가면, 가치 갱신이 늦은 승자 건이 영구 누락된다.
        boolean settlementKnown = lastWinRows != null || lastLostRows != null || lastClosedRows != null;
        if (!plan.worthless.isEmpty() && settlementKnown) {
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
                errorNotify(com.roomedia.boringmech.write.NotifyPolicy.KEY_REDEEM_FAILED,
                        "리딤 실패", slice(cid, 10) + "… · " + slice(msg(e), 120)
                                + " — 다음 사이클에 재시도합니다");
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
            joined.append(BoughtReconcile.slugOfEntry(rec.dropped.get(i)));
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
        return summarizeCycle(candidateCount, verified, rejects, filled, unfilled, failed, scan, null);
    }

    /** 단계 소요를 붙인 요약 — durationsText가 null이면 기존 요약과 같다. */
    public static String summarizeCycle(int candidateCount, int verified, Map<String, Integer> rejects,
                                 int filled, int unfilled, int failed, Map<String, Integer> scan,
                                 String durationsText) {
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
        if (durationsText != null && !durationsText.isEmpty()) {
            sb.append(" · ").append(durationsText);
        }
        return sb.toString();
    }

    /** 단계 소요 표기 — 스냅샷·손절·스캔 전체와 스캔 안의 검증·주문 누적. */
    public static String stageDurationsText(long snapshotMs, long stopLossMs, long scanMs,
                                            long verifyMs, long orderMs) {
        return "소요 스냅샷 " + snapshotMs + "ms · 손절 " + stopLossMs + "ms · 스캔 " + scanMs
                + "ms · 검증 " + verifyMs + "ms · 주문 " + orderMs + "ms";
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
        // 매매·기온 설정은 사이클당 1회만 파싱해 스냅샷·손절·스캔 전부에
        // 같은 객체를 넘긴다 — 지점마다 재파싱하면 사이클 도중 설정이
        // 바뀌었을 때 구·신 설정이 섞인다 (v0.3.38 감사).
        TradeSettings tradeCfg = TradeSettings.parse(env.durableRead(TempNo.SETTINGS_KEY));
        TempNo.Settings tempSettings = TempNo.parseSettings(env.durableRead(TempNo.SETTINGS_KEY));
        long stageT0 = env.nowMs();
        stageMsSnapshot = 0;
        stageMsStopLoss = 0;
        stageMsScan = 0;
        stageMsVerify = 0;
        stageMsOrder = 0;
        env.setStage("snapshot", cycle);
        Snap snap;
        try {
            snap = snapshot(tradeCfg);
        } catch (Exception e) {
            try {
                List<Object> rows = net.fetchPositions(null);
                lastOpenRows = PositionBook.openRows(rows);
                env.setStage("stoploss", cycle);
                runStopLoss(gen, tradeCfg);
                log("스냅샷 실패로 손절만 단독 실행함 — 원래 오류: " + slice(msg(e), 100));
            } catch (Exception e2) {
                // 재조회까지 실패하면 Data API가 통째로 죽은 것이다 — 마지막
                // 스냅샷 장부 기준의 손절 폴백으로 대체한다 (2026-11-25 후속).
                try {
                    runFallbackStopLoss(gen, tradeCfg);
                } catch (Exception e3) {
                    log("스냅샷 실패 + 손절 단독 실행도 실패: " + slice(msg(e2), 100));
                }
            }
            throw e;
        }
        log("사이클 " + cycle + " · 잔고 $" + n(snap.cash) + " · 평가 $" + n(snap.equity)
                + " · 건당 $" + n(snap.bet) + " · 슬롯 " + snap.slots + " · 보유 " + snap.openCount);
        int manualSold = processManualSell(gen, tradeCfg);
        if (manualSold > 0) {
            snap = snapshot(tradeCfg);
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
        stageMsSnapshot = env.nowMs() - stageT0;
        long stageT1 = env.nowMs();
        env.setStage("stoploss", cycle);
        int sold = runStopLoss(gen, tradeCfg);
        env.beatProgress(cycle);
        if (sold > 0) {
            snap = snapshot(tradeCfg);
        }
        stageMsStopLoss = env.nowMs() - stageT1;
        env.setStage("redeem", cycle);
        double recovered = runRedeems(gen);
        env.beatProgress(cycle);
        if (recovered > 0) {
            snap = snapshot(tradeCfg);
        }
        if (settleNotice != null) {
            moneyNotify("정산 확정", "실현 " + com.roomedia.boringmech.write.StopLoss.signedUsd(settleNotice.realizedDelta)
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
            // sizing 슬롯이 0이어도 예약 차선이 성립할 가능성이 있으면
            // 스캔까지 진행한다 — 홀드가 비워 둔 자리는 기온이 실제로
            // 쓸 수 있어야 한다 (JS runCycle과 같은 규칙).
            int reserveEffPre = TempNo.effectiveReserveSlots(
                    tempSettings.reserveSlots, tradeCfg.maxPositions);
            boolean lanePossible = tempSettings.enabled && reserveEffPre > 0
                    && snap.openCount < tradeCfg.maxPositions
                    && snap.bet > 0
                    && snap.cash >= snap.bet + tradeCfg.reserveUsd;
            if (!lanePossible) {
                log("슬롯 없음 — 이번 사이클 매수 없음");
                return;
            }
            log("슬롯 없음 — 기온 예약 차선으로 진입을 시도합니다");
        }
        if (BoughtReconcile.boughtCorrupted(env.durableRead("bm_bought"))) {
            log("기매수 기록 손상 감지 — 이번 사이클 매수를 건너뜁니다 (재매수 방벽 보호)");
            return;
        }
        if (!boughtReconciled) {
            reconcileBoughtOnce(snap);
        }
        scanStartMs = env.nowMs();
        env.setStage("scan", cycle);
        runScanAndBuy(gen, snap, tradeCfg, tempSettings);
    }

    /**
     * 오늘+내일 기온 이벤트 조회 — 15분 캐시. 도시별 실패는 Net 구현이
     * 건너뛰므로 돌아온 목록(빈 목록 포함)을 그대로 캐시한다. 단,
     * 전 도시 실패(null)는 캐시하지 않아 다음 사이클에 다시 시도한다
     * (JS fetchTempEvents와 같은 규칙). 조회 범위만 이틀치이고 후보
     * 생성은 창 판정으로 당일 이벤트만 쓴다. 캐시는 기준 UTC 날짜가
     * 같은 동안만 유효하다 — 자정을 넘기면 날짜 집합이 달라져 전날
     * 캐시가 새 집합을 덮지 않게 무효화한다.
     */
    private List<Object> tempEvents(long nowMs) throws Exception {
        String dayKey = TempNo.fetchDayKey(nowMs);
        if (tempEventsCache != null && dayKey.equals(tempEventsCacheDayKey)
                && nowMs - tempEventsCacheAtMs < TempNo.CACHE_MS) {
            return tempEventsCache;
        }
        List<Object> events = net.fetchTempEvents(TempNo.eventSlugsForFetch(nowMs));
        if (events == null) {
            // 전수 실패는 빈 목록으로 바꾸지 않고 null로 전파한다 —
            // "오늘 이벤트 없음"과 구분해 호출자가 예약을 유지하는 쪽으로
            // 판정하게 하기 위해서다 (v0.3.39 감사 E).
            return null;
        }
        tempEventsCache = events;
        tempEventsCacheAtMs = nowMs;
        tempEventsCacheDayKey = dayKey;
        return events;
    }

    private void runScanAndBuy(int gen, Snap snap, TradeSettings tradeCfg,
                               TempNo.Settings tempSettings) throws Exception {
        List<Object> markets = net.fetchMarkets();
        Map<String, Integer> scanStats = new LinkedHashMap<>();
        Set<String> boughtAll = boughtSlugStrings();
        boughtAll.addAll(snap.stopLossSlugs);
        // 결과 불명으로 끝난 손절 시도의 slug도 차단한다 — 실제로는
        // 체결됐는데 bm_bought에 없는 포지션이면 체결 기록도 보유도
        // 사라져 차단이 뚫리는 경로가 있었다 (v0.3.35 감사 축 2).
        boughtAll.addAll(StopLossLedger.attemptedSlugs(ledgerStore));
        // 매매 설정은 runCycle이 사이클 시작에 파싱한 것을 그대로 쓴다.
        List<ScanFilter.Candidate> candidates = ScanFilter.scanCandidates(
                markets, snap.heldSlugs, boughtAll, scanStats, env.nowMs(), tradeCfg);
        // 기온 No 페이드 — 설정이 켜졌을 때만 별도 스캔을 붙인다. 후보는
        // 같은 선정 패스(슬롯·검증 캡 공유)를 타고, 메인 후보와 slug가
        // 겹치면 메인 쪽이 이긴다. 기온 스캔 통계는 요약에 섞지 않는다.
        // 조회한 이벤트 목록은 아래 홀드 판정(창 대기)도 함께 쓴다.
        List<Object> tempEventsList = null;
        // 기온 이벤트 조회 전수 실패 여부 — 실패와 "오늘 이벤트 없음"을
        // 구분해, 전수 실패 사이클은 예약을 유지하는 쪽으로 판정한다
        // (안전 방향, v0.3.39 감사 E).
        boolean tempFetchFailed = false;
        if (tempSettings.enabled) {
            try {
                List<Object> events = tempEvents(env.nowMs());
                if (events == null) {
                    tempFetchFailed = true;
                    log("기온 이벤트 조회 전수 실패 — 이번 사이클은 기온 예약을 유지합니다");
                } else {
                    tempEventsList = events;
                    Set<String> mainSlugs = new HashSet<>();
                    for (ScanFilter.Candidate c : candidates) {
                        mainSlugs.add(c.slug);
                    }
                    List<ScanFilter.Candidate> tempCands = TempNo.scanTempCandidates(
                            events, snap.heldSlugs, boughtAll, tempSettings, env.nowMs(), mainSlugs);
                    if (!tempCands.isEmpty()) {
                        log("기온 후보 " + tempCands.size() + "건 (밴드 " + n(tempSettings.bandLo)
                                + "–" + n(tempSettings.bandHi) + ")");
                        candidates.addAll(tempCands);
                    }
                }
            } catch (Exception e) {
                log("기온 스캔 오류: " + slice(msg(e), 120));
            }
        }
        int slotsLeft = snap.slots;
        int verified = 0;
        Map<String, Integer> rejects = new LinkedHashMap<>();
        int filledCount = 0;
        int unfilledCount = 0;
        int failedCount = 0;
        // 선정 드라이버 — 기온 슬롯 홀드 (JS runCycle과 같은 규칙).
        // 토글 ON + 예약 슬롯 N>0이고, 오늘·내일 기온 이벤트 중 진입
        // 창이 남은 것이 있거나(후보가 있으면 그 이벤트가 여기에
        // 걸린다) 조회가 전수 실패했을 때 홀드가 켜진다. 홀드가 켜지면
        // 메인의 유효 용량을 전체에서 N슬롯 줄여 실제로 비워 둔다:
        // 메인 후보는 남은 슬롯이 N개 이하이거나 검증이 캡−N에 닿으면
        // 그 사이클에서 건너뛰고, 다시 태우지 않는다 — 다시 태우면
        // 비워 둔 슬롯이 같은 사이클에 사라져 창이 열릴 때 남는 자리가
        // 없다. slots는 건수 상한과 현금 기준 슬롯 수의 최소값이라,
        // 이 차단은 건당 베팅액 × N만큼의 현금도 함께 비운다. 기온
        // 후보는 홀드와 무관하게 전 용량을 쓴다 (예약은 하한이라
        // 기온이 N개를 넘어 사는 것도 막지 않는다). 홀드 조건이 없으면
        // 차단되는 후보가 없어 기존 단일 패스와 동일하다. 해당하는
        // 이벤트가 없거나 전부 종료됐으면 홀드가 풀려 메인이 전
        // 용량을 쓴다.
        int reserveEff = TempNo.effectiveReserveSlots(
                tempSettings.reserveSlots, tradeCfg.maxPositions);
        boolean tempHold = tempSettings.enabled
                && reserveEff > 0
                && (tempFetchFailed
                    || TempNo.hasUpcomingWindow(tempEventsList, env.nowMs(),
                            tempSettings.entryHourUtc, snap.heldSlugs, boughtAll));
        // 예약 차선 회계 — sizing 슬롯 밖에서 쓴 자리·현금을 따로 센다.
        int laneLeft = tempHold ? reserveEff : 0;
        int positionsUsed = snap.openCount;
        double cashLeft = snap.cash;
        List<ScanFilter.Candidate> heldMain = new ArrayList<>();
        for (ScanFilter.Candidate cand : candidates) {
            if (!env.isRunning(gen)) {
                return;
            }
            if (tempHold
                    && TempNo.reserveBlocksMain(cand.strategy, slotsLeft, verified, tradeCfg.verifyCap,
                            reserveEff)) {
                heldMain.add(cand);
                continue;
            }
            if (verified >= tradeCfg.verifyCap) {
                break;
            }
            boolean viaLane = false;
            if (slotsLeft < 1) {
                // sizing 슬롯이 소진된 뒤에는 예약 차선으로 기온 후보만
                // 더 시도한다 (JS 선정 드라이버와 같은 규칙).
                if (TempNo.reserveLaneAllows(cand.strategy, tempHold, laneLeft, positionsUsed,
                        tradeCfg.maxPositions, cashLeft, snap.bet, tradeCfg.reserveUsd)) {
                    viaLane = true;
                } else {
                    continue;
                }
            }
            env.beatProgress(cycle);
            verified++;
            CandidateVerifier.Result detail;
            long verifyT0 = env.nowMs();
            try {
                detail = CandidateVerifier.verifyCandidate(cand, snap.bet, gammaSource, bookSource, env.nowMs(),
                        tradeCfg, tempSettings.entryHourUtc);
            } catch (Exception e) {
                stageMsVerify += env.nowMs() - verifyT0;
                tally(rejects, "verify_error");
                log("검증 오류 " + cand.slug + ": " + msg(e));
                continue;
            }
            stageMsVerify += env.nowMs() - verifyT0;
            if (!detail.ok) {
                tally(rejects, detail.reason);
                if (detail.note != null) {
                    log(detail.note);
                }
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
                long orderT0 = env.nowMs();
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
                        errorNotify(com.roomedia.boringmech.write.NotifyPolicy.KEY_SIGN_FAIL,
                                "주문 서명 실패", cand.slug + " · " + slice(msg(e), 120));
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
                        markBought(cand.slug, cand.strategy);
                        if (viaLane) { laneLeft--; } else { slotsLeft--; }
                        positionsUsed++;
                        cashLeft -= snap.bet;
                        String m = msg(e);
                        log("주문 결과 불명 " + cand.slug + ": " + slice(m, 120) + " — 중복 방지 기록"
                                + (TempNo.STRATEGY.equals(cand.strategy) ? " · 전략 temp_no" : ""));
                        errorNotify(com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_UNKNOWN,
                                "주문 결과 불명",
                                cand.slug + " · 제출 응답이 끊겨 체결 여부를 모릅니다 — 보유 목록에서 확인하세요");
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
                        markBought(cand.slug, cand.strategy);
                        if (viaLane) { laneLeft--; } else { slotsLeft--; }
                        positionsUsed++;
                        cashLeft -= snap.bet;
                        log("주문 결과 불명 " + cand.slug + ": " + slice(verdict.error == null ? "" : verdict.error, 120)
                                + " — 중복 방지 기록"
                                + (TempNo.STRATEGY.equals(cand.strategy) ? " · 전략 temp_no" : ""));
                        errorNotify(com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_UNKNOWN,
                                "주문 결과 불명",
                                cand.slug + " · 체결 여부를 확정하지 못했습니다 — 보유 목록에서 확인하세요");
                        continue;
                    }
                    if (!verdict.filled && !"unfilled".equals(verdict.kind)) {
                        failedCount++;
                        String m = verdict.error != null ? verdict.error : "주문 거부 (success=false)";
                        errorNotify(com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_FAILED,
                                "주문 실패", cand.slug + " · " + slice(m, 120));
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
                            markBought(cand.slug, cand.strategy);
                            if (viaLane) { laneLeft--; } else { slotsLeft--; }
                            positionsUsed++;
                            cashLeft -= snap.bet;
                            log("미체결(지연 접수) " + cand.slug + " — 뒤늦은 체결에 대비해 기록하고 슬롯을 소모합니다"
                                    + (TempNo.STRATEGY.equals(cand.strategy) ? " · 전략 temp_no" : ""));
                        } else {
                            Object st = Js.field(resp, "status");
                            String statusText = (st == Js.UNDEFINED || st == null) ? "(없음)" : Js.str(st);
                            log("미체결 " + cand.slug + " · 응답 status: " + statusText
                                    + " · 응답 원문: " + slice(ModelJson.stringify(resp), 200));
                        }
                        continue;
                    }
                    markBought(cand.slug, cand.strategy);
                    sessionFills++;
                    filledCount++;
                    if (viaLane) { laneLeft--; } else { slotsLeft--; }
                    positionsUsed++;
                    cashLeft -= snap.bet;
                    Object making = Js.field(resp, "makingAmount");
                    Object taking = Js.field(resp, "takingAmount");
                    boolean hasFillPrice = Js.truthy(making) && Js.truthy(taking);
                    double fillPrice = hasFillPrice ? Js.round2(Js.num(making) / Js.num(taking)) : 0;
                    String fill = hasFillPrice ? " 체결가 " + n(fillPrice) : "";
                    Object respStatus = Js.field(resp, "status");
                    log("매수 " + cand.slug + " · $" + n(snap.bet) + " @ " + n(detail.limitPrice) + fill
                            + " · 상태 " + (Js.truthy(respStatus) ? Js.str(respStatus) : "matched")
                            + (TempNo.STRATEGY.equals(cand.strategy) ? " · 전략 temp_no" : "")
                            + (viaLane ? " · 예약 차선" : "")
                            + (TempNo.STRATEGY.equals(cand.strategy) && cand.feeRate != null
                                    ? " · 수수료율 " + n(cand.feeRate) : ""));
                    // 응답에 실제 체결 수량·가격이 있을 때만 본문에 넣는다.
                    // 없는데 추정 수량·지정가를 넣으면 체결값으로 오독된다.
                    boolean hasTaking = Js.truthy(taking) && Double.isFinite(Js.num(taking));
                    moneyNotify("매수 체결", cand.slug + " · " + cand.side
                            + (hasTaking ? " · " + n(Js.num(taking)) + "주" : "")
                            + (hasFillPrice ? " · 체결가 " + n(fillPrice) : "")
                            + " · $" + n(snap.bet));
                } finally {
                    stageMsOrder += env.nowMs() - orderT0;
                    guard.endSubmit();
                }
            } finally {
                guard.releaseSubmitLock(cand.slug);
            }
        }
        if (tempHold && !heldMain.isEmpty()) {
            if (tempFetchFailed) {
                log("기온 예약 — 조회 전수 실패로 메인 " + heldMain.size()
                        + "건을 미루고 슬롯을 남깁니다");
            } else {
                log("기온 예약 — 진입 창이 남은 이벤트가 있어 메인 " + heldMain.size()
                        + "건을 미루고 슬롯을 남깁니다");
            }
        }
        stageMsScan = env.nowMs() - scanStartMs;
        log(summarizeCycle(candidates.size(), verified, rejects, filledCount, unfilledCount, failedCount, scanStats,
                stageDurationsText(stageMsSnapshot, stageMsStopLoss, stageMsScan, stageMsVerify, stageMsOrder)));
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
                errorNotify(com.roomedia.boringmech.write.NotifyPolicy.KEY_CYCLE_ERRORS,
                        "엔진 오류", "사이클 오류 " + cycleErrors
                                + "회 연속 — 자가 재시작으로 복구를 시도합니다");
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
