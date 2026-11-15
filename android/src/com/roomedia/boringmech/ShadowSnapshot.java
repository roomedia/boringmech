package com.roomedia.boringmech;

import android.content.Context;
import android.content.SharedPreferences;

import com.roomedia.boringmech.crypto.PolyHmac;
import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.engine.JsonModel;
import com.roomedia.boringmech.engine.PositionBook;
import com.roomedia.boringmech.engine.PositionsFetcher;

import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 네이티브 섀도우 스냅샷 (2단계 — 읽기 전용 대조).
 *
 * engine.js snapshot()과 같은 조회(Data API positions/value + CLOB
 * balance-allowance)를 네이티브로 수행해 PositionBook으로 같은 스냅샷을
 * 만들고, JS가 남긴 스냅샷 캐시(bm_engine.snap_cache)와 핵심 필드를
 * 비교해 bm_shadow에 누적 기록한다. GET만 하며 제출·주문은 절대 없다.
 * 실패는 전부 이 클래스 안에서 삼켜 기록으로만 남긴다 — 호출자(서비스
 * 섀도우 루프)와 JS 엔진에 영향을 주지 않는다.
 */
final class ShadowSnapshot {
    private ShadowSnapshot() {}

    private static final String DATA_API_V2 = "https://data-api.polymarket.com/v2";
    private static final String CLOB_HOST = "https://clob.polymarket.com";
    private static final String DURABLE_PREFS = "bm_durable";
    // 금액 비교 허용 오차 — round2 단위(0.01) + 조회 시점 차의 미세 변동
    private static final double MONEY_TOL = 0.011;

    static void runOnce(Context ctx) {
        try {
            runInner(ctx);
        } catch (Throwable t) {
            EngineService.recordShadowError(ctx, shortMsg(t));
        }
    }

    private static void runInner(Context ctx) throws Exception {
        String funder = EngineService.getFunder(ctx);
        if (funder == null) {
            throw new Exception("funder 미러 없음 — JS boot/link 대기");
        }
        Bridge.Creds creds = Bridge.loadShadowCreds(ctx);
        if (creds == null) {
            throw new Exception("creds 미러 없음 — JS boot/link 대기");
        }

        PositionsFetcher.PageSource source = (f, status, cursor) -> {
            StringBuilder u = new StringBuilder(DATA_API_V2)
                    .append("/positions?user=").append(enc(f)).append("&limit=100");
            if (status != null) {
                u.append("&status=").append(enc(status));
            }
            if (cursor != null) {
                u.append("&cursor=").append(enc(cursor));
            }
            return JsonModel.fromJson(new JSONObject(httpGet(u.toString(), uaHeaders())));
        };

        double cash = clobCash(creds);
        List<Object> positions = PositionsFetcher.fetchPositions(funder, null, source);
        double positionsValue = Js.round2(fetchPositionsValue(funder));
        double equity = Js.round2(cash + positionsValue);

        List<Object> winRows = null;
        List<Object> lostRows = null;
        List<Object> closedRows = null;
        try {
            winRows = PositionsFetcher.fetchPositions(funder, "REDEEMABLE", source);
            lostRows = PositionsFetcher.fetchPositions(funder, "REDEEMABLE_LOST", source);
            closedRows = PositionsFetcher.fetchPositions(funder, "CLOSED", source);
        } catch (Exception e) {
            // JS snapshot()과 동일 — 정산 집계를 못 읽으면 셋 다 null로 남긴다
            winRows = null;
            lostRows = null;
            closedRows = null;
        }

        PositionBook.Book book = PositionBook.summarizeBook(
                positions, winRows, lostRows, closedRows,
                stopLossRecords(ctx), stopLossAttemptedTokens(ctx));
        PositionBook.Sizing sizing = PositionBook.sizing(cash, equity, book.openCount);

        JSONObject diff = compare(ctx, cash, positionsValue, equity, book, sizing);
        EngineService.recordShadowRun(ctx, diff.length() == 0,
                diff.length() == 0 ? "" : diff.toString());
    }

