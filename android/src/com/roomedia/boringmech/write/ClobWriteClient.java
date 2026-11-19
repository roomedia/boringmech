package com.roomedia.boringmech.write;

import com.roomedia.boringmech.crypto.Amounts;
import com.roomedia.boringmech.crypto.OrderSigner;
import com.roomedia.boringmech.crypto.PolyHmac;
import com.roomedia.boringmech.engine.Js;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * CLOB 쓰기 클라이언트 — engine.js가 withClientTimeout으로 감싼 ClobClient의
 * createMarketOrder + postOrder 등가 경로.
 *
 * 재현 범위 (SDK @polymarket/clob-client-v2 1.2.1 기준):
 * - createMarketOrder: 라우팅(positionID면 v3 강제) → 시장 정보 캐시 →
 *   틱 검증(min보다 작으면 오류) → 가격 유효성 → 버전 결정(서버 /version,
 *   캐시) → negRisk(v3면 false) → 금액 산출(Amounts) → 주문 조립
 *   (salt·timestamp 생성) → POLY_1271 서명(OrderSigner).
 *   엔진이 쓰지 않는 분기(userUSDCBalance 잔고 조정·builderCode·feeRate v1)는
 *   입력 자체가 없어 도달하지 않는다 — v1은 POLY_1271과 양립하지 않아 오류.
 * - postOrder: orderToJsonV2 본문(OrderJson) → L2 헤더(PolyHmac) →
 *   OrderBridge 전송(shaping·재시도) → 버전 불일치 시 버전 강제 갱신 →
 *   resolveTransactionsHashes(체결 해시 폴링).
 * - 메서드 단위 20초 타임아웃(withClientTimeout 등가)은 TimeoutRunner가 맡는다.
 *
 * 실제 네트워크 호출은 주입된 Transport가 한다. 기본 배선(HttpTransport)은
 * 통합 단계 몫이며, 이 클래스는 전송 직전 구성·직렬화·판정까지를 책임진다.
 */
public final class ClobWriteClient {
    public static final String DEFAULT_HOST = "https://clob.polymarket.com";
    public static final int CHAIN_ID = 137;
    public static final int SIGNATURE_TYPE_POLY_1271 = 3;
    public static final long CLIENT_TIMEOUT_MS = 20_000;
    public static final String EXCHANGE_V2 = "0xE111180000d2663C0091e4f400237545B87B996B";
    public static final String NEG_RISK_EXCHANGE_V2 = "0xe2222d279d744050d28e00520010520000310F59";
    public static final String EXCHANGE_V3 = "0xe3333700cA9d93003F00f0F71f8515005F6c00Aa";
    public static final String BYTES32_ZERO =
            "0x0000000000000000000000000000000000000000000000000000000000000000";
    public static final String ORDER_VERSION_MISMATCH_ERROR = "order_version_mismatch";

    // ── 주입 지점 ──

    public interface Clock {
        long nowMs();
    }

    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public interface SaltGenerator {
        String generate();
    }

    /** withClientTimeout 등가 — 메서드 전체를 제한 시간으로 감싼다. */
    public interface TimeoutRunner {
        <T> T run(String prop, Callable<T> call) throws Exception;
    }

    /** 서버 주문 버전 결정 (SDK resolveVersion — 캐시 + 강제 갱신). */
    public interface VersionResolver {
        int resolveVersion(boolean forceUpdate) throws Exception;
    }

    /** 틱·negRisk·시장 정보 캐시 (SDK getTickSize/getNegRisk/_ensureMarketInfoCached). */
    public interface MarketInfoSource {
        void ensureMarketInfoCached(String tokenId) throws Exception;

        String getTickSize(String tokenId) throws Exception;

        boolean getNegRisk(String tokenId) throws Exception;
    }

    /** getTrades({id}, only_first_page=true) 한 페이지 — resolveTransactionsHashes용. */
    public interface TradesSource {
        List<Object> getTradesPage(String tradeId) throws Exception;
    }

