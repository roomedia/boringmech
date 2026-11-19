package com.roomedia.boringmech.write;

import com.roomedia.boringmech.engine.Js;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 수동 매도의 순수 로직 — 뷰어 요청 파싱·신선도 판정·수수료율 매핑·원장 목록 연산.
 *
 * 통신 규약: 뷰어는 확인 팝업의 "매도하기"를 눌렀을 때만 bm_durable의
 * REQUEST_KEY에 요청 JSON({token_id, slug, ts})을 쓴다. 엔진은 사이클에서
 * 요청을 한 번만 읽어 소비(키를 비움)한 뒤 처리하므로, 같은 요청이 두 번
 * 제출되는 경로는 구조적으로 없다. 오래된 요청은 가격이 완전히 바뀐
 * 상태일 수 있어 신선도 상한을 넘기면 버린다.
 *
 * 수수료율: Polymarket 테이커 수수료는 마켓 카테고리별로 다르고, Gamma
 * 마켓의 feeType 접두사가 그 카테고리를 알려준다 (ScanFilter가 sports
 * 판정에 쓰는 것과 같은 필드). 요율표는 확정 명세 기준 — 크립토 0.07,
 * 스포츠·기타 0.05, 정치·금융·테크 0.04, 지정학 0. feeType이 없으면
 * 판정 불가(null)이고, 표시는 보수적 상한(CONSERVATIVE_FEE_RATE)으로
 * "약" 표기를 붙인다.
 */
public final class ManualSell {
    private ManualSell() {}

    public static final String REQUEST_KEY = "bm_manual_sell_req";
    public static final String LEDGER_KEY = "bm_manual_sell";
    public static final String ATTEMPTED_KEY = "bm_manual_sell_attempted";
    /**
     * 마지막 수동 매도 요청의 처리 결과 — 엔진이 종단 결과마다 JSON
     * ({ts, slug, outcome, detail})으로 남기고 뷰어가 한 줄로 보여준다.
     * 만료처럼 로그에만 남으면 사용자가 "조용한 실패"로 겪는 경로를
     * 막기 위한 표시 전용 기록이다 (판정에는 쓰지 않는다).
     */
    public static final String STATUS_KEY = "bm_manual_sell_status";

    /** 요청 신선도 상한 — 사이클 주기(60초)의 10배. 넘긴 요청은 버린다. */
    public static final long MAX_REQUEST_AGE_MS = 600_000L;
    public static final int LEDGER_CAP = 200;
    public static final int ATTEMPTED_CAP = 200;
    /** 카테고리 판정 불가 시 예상 수수료 계산에 쓰는 보수적 상한 요율. */
    public static final double CONSERVATIVE_FEE_RATE = 0.07;

    /** 뷰어 수동 매도 요청 1건. */
    public static final class Request {
        public final String tokenId;
        public final String slug;
        public final long tsMs;

        Request(String tokenId, String slug, long tsMs) {
            this.tokenId = tokenId;
            this.slug = slug;
            this.tsMs = tsMs;
        }
    }

    /**
     * 요청 모델 검증 — token_id가 비어 있지 않은 문자열이고 ts가 유한
     * 숫자일 때만 유효한 요청으로 본다. slug는 로그용이라 없어도 된다.
     */
    public static Request parseRequest(Object model) {
        if (!(model instanceof Map)) {
            return null;
        }
        Object tokenRaw = Js.field(model, "token_id");
        if (!(tokenRaw instanceof String) || ((String) tokenRaw).isEmpty()) {
            return null;
        }
        double ts = Js.fieldNum(model, "ts");
        if (!Double.isFinite(ts) || ts <= 0) {
            return null;
        }
        Object slugRaw = Js.field(model, "slug");
        String slug = slugRaw instanceof String ? (String) slugRaw : "";
        return new Request((String) tokenRaw, slug, (long) ts);
    }

    /** 신선도 판정 — 상한을 넘긴 요청은 실행하지 않는다. */
    public static boolean isStale(Request req, long nowMs) {
        return nowMs - req.tsMs > MAX_REQUEST_AGE_MS;
    }

    /**
     * Gamma feeType → 테이커 수수료율. 명세 요율표에 없는 카테고리는
     * "기타" 0.05로 본다. feeType 자체가 없으면 판정 불가(null).
     */
    public static Double feeRateForFeeType(String feeType) {
        if (feeType == null || feeType.isEmpty()) {
            return null;
        }
        String f = feeType.toLowerCase(Locale.ROOT);
        if (f.startsWith("crypto")) {
            return 0.07;
        }
        if (f.startsWith("politics") || f.startsWith("finance") || f.startsWith("tech")) {
            return 0.04;
        }
        if (f.startsWith("geopolitics")) {
            return 0.0;
        }
        // sports·economy·culture·weather·general 등 그 밖의 카테고리
        return 0.05;
    }

    /** Gamma 페이지(배열 모델) 첫 마켓의 feeType. 없거나 형식이 아니면 null. */
    public static String feeTypeOfPage(Object page) {
        Object m = null;
        if (page instanceof List && !((List<?>) page).isEmpty()) {
            m = ((List<?>) page).get(0);
        }
        if (!Js.truthy(m)) {
            return null;
        }
        Object fee = Js.field(m, "feeType");
        return Js.truthy(fee) ? Js.str(fee) : null;
    }

    /** 예상 테이커 수수료 — shares × rate × p × (1 − p). 입력이 비유한이면 NaN. */
    public static double estimateFee(double shares, double price, double rate) {
        if (!Double.isFinite(shares) || !Double.isFinite(price) || !Double.isFinite(rate)) {
            return Double.NaN;
        }
        return shares * rate * price * (1 - price);
    }

    /** 수동 매도 원장 append — 최근 LEDGER_CAP건만 유지한다 (꼬리 캡). */
    public static List<Object> appendRecord(List<Object> list, Map<String, Object> rec) {
        List<Object> out = new ArrayList<>(list);
        out.add(rec);
        if (out.size() > LEDGER_CAP) {
            out = new ArrayList<>(out.subList(out.size() - LEDGER_CAP, out.size()));
        }
        return out;
    }

    /** 결과 불명 시도 토큰 append — 중복·빈 토큰은 그대로, 상한은 꼬리 유지. */
    public static List<Object> appendAttempted(List<Object> list, String tokenId) {
        List<Object> out = new ArrayList<>(list);
        if (tokenId == null || tokenId.isEmpty() || out.contains(tokenId)) {
            return out;
        }
        out.add(tokenId);
        if (out.size() > ATTEMPTED_CAP) {
            out = new ArrayList<>(out.subList(out.size() - ATTEMPTED_CAP, out.size()));
        }
        return out;
    }
}