    // engine.js clobCash — SDK getBalanceAllowance(COLLATERAL)의 L2 GET 재현.
    // HMAC 대상 경로는 쿼리 없는 "/balance-allowance" (SDK createL2Headers 규약).
    private static double clobCash(Bridge.Creds creds) throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        String sig = PolyHmac.buildSignature(
                creds.secret, ts, "GET", "/balance-allowance", null);
        JSONObject h = new JSONObject();
        h.put("POLY_ADDRESS", creds.eoa);
        h.put("POLY_SIGNATURE", sig);
        h.put("POLY_TIMESTAMP", String.valueOf(ts));
        h.put("POLY_API_KEY", creds.key);
        h.put("POLY_PASSPHRASE", creds.passphrase);
        h.put("User-Agent", "boringmech-app/0.3");
        String body = httpGet(
                CLOB_HOST + "/balance-allowance?asset_type=COLLATERAL&signature_type=3",
                h.toString());
        JSONObject o = new JSONObject(body);
        String bal = o.optString("balance", "0");
        if (bal.isEmpty()) {
            bal = "0"; // JS: bal.balance || "0"
        }
        return Js.round2(new BigInteger(bal.trim()).doubleValue() / 1e6);
    }

    // engine.js fetchPositionsValue — 값이 숫자가 아니면 0으로 삼키지 않고 실패.
    private static double fetchPositionsValue(String funder) throws Exception {
        String body = httpGet(
                DATA_API_V2 + "/value?user=" + enc(funder), uaHeaders());
        Object model = JsonModel.fromJson(new JSONObject(body));
        double v = Js.num(Js.field(Js.field(model, "data"), "value"));
        if (!Double.isFinite(v)) {
            throw new Exception("포지션 평가액 응답 형식 오류");
        }
        return v;
    }

    // Bridge.getSync와 같은 전송(타임아웃 동일)의 응답 봉투를 풀어,
    // 200이 아니면 JS fetchJson처럼 오류로 던진다.
    private static String httpGet(String url, String headersJson) throws Exception {
        JSONObject env = new JSONObject(Bridge.getSyncRaw(url, headersJson));
        int status = env.optInt("status", 0);
        if (status != 200) {
            String path = url.split("\\?")[0];
            throw new Exception("HTTP " + status + " " + path);
        }
        return env.optString("body", "");
    }

    private static String uaHeaders() throws Exception {
        JSONObject h = new JSONObject();
        h.put("User-Agent", "boringmech-app/0.3");
        return h.toString();
    }

    // ── bm_durable에서 손절 원장·시도 토큰 읽기 (JS stopLossRecords 등과 동일 필터) ──

    private static String durable(Context ctx, String key) {
        SharedPreferences p = ctx.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE);
        return p.getString(key, null);
    }

    private static List<Object> stopLossRecords(Context ctx) {
        List<Object> out = new ArrayList<>();
        out.addAll(parseStopLossList(durable(ctx, "bm_stoploss_archive")));
        out.addAll(parseStopLossList(durable(ctx, "bm_stoploss")));
        return out;
    }

    private static List<Object> parseStopLossList(String raw) {
        List<Object> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        try {
            Object model = JsonModel.fromJson(new org.json.JSONArray(raw));
            if (!(model instanceof List)) {
                return out;
            }
            for (Object r : (List<?>) model) {
                if (r instanceof Map
                        && Js.field(r, "token_id") instanceof String
                        && Double.isFinite(Js.fieldNum(r, "realized_pnl"))) {
                    out.add(r);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Set<Object> stopLossAttemptedTokens(Context ctx) {
        Set<Object> out = new HashSet<>();
        String raw = durable(ctx, "bm_stoploss_attempted");
        if (raw == null) {
            return out;
        }
        try {
            Object model = JsonModel.fromJson(new org.json.JSONArray(raw));
            if (model instanceof List) {
                for (Object t : (List<?>) model) {
                    if (t instanceof String) {
                        out.add(t);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ── JS 스냅샷 캐시와 핵심 필드 비교 ──

    private static JSONObject compare(Context ctx, double cash, double positionsValue,
                                      double equity, PositionBook.Book book,
                                      PositionBook.Sizing sizing) throws Exception {
        JSONObject diff = new JSONObject();
        JSONObject js = null;
        String jsJson = EngineService.getSnapCache(ctx);
        if (jsJson != null) {
            try {
                js = new JSONObject(jsJson);
            } catch (Exception ignored) {
                js = null;
            }
        }
        if (js == null) {
            JSONObject pair = new JSONObject();
            pair.put("js", "없음");
            pair.put("native", "있음");
            diff.put("snapCache", pair);
            return diff;
        }
        cmpMoney(diff, js, "cash", cash);
        cmpMoney(diff, js, "positionsValue", positionsValue);
        cmpMoney(diff, js, "equity", equity);
        cmpExact(diff, js, "openCount", book.openCount);
        cmpNullableInt(diff, js, "settledCount", book.settledCount);
        cmpNullableInt(diff, js, "settledWins", book.settledWins);
        cmpNullableInt(diff, js, "settledLosses", book.settledLosses);
        cmpNullableMoney(diff, js, "settledRealizedPnl", book.settledRealizedPnl);
        cmpMoney(diff, js, "bet", sizing.bet);
        cmpExact(diff, js, "slots", sizing.slots);
        return diff;
    }

    private static void putDiff(JSONObject diff, String field, Object jsVal, Object nativeVal)
            throws Exception {
        JSONObject pair = new JSONObject();
        pair.put("js", jsVal == null ? JSONObject.NULL : jsVal);
        pair.put("native", nativeVal == null ? JSONObject.NULL : nativeVal);
        diff.put(field, pair);
    }

    private static void cmpMoney(JSONObject diff, JSONObject js, String field, double nativeVal)
            throws Exception {
        if (!js.has(field) || js.isNull(field)) {
            putDiff(diff, field, null, nativeVal);
            return;
        }
        double jsVal = js.optDouble(field, Double.NaN);
        if (!Double.isFinite(jsVal) || Math.abs(jsVal - nativeVal) > MONEY_TOL) {
            putDiff(diff, field, jsVal, nativeVal);
        }
    }

    private static void cmpExact(JSONObject diff, JSONObject js, String field, double nativeVal)
            throws Exception {
        if (!js.has(field) || js.isNull(field)) {
            putDiff(diff, field, null, nativeVal);
            return;
        }
        double jsVal = js.optDouble(field, Double.NaN);
        if (jsVal != nativeVal) {
            putDiff(diff, field, jsVal, nativeVal);
        }
    }

    private static void cmpNullableInt(JSONObject diff, JSONObject js, String field,
                                       Integer nativeVal) throws Exception {
        boolean jsNull = !js.has(field) || js.isNull(field);
        if (jsNull && nativeVal == null) {
            return;
        }
        if (jsNull || nativeVal == null) {
            putDiff(diff, field,
                    jsNull ? null : js.optDouble(field),
                    nativeVal == null ? null : nativeVal.doubleValue());
            return;
        }
        double jsVal = js.optDouble(field, Double.NaN);
        if (jsVal != nativeVal.doubleValue()) {
            putDiff(diff, field, jsVal, nativeVal.doubleValue());
        }
    }

    private static void cmpNullableMoney(JSONObject diff, JSONObject js, String field,
                                         Double nativeVal) throws Exception {
        boolean jsNull = !js.has(field) || js.isNull(field);
        if (jsNull && nativeVal == null) {
            return;
        }
        if (jsNull || nativeVal == null) {
            putDiff(diff, field,
                    jsNull ? null : js.optDouble(field),
                    nativeVal);
            return;
        }
        double jsVal = js.optDouble(field, Double.NaN);
        if (!Double.isFinite(jsVal) || Math.abs(jsVal - nativeVal) > MONEY_TOL) {
            putDiff(diff, field, jsVal, nativeVal);
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 160 ? m.substring(0, 160) : m;
    }
}