    // ── 값 객체 ──

    public static final class Creds {
        public final String eoa;
        public final String key;
        public final String secret;
        public final String passphrase;

        public Creds(String eoa, String key, String secret, String passphrase) {
            this.eoa = eoa;
            this.key = key;
            this.secret = secret;
            this.passphrase = passphrase;
        }
    }

    public static final class MarketOrderRequest {
        public String tokenID;
        public String positionID;
        public boolean buy;
        public double amount;
        public double price;
        /** 엔진은 항상 "FOK". */
        public String orderType = "FOK";
        public String tickSize;
        public Boolean negRisk;
        /** options.version — 엔진은 지정하지 않는다(서버 버전). */
        public Integer version;

        public MarketOrderRequest(String tokenID, boolean buy, double amount, double price) {
            this.tokenID = tokenID;
            this.buy = buy;
            this.amount = amount;
            this.price = price;
        }
    }

    // ── 기본 구현들 ──

    public static final Clock SYSTEM_CLOCK = System::currentTimeMillis;
    public static final Sleeper THREAD_SLEEPER = Thread::sleep;

    /** SDK generateOrderSalt: `${Math.round(Math.random() * Date.now())}`. */
    public static SaltGenerator randomSaltGenerator(Clock clock) {
        return () -> Long.toString(Math.round(Math.random() * clock.nowMs()));
    }

    /** 타임아웃 없는 실행 (테스트·동기 환경용). */
    public static final TimeoutRunner DIRECT_TIMEOUT = new TimeoutRunner() {
        @Override
        public <T> T run(String prop, Callable<T> call) throws Exception {
            return call.call();
        }
    };

