package com.roomedia.boringmech;

import com.roomedia.boringmech.crypto.Funder;
import com.roomedia.boringmech.crypto.PolyHmac;
import com.roomedia.boringmech.engine.BoughtReconcile;
import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.engine.NativeCycle;
import com.roomedia.boringmech.engine.PositionsFetcher;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.write.JsonCodec;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.OrderBridge;
import com.roomedia.boringmech.write.RawHttp;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NativeCycle.Net의 운영 구현 — engine.js fetchJson/rpcCall/CLOB SDK GET 의미론 재현.
 *
 * 규약이 두 가지로 갈린다:
 * - Data API·Gamma·GitHub (fetchJson): 비2xx면 "HTTP {status} {path}" 예외.
 * - CLOB SDK GET: 오류를 던지지 않고 {error, status} 모델로 돌려준다 (OrderBridge.shapeNativeResult).
 */
final class NativeNet implements NativeCycle.Net {

    static final String CLOB_HOST = "https://clob.polymarket.com";
    private static final String GAMMA_API = "https://gamma-api.polymarket.com/markets";
    private static final String DATA_API_V2 = "https://data-api.polymarket.com/v2";
    private static final String PUSD = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB";
    private static final String[] POLYGON_RPCS = {
            "https://polygon-bor-rpc.publicnode.com",
            "https://polygon.drpc.org",
            "https://1rpc.io/matic",
    };

    static final JsonCodec CODEC = new JsonCodec() {
        @Override
        public Object parse(String text) {
            return JsonText.parse(text);
        }

        @Override
        public String stringify(Object model) {
            return ModelJson.stringify(model);
        }
    };

    private final RawHttp.Transport transport;
    private final OrderBridge orderBridge;
    private final String funder;
    private final Bridge.Creds creds;
    private Runnable progressBeat;

    /** 페이지네이션 진행 신호 — 단계 데드라인이 느린 조회를 동결로 오인하지 않게 한다. */
    void setProgressBeat(Runnable beat) {
        this.progressBeat = beat;
    }

    private void beat() {
        if (progressBeat == null) {
            return;
        }
        try {
            progressBeat.run();
        } catch (Exception ignored) {
            // 진행 신호 실패가 조회를 깨면 안 된다.
        }
    }

