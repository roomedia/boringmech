package com.roomedia.boringmech.write;

import com.roomedia.boringmech.engine.Js;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 주문 경로 측정의 순수 로직 — 요청 파싱·시장 선정·체결/취소 판정·통계.
 *
 * 안전 경계는 코드로 고정한다: 테스트 가격 TEST_PRICE는 설정으로 바꿀
 * 수 없는 상수이고, 수량은 대상 시장의 최소 주문 수량 그대로다 (금액은
 * 최소 수량 × 0.01 수준). 이 클래스는 장부(bm_bought·원장·포지션)에
 * 닿는 API를 아예 갖지 않는다 — 테스트 주문이 운영 기록으로 새는
 * 경로는 구조적으로 없다.
 *
 * 통신 규약은 수동 매도와 같다: 뷰어가 REQUEST_KEY에 {ts}를 쓰면
 * 엔진이 idle 구간에서 한 번만 읽어 소비한다.
 */
public final class OrderPathTest {
    private OrderPathTest() {}

    public static final String REQUEST_KEY = "bm_orderpath_test_req";
    /** 마지막 측정 결과 요약 — 뷰어 상태 줄 표시 전용 (판정에 쓰지 않는다). */
    public static final String STATUS_KEY = "bm_orderpath_test_status";

    /** 체결 불가 테스트 가격 — 고정 상수. 설정·요청으로 바꿀 수 없다. */
    public static final double TEST_PRICE = 0.01;
    /** 동시성 구간에서 연속 제출할 시장 수. */
    public static final int CONCURRENCY_MARKETS = 5;
    /** 요청 신선도 상한 — 수동 매도와 같은 600초. */
    public static final long MAX_REQUEST_AGE_MS = 600_000L;

    /** 뷰어 측정 요청 1건. */
    public static final class Request {
        public final long tsMs;

        Request(long tsMs) {
            this.tsMs = tsMs;
        }
    }

    /** 요청 모델 검증 — ts가 유한 양수일 때만 유효한 요청으로 본다. */
    public static Request parseRequest(Object model) {
        if (!(model instanceof Map)) {
            return null;
        }
        double ts = Js.fieldNum(model, "ts");
        if (!Double.isFinite(ts) || ts <= 0) {
            return null;
        }
        return new Request((long) ts);
    }

    public static boolean isStale(Request req, long nowMs) {
        return nowMs - req.tsMs > MAX_REQUEST_AGE_MS;
    }

    // ── 시장 선정 ──

    /** 측정 대상 시장 — 토큰 지정 방식은 검증기(CandidateVerifier)와 같다. */
    public static final class Target {
        public final String slug;
        /** clobTokenIds 경로일 때만 — v2 positionIds 경로는 positionId를 쓴다. */
        public final String tokenId;
        public final String positionId;
        public final double volume;

        Target(String slug, String tokenId, String positionId, double volume) {
            this.slug = slug;
            this.tokenId = tokenId;
            this.positionId = positionId;
            this.volume = volume;
        }

        /** 로그·잠금 키용 식별자. */
        public String assetKey() {
            return positionId != null ? positionId : tokenId;
        }
    }