    /**
     * withClientTimeout 재현 — 별도 스레드에서 실행하고 제한을 넘기면
     * "SDK 호출 시간 초과 (20초) {prop}" 오류로 끊는다. JS Promise.race와
     * 달리 백그라운드 실행 자체는 취소 시 interrupt까지만 한다.
     */
    public static TimeoutRunner executorTimeout(long timeoutMs) {
        return new TimeoutRunner() {
            @Override
            public <T> T run(String prop, Callable<T> call) throws Exception {
                java.util.concurrent.ExecutorService ex =
                        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "bm-client-timeout");
                            t.setDaemon(true);
                            return t;
                        });
                try {
                    java.util.concurrent.Future<T> f = ex.submit(call);
                    return f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new Exception("SDK 호출 시간 초과 ("
                            + Math.round(timeoutMs / 1000.0) + "초) " + prop);
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new Exception(cause);
                } finally {
                    ex.shutdownNow();
                }
            }
        };
    }

    // ── 클라이언트 본체 ──

    private final String host;
    private final BigInteger privateKey;
    private final String funder;
    private final Creds creds;
    private final RawHttp.Transport transport;
    private final JsonCodec codec;
    private final Clock clock;
    private final Sleeper sleeper;
    private final SaltGenerator saltGenerator;
    private final TimeoutRunner timeout;
    private final VersionResolver versionResolver;
    private final MarketInfoSource marketInfo;
    private final TradesSource tradesSource;
    private final boolean retryOnError;

    private ClobWriteClient(Builder b) {
        this.host = b.host;
        this.privateKey = b.privateKey;
        this.funder = b.funder;
        this.creds = b.creds;
        this.transport = b.transport;
        this.codec = b.codec;
        this.clock = b.clock;
        this.sleeper = b.sleeper;
        this.saltGenerator = b.saltGenerator != null ? b.saltGenerator : randomSaltGenerator(b.clock);
        this.timeout = b.timeout;
        this.versionResolver = b.versionResolver;
        this.marketInfo = b.marketInfo;
        this.tradesSource = b.tradesSource;
        this.retryOnError = b.retryOnError;
    }

    public static final class Builder {
        private String host = DEFAULT_HOST;
        private BigInteger privateKey;
        private String funder;
        private Creds creds;
        private RawHttp.Transport transport;
        private JsonCodec codec;
        private Clock clock = SYSTEM_CLOCK;
        private Sleeper sleeper = THREAD_SLEEPER;
        private SaltGenerator saltGenerator;
        private TimeoutRunner timeout = DIRECT_TIMEOUT;
        private VersionResolver versionResolver;
        private MarketInfoSource marketInfo;
        private TradesSource tradesSource;
        private boolean retryOnError = false;

        public Builder host(String v) { host = v; return this; }
        public Builder privateKey(BigInteger v) { privateKey = v; return this; }
        public Builder funder(String v) { funder = v; return this; }
        public Builder creds(Creds v) { creds = v; return this; }
        public Builder transport(RawHttp.Transport v) { transport = v; return this; }
        public Builder codec(JsonCodec v) { codec = v; return this; }
        public Builder clock(Clock v) { clock = v; return this; }
        public Builder sleeper(Sleeper v) { sleeper = v; return this; }
        public Builder saltGenerator(SaltGenerator v) { saltGenerator = v; return this; }
        public Builder timeout(TimeoutRunner v) { timeout = v; return this; }
        public Builder versionResolver(VersionResolver v) { versionResolver = v; return this; }
        public Builder marketInfo(MarketInfoSource v) { marketInfo = v; return this; }
        public Builder tradesSource(TradesSource v) { tradesSource = v; return this; }
        public Builder retryOnError(boolean v) { retryOnError = v; return this; }

        public ClobWriteClient build() {
            if (transport == null || codec == null) {
                throw new IllegalStateException("transport/codec가 필요합니다");
            }
            ClobWriteClient c = new ClobWriteClient(this);
            if (versionResolver == null) {
                versionResolver = c.new HttpVersionResolver();
                // 재구성: resolver가 클라이언트를 참조하므로 필드 주입으로 교체
                c = new ClobWriteClient(withResolver(versionResolver));
            }
            if (marketInfo == null || tradesSource == null) {
                ClobWriteClient ref = c;
                if (marketInfo == null) {
                    marketInfo = ref.new HttpMarketInfoSource();
                }
                if (tradesSource == null) {
                    tradesSource = ref.new HttpTradesSource();
                }
                c = new ClobWriteClient(withResolver(versionResolver));
            }
            return c;
        }

        private Builder withResolver(VersionResolver vr) {
            // build() 내부 재구성용 — 현재 값들을 그대로 복사한다.
            Builder b = new Builder();
            b.host = host; b.privateKey = privateKey; b.funder = funder; b.creds = creds;
            b.transport = transport; b.codec = codec; b.clock = clock; b.sleeper = sleeper;
            b.saltGenerator = saltGenerator; b.timeout = timeout; b.versionResolver = vr;
            b.marketInfo = marketInfo; b.tradesSource = tradesSource; b.retryOnError = retryOnError;
            return b;
        }
    }

    // ── L2 헤더 ──

    /** SDK createL2Headers — POLY_ADDRESS는 EOA 주소다 (funder가 아니다). */
    public Map<String, String> createL2Headers(String method, String requestPath, String body) {
        long ts = Math.floorDiv(clock.nowMs(), 1000);
        String sig = PolyHmac.buildSignature(creds.secret, ts, method, requestPath, body);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("POLY_ADDRESS", creds.eoa);
        h.put("POLY_SIGNATURE", sig);
        h.put("POLY_TIMESTAMP", String.valueOf(ts));
        h.put("POLY_API_KEY", creds.key);
        h.put("POLY_PASSPHRASE", creds.passphrase);
        return h;
    }

    // ── createMarketOrder ──

    public SignedOrder createMarketOrder(MarketOrderRequest req) throws Exception {
        return timeout.run("createMarketOrder", () -> createMarketOrderInner(req));
    }

    private SignedOrder createMarketOrderInner(MarketOrderRequest req) throws Exception {
        if (privateKey == null) {
            throw new Exception("L1_AUTH_UNAVAILABLE_ERROR");
        }
        // resolveOrderRouting — tokenID/positionID 중 정확히 하나만
        String assetID = resolveOrderAssetID(req);
        Integer routingVersion = req.positionID != null ? Integer.valueOf(3) : req.version;

        marketInfo.ensureMarketInfoCached(assetID);
        String tickSize = resolveTickSize(assetID, req.tickSize);
        double price = req.price;
        if (price == 0 || Double.isNaN(price)) {
            // SDK calculateMarketPrice 경로 — 엔진은 항상 가격을 넘기므로
            // 도달하지 않지만, 의미론 유지를 위해 오더북 기반 계산을 주입 지점으로 둔다.
            throw new Exception("no match");
        }
        if (!priceValid(price, tickSize)) {
            throw new Exception("invalid price (" + Js.str(price) + "), min: "
                    + Double.parseDouble(tickSize) + " - max: " + (1 - Double.parseDouble(tickSize)));
        }
        int version = routingVersion != null ? routingVersion : versionResolver.resolveVersion(false);
        boolean negRisk = version == 3 ? false
                : (req.negRisk != null ? req.negRisk : marketInfo.getNegRisk(assetID));
        if (version == 1) {
            throw new Exception("signature type POLY_1271 is not supported for v1 orders");
        }
        String exchangeContract;
        switch (version) {
            case 2:
                exchangeContract = negRisk ? NEG_RISK_EXCHANGE_V2 : EXCHANGE_V2;
                break;
            case 3:
                exchangeContract = EXCHANGE_V3;
                break;
            default:
                throw new Exception("unsupported order version " + version);
        }
        Amounts.RoundingConfig cfg = Amounts.roundingConfig(tickSize);
        String[] amts = Amounts.marketOrderAmounts(req.buy, req.amount, price, cfg);

        SignedOrder o = new SignedOrder();
        o.salt = saltGenerator.generate();
        o.maker = funder;
        o.signer = funder; // POLY_1271이면 signer = maker
        o.tokenId = assetID;
        o.makerAmount = amts[0];
        o.takerAmount = amts[1];
        o.side = req.buy ? "BUY" : "SELL";
        o.signatureType = SIGNATURE_TYPE_POLY_1271;
        o.timestamp = String.valueOf(clock.nowMs());
        o.expiration = "0";
        o.metadata = BYTES32_ZERO;
        o.builder = BYTES32_ZERO;
        o.signature = OrderSigner.signOrder(privateKey, o.toSignerOrder(),
                String.valueOf(version), BigInteger.valueOf(CHAIN_ID), exchangeContract);
        return o;
    }

    private static String resolveOrderAssetID(MarketOrderRequest req) {
        String assetID = req.positionID == null ? req.tokenID : req.positionID;
        boolean bad = (req.tokenID == null) == (req.positionID == null)
                || assetID == null || assetID.trim().isEmpty();
        if (bad) {
            throw new IllegalArgumentException(
                    "Exactly one of tokenID or positionID must be provided as a non-empty string");
        }
        return assetID;
    }

    private String resolveTickSize(String tokenId, String tickSize) throws Exception {
        String minTickSize = marketInfo.getTickSize(tokenId);
        if (tickSize != null) {
            if (Double.parseDouble(tickSize) < Double.parseDouble(minTickSize)) {
                throw new Exception("invalid tick size (" + tickSize
                        + "), minimum for the market is " + minTickSize);
            }
            return tickSize;
        }
        return minTickSize;
    }

    private static boolean priceValid(double price, String tickSize) {
        double t = Double.parseDouble(tickSize);
        return price >= t && price <= 1 - t;
    }

    // ── postOrder ──

    /**
     * 반환은 응답 JSON 모델 (resolveTransactionsHashes까지 마친 값).
     *
     * 제출(POST)만 타임아웃으로 감싼다. 해시 해소 폴링은 자체 30초
     * 데드라인으로 bounded라 제출 타임아웃(20초) 밖에 둔다 — 안에 넣으면
     * 폴링 구간이 잘려, 체결됐는데 해시 해소가 20~30초에 끝나는 건이
     * 예외로 떨어져 "결과 불명"으로 오분류된다 (v0.3.35 감사 축 1).
     */
    public Object postOrder(SignedOrder order, String orderType) throws Exception {
        Object res = timeout.run("postOrder", () -> postOrderSubmit(order, orderType));
        return resolveTransactionsHashes(res);
    }

    private Object postOrderSubmit(SignedOrder order, String orderType) throws Exception {
        if (creds == null) {
            throw new Exception("L2_AUTH_NOT_AVAILABLE");
        }
        String body = OrderJson.orderToJsonV2(order, creds.key, orderType);
        Map<String, String> headers = createL2Headers("POST", "/order", body);
        Object res = OrderBridge.bridgePost(transport, host + "/order", headers, body,
                retryOnError, codec, sleeper::sleep);
        if (isOrderVersionMismatch(res)) {
            versionResolver.resolveVersion(true);
        }
        return res;
    }

    public static boolean isOrderVersionMismatch(Object resp) {
        if (!(resp instanceof Map)) {
            return false;
        }
        Object error = ((Map<?, ?>) resp).get("error");
        if (error == null) {
            return false;
        }
        String text = error instanceof String ? (String) error : ModelJson.stringify(error);
        return text.contains(ORDER_VERSION_MISMATCH_ERROR);
    }

    // ── resolveTransactionsHashes ──

    private static final long RESOLVE_TRADES_TIMEOUT_MS = 30_000;
    private static final long RESOLVE_TRADES_POLL_INTERVAL_MS = 250;
    private static final String FAILED_TRADE_STATUS = "FAILED";

    /**
     * SDK resolveTransactionsHashes — 해시가 이미 있으면 그대로, tradeIDs가
     * 없으면 그대로, 있으면 최대 30초간 250ms 간격으로 거래를 조회해
     * 해소된(FAILED가 아닌) 거래의 해시를 채운다. 못 채우면 원본 그대로.
     */
    public Object resolveTransactionsHashes(Object response) throws Exception {
        if (!(response instanceof Map)) {
            return response;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) response).entrySet()) {
            resp.put(String.valueOf(e.getKey()), e.getValue());
        }
        Object hashes = resp.get("transactionsHashes");
        if (hashes instanceof List && !((List<?>) hashes).isEmpty()) {
            return resp;
        }
        Object tradeIDsObj = resp.get("tradeIDs");
        List<String> ids = new ArrayList<>();
        if (tradeIDsObj instanceof List) {
            Set<String> seen = new LinkedHashSet<>();
            for (Object t : (List<?>) tradeIDsObj) {
                String id = Js.str(t);
                if (!id.isEmpty() && seen.add(id)) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            return resp;
        }
        List<Object> resolved = waitForResolvedTrades(ids);
        List<String> transactionsHashes = new ArrayList<>();
        for (Object trade : resolved) {
            if (!(trade instanceof Map)) {
                continue;
            }
            Object status = ((Map<?, ?>) trade).get("status");
            if (status != null && FAILED_TRADE_STATUS.equals(Js.str(status).toUpperCase())) {
                continue;
            }
            Object hash = ((Map<?, ?>) trade).get("transaction_hash");
            if (hash != null && Js.truthy(hash)) {
                transactionsHashes.add(Js.str(hash));
            }
        }
        if (transactionsHashes.isEmpty()) {
            return resp;
        }
        resp.put("transactionsHashes", new ArrayList<Object>(transactionsHashes));
        return resp;
    }

    private List<Object> waitForResolvedTrades(List<String> ids) throws Exception {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Set<String> requested = new LinkedHashSet<>(ids);
        long deadline = clock.nowMs() + RESOLVE_TRADES_TIMEOUT_MS;
        while (true) {
            List<String> pending = new ArrayList<>();
            for (String id : ids) {
                if (!resolved.containsKey(id)) {
                    pending.add(id);
                }
            }
            for (String id : pending) {
                List<Object> trades;
                try {
                    trades = tradesSource.getTradesPage(id);
                } catch (Exception e) {
                    trades = List.of();
                }
                if (trades == null) {
                    continue;
                }
                for (Object trade : trades) {
                    if (!(trade instanceof Map)) {
                        continue;
                    }
                    Object tid = ((Map<?, ?>) trade).get("id");
                    if (tid != null && isTradeResolved(trade) && requested.contains(Js.str(tid))) {
                        resolved.put(Js.str(tid), trade);
                    }
                }
            }
            boolean all = true;
            for (String id : ids) {
                if (!resolved.containsKey(id)) {
                    all = false;
                    break;
                }
            }
            if (all || clock.nowMs() >= deadline) {
                List<Object> out = new ArrayList<>();
                for (String id : ids) {
                    Object t = resolved.get(id);
                    if (t != null) {
                        out.add(t);
                    }
                }
                return out;
            }
            sleeper.sleep(RESOLVE_TRADES_POLL_INTERVAL_MS);
        }
    }

    private static boolean isTradeResolved(Object trade) {
        Object status = ((Map<?, ?>) trade).get("status");
        if (status != null && FAILED_TRADE_STATUS.equals(Js.str(status).toUpperCase())) {
            return true;
        }
        Object hash = ((Map<?, ?>) trade).get("transaction_hash");
        return hash != null && !Js.str(hash).isEmpty();
    }

    // ── 기본 HTTP 구현 (SDK GET 경로 재현) ──

    /** GET /version — 오류 응답이면 기본값 2 (SDK startVersionRequest 의미론). */
    private final class HttpVersionResolver implements VersionResolver {
        private Integer cached;

        @Override
        public int resolveVersion(boolean forceUpdate) {
            if (!forceUpdate && cached != null) {
                return cached;
            }
            int version = 2;
            try {
                RawHttp.Raw raw = transport.get(host + "/version", Map.of());
                if (raw.status >= 200 && raw.status < 300) {
                    Object parsed = codec.parse(raw.body);
                    if (parsed instanceof Map) {
                        Object v = ((Map<?, ?>) parsed).get("version");
                        if (v instanceof Number) {
                            version = ((Number) v).intValue();
                        }
                    }
                }
            } catch (Exception ignored) {
                // SDK: 오류 응답은 기본 버전 2로 떨어진다
            }
            cached = version;
            return version;
        }
    }

    /** SDK getTickSize/getNegRisk + 시장 정보 캐시 — GET 전용, L2 불필요 경로. */
    private final class HttpMarketInfoSource implements MarketInfoSource {
        private final Map<String, String> tickSizes = new LinkedHashMap<>();
        private final Map<String, Boolean> negRisk = new LinkedHashMap<>();
        private final Map<String, String> tokenConditionMap = new LinkedHashMap<>();
        private final Set<String> feeInfoTokens = new LinkedHashSet<>();

        @Override
        public void ensureMarketInfoCached(String tokenId) throws Exception {
            if (feeInfoTokens.contains(tokenId)) {
                return;
            }
            if (!tokenConditionMap.containsKey(tokenId)) {
                Object result = getJson(host + "/markets-by-token/" + tokenId);
                Object conditionId = result instanceof Map
                        ? ((Map<?, ?>) result).get("condition_id") : null;
                if (conditionId == null || !Js.truthy(conditionId)) {
                    throw new Exception("failed to resolve condition id for token " + tokenId);
                }
                tokenConditionMap.put(tokenId, Js.str(conditionId));
            }
            if (feeInfoTokens.contains(tokenId)) {
                return;
            }
            fetchClobMarketInfo(tokenConditionMap.get(tokenId));
        }

        private void fetchClobMarketInfo(String conditionId) throws Exception {
            Object result = getJson(host + "/clob-markets/" + conditionId);
            if (!(result instanceof Map) || ((Map<?, ?>) result).get("t") == null) {
                throw new Exception("failed to fetch market info for condition id " + conditionId);
            }
            Map<?, ?> r = (Map<?, ?>) result;
            Object tokens = r.get("t");
            if (tokens instanceof List) {
                for (Object token : (List<?>) tokens) {
                    if (!(token instanceof Map)) {
                        continue;
                    }
                    Object tid = ((Map<?, ?>) token).get("t");
                    if (tid == null) {
                        continue;
                    }
                    String tokenId = Js.str(tid);
                    tokenConditionMap.put(tokenId, conditionId);
                    Object mts = r.get("mts");
                    tickSizes.put(tokenId, mts == null ? null : Js.str(mts));
                    Object nr = r.get("nr");
                    negRisk.put(tokenId, nr != null && Js.truthy(nr));
                    feeInfoTokens.add(tokenId);
                }
            }
        }

        @Override
        public String getTickSize(String tokenId) throws Exception {
            String cached = tickSizes.get(tokenId);
            if (cached != null) {
                return cached;
            }
            if (tokenConditionMap.containsKey(tokenId)) {
                fetchClobMarketInfo(tokenConditionMap.get(tokenId));
                String v = tickSizes.get(tokenId);
                if (v != null) {
                    return v;
                }
            }
            Object result = getJson(host + "/tick-size?token_id=" + enc(tokenId));
            Object mts = result instanceof Map ? ((Map<?, ?>) result).get("minimum_tick_size") : null;
            String v = Js.str(mts);
            tickSizes.put(tokenId, v);
            return v;
        }

        @Override
        public boolean getNegRisk(String tokenId) throws Exception {
            Boolean cached = negRisk.get(tokenId);
            if (cached != null) {
                return cached;
            }
            if (tokenConditionMap.containsKey(tokenId)) {
                fetchClobMarketInfo(tokenConditionMap.get(tokenId));
                Boolean v = negRisk.get(tokenId);
                if (v != null) {
                    return v;
                }
            }
            Object result = getJson(host + "/neg-risk?token_id=" + enc(tokenId));
            Object nr = result instanceof Map ? ((Map<?, ?>) result).get("neg_risk") : null;
            boolean v = Js.truthy(nr);
            negRisk.put(tokenId, v);
            return v;
        }

        private Object getJson(String url) throws Exception {
            RawHttp.Raw raw = transport.get(url, Map.of());
            if (raw.status == 0) {
                // SDK http-helpers get은 전송 실패를 {error} 객체로 돌려준다 —
                // 호출부의 필드 접근이 실패로 이어지게 빈 맵이 아닌 오류를 던진다.
                throw new Exception(raw.error != null ? raw.error : "Network Error");
            }
            Object parsed = codec.parse(raw.body.isEmpty() ? "{}" : raw.body);
            if (raw.status >= 400) {
                // errorHandling 모양 — 필드가 없으므로 상위에서 형식 오류로 처리된다
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", parsed);
                err.put("status", (double) raw.status);
                return err;
            }
            return parsed;
        }
    }

    /** SDK getTrades({id}, only_first_page=true) — L2 GET /data/trades 한 페이지. */
    private final class HttpTradesSource implements TradesSource {
        @Override
        public List<Object> getTradesPage(String tradeId) throws Exception {
            Map<String, String> headers = createL2Headers("GET", "/data/trades", null);
            String url = host + "/data/trades?id=" + enc(tradeId) + "&next_cursor=MA==";
            RawHttp.Raw raw = transport.get(url, headers);
            if (raw.status == 0 || raw.status >= 400) {
                return List.of();
            }
            Object parsed = codec.parse(raw.body);
            if (parsed instanceof Map) {
                Object data = ((Map<?, ?>) parsed).get("data");
                if (data instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> l = (List<Object>) data;
                    return l;
                }
            }
            return List.of();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // 테스트·통합용 접근자
    public String host() { return host; }
    public Creds creds() { return creds; }
    public String funder() { return funder; }
}