    NativeNet(RawHttp.Transport transport, String funder, Bridge.Creds creds) {
        this.transport = transport;
        this.orderBridge = new OrderBridge(transport, CODEC, ms -> {
        });
        this.funder = funder;
        this.creds = creds;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── fetchJson (Data API·Gamma·GitHub) ───────────────────────────────────

    private Object fetchJsonModel(String url) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "boringmech-app/0.3");
        RawHttp.Raw r = transport.get(url, headers);
        if (r.status == 0) {
            throw new Exception(r.error != null ? r.error : "네트워크 오류");
        }
        if (r.status < 200 || r.status >= 300) {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            throw new Exception("HTTP " + r.status + " " + path);
        }
        return CODEC.parse(r.body == null ? "" : r.body);
    }

    @Override
    public List<Object> fetchPositions(String status) throws Exception {
        PositionsFetcher.PageSource source = (f, st, cursor) -> {
            StringBuilder u = new StringBuilder(DATA_API_V2)
                    .append("/positions?user=").append(enc(f)).append("&limit=100");
            if (st != null) {
                u.append("&status=").append(enc(st));
            }
            if (cursor != null) {
                u.append("&cursor=").append(enc(cursor));
            }
            return fetchJsonModel(u.toString());
        };
        return PositionsFetcher.fetchPositions(funder, status, source, this::beat);
    }

    @Override
    public double fetchPositionsValue() throws Exception {
        Object model = fetchJsonModel(DATA_API_V2 + "/value?user=" + enc(funder));
        double v = Js.num(Js.field(Js.field(model, "data"), "value"));
        if (!Double.isFinite(v)) {
            throw new Exception("포지션 평가액 응답 형식 오류");
        }
        return v;
    }

    @Override
    public BoughtReconcile.BuySlugs fetchBuySlugs() throws Exception {
        return BoughtReconcile.fetchBuySlugs(funder, (f, cursor) -> {
            StringBuilder u = new StringBuilder(DATA_API_V2)
                    .append("/activity?user=").append(enc(f)).append("&limit=100");
            if (cursor != null) {
                u.append("&cursor=").append(enc(cursor));
            }
            return fetchJsonModel(u.toString());
        });
    }

    @Override
    public List<Object> fetchMarkets() throws Exception {
        List<Object> markets = new ArrayList<>();
        int offset = 0;
        while (markets.size() < 1000) {
            Object page = fetchJsonModel(GAMMA_API
                    + "?active=true&closed=false&order=liquidity&ascending=false&limit=100&offset=" + offset);
            if (!(page instanceof List) || ((List<?>) page).isEmpty()) {
                break;
            }
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) page;
            markets.addAll(rows);
            beat();
            if (rows.size() < 100) {
                break;
            }
            offset += 100;
        }
        return markets.size() > 1000 ? new ArrayList<>(markets.subList(0, 1000)) : markets;
    }

    @Override
    public Object gammaPageBySlug(String slug) throws Exception {
        return fetchJsonModel(GAMMA_API + "?slug=" + enc(slug));
    }

    private static final String GAMMA_EVENTS_API = "https://gamma-api.polymarket.com/events";

    @Override
    public List<Object> fetchTempEvents(List<String> eventSlugs) {
        List<Object> events = new ArrayList<>();
        int failures = 0;
        for (String slug : eventSlugs) {
            try {
                Object page = fetchJsonModel(GAMMA_EVENTS_API + "?slug=" + enc(slug));
                if (page instanceof List && !((List<?>) page).isEmpty()) {
                    events.add(((List<?>) page).get(0));
                }
            } catch (Exception ignored) {
                // 도시별 실패는 그 도시만 건너뛴다 (JS fetchTempEvents와 같다).
                failures++;
            }
            beat();
        }
        // 전 도시 실패는 빈 목록과 구분한다 — JS는 전부 실패한 조회를
        // 캐시하지 않으므로, 네이티브도 null로 알려 호출자가 캐시하지
        // 않게 한다 (감사 2026-11-20 패리티 수리).
        if (!eventSlugs.isEmpty() && failures == eventSlugs.size()) {
            return null;
        }
        return events;
    }

    @Override
    public NativeCycle.MarketContext fetchMarketContext(String conditionId) throws Exception {
        Object page = fetchJsonModel(GAMMA_API + "?condition_ids=" + enc(conditionId) + "&closed=true");
        if (!(page instanceof List) || ((List<?>) page).isEmpty()) {
            return null;
        }
        Object m = ((List<?>) page).get(0);
        List<String> tokenIds = null;
        Object rawTokens = Js.field(m, "clobTokenIds");
        if (rawTokens instanceof String) {
            try {
                Object parsed = CODEC.parse((String) rawTokens);
                if (parsed instanceof List && ((List<?>) parsed).size() >= 2) {
                    tokenIds = new ArrayList<>();
                    for (Object t : (List<?>) parsed) {
                        tokenIds.add(Js.str(t));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        List<String> positionIds = null;
        Object rawPos = Js.field(m, "positionIds");
        if (rawPos instanceof List) {
            positionIds = new ArrayList<>();
            for (Object t : (List<?>) rawPos) {
                positionIds.add(Js.str(t));
            }
        }
        Object version = Js.field(m, "version");
        return new NativeCycle.MarketContext(
                Js.truthy(Js.field(m, "negRisk")),
                version instanceof String ? (String) version : null,
                positionIds, tokenIds);
    }

    @Override
    public Object fetchLatestReleaseJson() throws Exception {
        return fetchJsonModel("https://api.github.com/repos/roomedia/boringmech/releases/latest");
    }

    // ── CLOB SDK GET (오류는 모델로) ────────────────────────────────────────

    private Map<String, String> l2Headers(String method, String path) {
        long ts = System.currentTimeMillis() / 1000;
        String sig = PolyHmac.buildSignature(creds.secret, ts, method, path, null);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "boringmech-app/0.3");
        h.put("POLY_ADDRESS", creds.eoa);
        h.put("POLY_SIGNATURE", sig);
        h.put("POLY_TIMESTAMP", String.valueOf(ts));
        h.put("POLY_API_KEY", creds.key);
        h.put("POLY_PASSPHRASE", creds.passphrase);
        return h;
    }

    private Object clobSdkGet(String path, String query, Map<String, String> headers) {
        String url = CLOB_HOST + path + (query == null ? "" : "?" + query);
        RawHttp.Raw raw = transport.get(url, headers);
        return orderBridge.shapeNativeResult(raw);
    }

    private static Map<String, String> uaOnly() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "boringmech-app/0.3");
        return h;
    }

    @Override
    public double clobCash() {
        Object model = clobSdkGet("/balance-allowance", "asset_type=COLLATERAL&signature_type=3",
                l2Headers("GET", "/balance-allowance"));
        Object bal = Js.field(model, "balance");
        String balStr = Js.truthy(bal) ? Js.str(bal) : "0";
        return Js.round2(new BigInteger(balStr.trim()).doubleValue() / 1e6);
    }

    @Override
    public Object orderBook(String tokenId) {
        return clobSdkGet("/book", "token_id=" + enc(tokenId), uaOnly());
    }

    @Override
    public double tickSize(String tokenId) throws Exception {
        Object model = clobSdkGet("/tick-size", "token_id=" + enc(tokenId), uaOnly());
        Object ts = Js.field(model, "minimum_tick_size");
        if (!Js.truthy(ts)) {
            throw new Exception("틱 사이즈 조회 실패");
        }
        return Js.num(ts);
    }

    @Override
    public BigInteger conditionalBalance(String tokenId) {
        Object model = clobSdkGet("/balance-allowance",
                "asset_type=CONDITIONAL&token_id=" + enc(tokenId) + "&signature_type=3",
                l2Headers("GET", "/balance-allowance"));
        Object bal = Js.field(model, "balance");
        return Js.truthy(bal) ? new BigInteger(Js.str(bal).trim()) : BigInteger.ZERO;
    }

    // ── Polygon RPC ─────────────────────────────────────────────────────────

    Object rpcCall(String method, List<Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1.0);
        body.put("method", method);
        body.put("params", params);
        String bodyText = CODEC.stringify(body);
        Exception last = null;
        for (String url : POLYGON_RPCS) {
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", "application/json");
                RawHttp.Raw r = transport.post(url, headers, bodyText);
                if (r.status == 0) {
                    throw new Exception(r.error != null ? r.error : "RPC 네트워크 오류");
                }
                Object data = CODEC.parse(r.body == null ? "" : r.body);
                Object err = Js.field(data, "error");
                if (Js.truthy(err)) {
                    Object m = Js.field(err, "message");
                    throw new Exception(Js.truthy(m) ? Js.str(m) : "rpc error");
                }
                return Js.field(data, "result");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("모든 Polygon RPC 실패");
    }

    private static String pad64(String hexNoPrefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = hexNoPrefix.length(); i < 64; i++) {
            sb.append('0');
        }
        return sb.append(hexNoPrefix).toString();
    }

    private static BigInteger parseUint(Object result) {
        if (!(result instanceof String)) {
            throw new IllegalStateException("RPC 결과 형식 오류");
        }
        String hex = (String) result;
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        return hex.isEmpty() ? BigInteger.ZERO : new BigInteger(hex, 16);
    }

    private static BigInteger parseTokenId(String tokenId) {
        if (tokenId.startsWith("0x") || tokenId.startsWith("0X")) {
            return new BigInteger(tokenId.substring(2), 16);
        }
        return new BigInteger(tokenId);
    }

    @Override
    public BigInteger erc1155Balance(String token, String owner, String tokenId) throws Exception {
        String ownerHex = owner.startsWith("0x") ? owner.substring(2) : owner;
        String data = "0x00fdd58e" + pad64(ownerHex.toLowerCase())
                + pad64(parseTokenId(tokenId).toString(16));
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("to", token);
        call.put("data", data);
        List<Object> params = new ArrayList<>();
        params.add(call);
        params.add("latest");
        return parseUint(rpcCall("eth_call", params));
    }

    double pusdBalance(String address) throws Exception {
        String addrHex = address.startsWith("0x") ? address.substring(2) : address;
        String data = "0x70a08231" + pad64(addrHex.toLowerCase());
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("to", PUSD);
        call.put("data", data);
        List<Object> params = new ArrayList<>();
        params.add(call);
        params.add("latest");
        return parseUint(rpcCall("eth_call", params)).doubleValue() / 1e6;
    }

    String getCode(String address) throws Exception {
        List<Object> params = new ArrayList<>();
        params.add(address);
        params.add("latest");
        Object result = rpcCall("eth_getCode", params);
        return result instanceof String ? (String) result : null;
    }

    /** detectFunder 이식 — 비콘·UUPS 파생 + getCode 존재 + pUSD 잔고 타이브레이크. */
    String detectFunder(String eoa) throws Exception {
        List<String> candidates = new ArrayList<>();
        candidates.add(Funder.deriveBeacon(eoa));
        candidates.add(Funder.deriveUups(eoa));
        List<String> deployed = new ArrayList<>();
        for (String c : candidates) {
            String code = getCode(c);
            if (code != null && code.length() > 2) {
                deployed.add(c);
            }
        }
        if (deployed.size() == 1) {
            return deployed.get(0);
        }
        if (deployed.size() > 1) {
            String best = null;
            double bestBal = -1;
            for (String c : deployed) {
                double bal = pusdBalance(c);
                if (bal > bestBal) {
                    bestBal = bal;
                    best = c;
                }
            }
            if (best != null && bestBal > 0) {
                return best;
            }
        }
        return null;
    }
}