    /**
     * Gamma 마켓 목록에서 유동성 순으로 측정 대상을 고른다 — 활성이고
     * 닫히지 않았고 주문을 받는 시장만, 거래량 내림차순. 토큰은 검증기와
     * 같은 규칙: v2 마켓은 positionIds, 그 외는 clobTokenIds 파싱.
     * 첫 번째 outcome(인덱스 0)만 쓴다.
     */
    public static List<Target> pickTargets(List<Object> markets, int count) {
        List<Target> out = new ArrayList<>();
        if (markets == null) {
            return out;
        }
        for (Object o : markets) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            if (!Js.truthy(Js.field(m, "active")) || Js.truthy(Js.field(m, "closed"))) {
                continue;
            }
            if (Boolean.FALSE.equals(Js.field(m, "acceptingOrders"))) {
                continue;
            }
            String tokenId = null;
            String positionId = null;
            Object posIds = Js.field(m, "positionIds");
            if ("v2".equals(Js.str(Js.field(m, "version")))
                    && posIds instanceof List && !((List<?>) posIds).isEmpty()) {
                positionId = Js.str(((List<?>) posIds).get(0));
            } else {
                Object raw = Js.field(m, "clobTokenIds");
                List<Object> ids = null;
                if (raw instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> l = (List<Object>) raw;
                    ids = l;
                } else if (raw instanceof String) {
                    try {
                        Object parsed = com.roomedia.boringmech.engine.JsonText.parse((String) raw);
                        if (parsed instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> l = (List<Object>) parsed;
                            ids = l;
                        }
                    } catch (Exception ignored) {
                        ids = null;
                    }
                }
                if (ids != null && !ids.isEmpty()) {
                    tokenId = Js.str(ids.get(0));
                }
            }
            String asset = positionId != null ? positionId : tokenId;
            if (asset == null || asset.isEmpty()) {
                continue;
            }
            double volume = Js.numOrZero(Js.field(m, "volume"));
            if (!Double.isFinite(volume)) {
                volume = 0;
            }
            Object slugRaw = Js.field(m, "slug");
            String slug = slugRaw instanceof String ? (String) slugRaw : asset;
            out.add(new Target(slug, tokenId, positionId, volume));
        }
        out.sort((a, b) -> Double.compare(b.volume, a.volume));
        if (out.size() > count) {
            return new ArrayList<>(out.subList(0, count));
        }
        return out;
    }

    /**
     * 테스트 수량 — 오더북 min_order_size 그대로. 값이 없거나 0이면
     * NaN을 돌려줘 호출자가 그 시장을 건너뛰게 한다 (추정으로 크기를
     * 만들지 않는다).
     */
    public static double planSize(double minOrderSize) {
        return Double.isFinite(minOrderSize) && minOrderSize > 0 ? minOrderSize : Double.NaN;
    }

    // ── 응답 판정 ──

    /** 제출 응답의 status 문자열 (없으면 빈 문자열, 소문자). */
    public static String responseStatus(Object resp) {
        Object st = Js.field(resp, "status");
        return (st == Js.UNDEFINED || st == null) ? "" : Js.str(st).toLowerCase(java.util.Locale.ROOT);
    }

    /** 제출 응답의 주문 ID (없으면 null). */
    public static String orderIdOf(Object resp) {
        Object id = Js.field(resp, "orderID");
        return (id == Js.UNDEFINED || id == null) ? null : Js.str(id);
    }

    /**
     * 체결 감지 — status가 matched/filled면 무조건 체결로 본다.
     * 그 외에는 JudgeFill의 체결 증거 판정을 그대로 쓴다.
     */
    public static boolean isFillResponse(Object resp) {
        String st = responseStatus(resp);
        if (st.equals("matched") || st.equals("filled")) {
            return true;
        }
        return JudgeFill.judgeFill(resp).filled;
    }

    /** 지연 접수 — 체결은 아니지만 뒤늦게 체결될 수 있어 즉시 취소·확인이 필요하다. */
    public static boolean isDelayedResponse(Object resp) {
        return responseStatus(resp).equals("delayed");
    }

    /** 취소 응답이 이 주문의 취소를 확정했는가 ({canceled: [...]} 포함 여부). */
    public static boolean cancelConfirmed(Object resp, String orderId) {
        Object canceled = Js.field(resp, "canceled");
        if (canceled instanceof List) {
            for (Object id : (List<?>) canceled) {
                if (orderId.equals(Js.str(id))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 취소 거부 사유 ({not_canceled: {id: 사유}}) — 없으면 null. */
    public static String cancelRefusal(Object resp, String orderId) {
        Object nc = Js.field(resp, "not_canceled");
        if (nc instanceof Map && ((Map<?, ?>) nc).containsKey(orderId)) {
            Object reason = ((Map<?, ?>) nc).get(orderId);
            return reason == null ? "" : Js.str(reason);
        }
        return null;
    }

    /** 열린 주문 목록에 이 주문이 아직 남아 있는가 (행의 id 필드 대조). */
    public static boolean openOrderPresent(List<Object> openOrders, String orderId) {
        if (openOrders == null) {
            return false;
        }
        for (Object row : openOrders) {
            Object id = Js.field(row, "id");
            if (id != Js.UNDEFINED && id != null && orderId.equals(Js.str(id))) {
                return true;
            }
        }
        return false;
    }

    // ── 왕복 시간 통계 ──

    /** {건수, 중앙값, p90, 최대} — 중앙값은 정렬 후 하위 중앙, p90은 nearest-rank. */
    public static long[] summarizeMs(List<Long> samples) {
        if (samples == null || samples.isEmpty()) {
            return new long[]{0, 0, 0, 0};
        }
        List<Long> s = new ArrayList<>(samples);
        Collections.sort(s);
        long median = s.get((s.size() - 1) / 2);
        int p90idx = (int) Math.ceil(0.9 * s.size()) - 1;
        long p90 = s.get(Math.max(0, p90idx));
        long max = s.get(s.size() - 1);
        return new long[]{s.size(), median, p90, max};
    }
}
