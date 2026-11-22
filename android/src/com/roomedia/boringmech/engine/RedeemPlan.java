package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.crypto.CallData;
import com.roomedia.boringmech.crypto.Hex;
import com.roomedia.boringmech.crypto.Redeem;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * engine.js 리딤 계획·정화 판정 이식 — planRedeems·verifyRecovery·
 * redeemBalanceGate·normalizeConditionId·toBytes31·콜 빌더(암호 코어 위임)·
 * ingestRedeemPending의 수집 판정.
 * 승자 행(REDEEMABLE·가치>0)이 있는 condition만 회수 대상이고, 패배 행만
 * 남은 condition은 worthless로 종결한다는 것이 원본 분류 규칙이다.
 */
public final class RedeemPlan {
    private RedeemPlan() {}

    // engine.js 상수 — 회수 검증 허용 오차 max($0.05, 2%)
    public static final double REDEEM_TOL_ABS = 0.05;
    public static final double REDEEM_TOL_REL = 0.02;

    private static final Pattern CONDITION_ID_RE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BYTES31_HEX_RE = Pattern.compile("[0-9a-f]+");
    private static final byte[] ZERO_BYTES32 = new byte[32];

    /** condition_id 정규화 — 0x 없는 64자리 hex 소문자로. 형식이 아니면 null. */
    public static String normalizeConditionId(Object raw) {
        String cid = Js.truthy(raw) ? Js.str(raw) : "";
        cid = cid.toLowerCase(Locale.ROOT);
        if (cid.startsWith("0x")) {
            cid = cid.substring(2);
        }
        return CONDITION_ID_RE.matcher(cid).matches() ? cid : null;
    }

    /** 회수 대상 1건 — condition 단위 그룹 + 회수 예정액. */
    public static final class Target {
        public final String conditionId;
        public final List<Object> wins;
        public final List<Object> losses;
        public final double expectedUsd;

        Target(String conditionId, List<Object> wins, List<Object> losses, double expectedUsd) {
            this.conditionId = conditionId;
            this.wins = wins;
            this.losses = losses;
            this.expectedUsd = expectedUsd;
        }
    }

    /** planRedeems 결과 — targets·worthless·errors(사유 문자열). */
    public static final class Plan {
        public final List<Target> targets = new ArrayList<>();
        public final List<Object> worthless = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    /** 정산 행 → condition 단위 계획 (live_executor.plan_redeems 이식). */
    public static Plan planRedeems(List<Object> entries) {
        Plan plan = new Plan();
        Map<String, List<Object>> winsByKey = new LinkedHashMap<>();
        Map<String, List<Object>> lossesByKey = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        if (entries != null) {
            for (Object e : entries) {
                String cid = normalizeConditionId(Js.field(e, "condition_id"));
                if (cid == null) {
                    plan.errors.add("condition_id 형식 오류");
                    continue;
                }
                String key = "0x" + cid;
                if (!winsByKey.containsKey(key)) {
                    winsByKey.put(key, new ArrayList<>());
                    lossesByKey.put(key, new ArrayList<>());
                    order.add(key);
                }
                boolean won = "REDEEMABLE".equals(Js.field(e, "status"))
                        && Js.numOrZero(Js.field(e, "current_value")) > 0;
                (won ? winsByKey.get(key) : lossesByKey.get(key)).add(e);
            }
        }
        for (String key : order) {
            List<Object> wins = winsByKey.get(key);
            List<Object> losses = lossesByKey.get(key);
            if (!wins.isEmpty()) {
                double sum = 0;
                for (Object e : wins) {
                    sum += Js.numOrZero(Js.field(e, "current_value"));
                }
                plan.targets.add(new Target(key, wins, losses, Js.round2(sum)));
            } else {
                plan.worthless.addAll(losses);
            }
        }
        return plan;
    }

    /** 회수 검증 결과 (verifyRecovery 반환 객체). */
    public static final class Recovery {
        public final double deltaUsd;
        public final double expectedUsd;
        public final double tolerance;
        /** verified | mismatch */
        public final String status;

        Recovery(double deltaUsd, double expectedUsd, double tolerance, String status) {
            this.deltaUsd = deltaUsd;
            this.expectedUsd = expectedUsd;
            this.tolerance = tolerance;
            this.status = status;
        }
    }

    /** pUSD 잔고 델타 검증 — 허용 오차 max($0.05, 2%) 안이면 verified. */
    public static Recovery verifyRecovery(double beforeCash, double afterCash, double expectedUsd) {
        double delta = Js.round2(afterCash - beforeCash);
        double expected = (expectedUsd == 0 || Double.isNaN(expectedUsd)) ? 0 : expectedUsd;
        double tolerance = Math.max(REDEEM_TOL_ABS, REDEEM_TOL_REL * expected);
        return new Recovery(
                delta,
                expected,
                Js.round2(tolerance),
                Math.abs(delta - expected) <= tolerance ? "verified" : "mismatch");
    }

    /**
     * 온체인 잔고 목록으로 상환 제출 여부를 가른다 — 전부 0이면 이미 상환이
     * 끝난 건("already"), 잔고가 남으면 "submit", 하나도 못 읽었으면 "unknown".
     */
    public static String redeemBalanceGate(List<BigInteger> balances) {
        if (balances == null || balances.isEmpty()) {
            return "unknown";
        }
        for (BigInteger b : balances) {
            if (b.signum() != 0) {
                return "submit";
            }
        }
        return "already";
    }

    /**
     * protocol v2: bytes31은 32바이트 condition id 끝의 binary outcome
     * 바이트를 뗀 것. 바꿀 수 없으면 원본처럼 예외를 던진다.
     */
    public static String toBytes31(String conditionId) {
        String hex = conditionId == null ? "" : conditionId.toLowerCase(Locale.ROOT);
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (hex.length() == 64 && (hex.endsWith("00") || hex.endsWith("01"))) {
            hex = hex.substring(0, 62);
        }
        if (hex.length() != 62 || !BYTES31_HEX_RE.matcher(hex).matches()) {
            throw new IllegalArgumentException("protocol v2 condition id를 bytes31로 바꿀 수 없음");
        }
        return "0x" + hex;
    }

    /** buildCtfRedeemCall — {target, value:"0", data} 모델. 인코딩은 암호 코어. */
    public static Map<String, Object> buildCtfRedeemCall(String conditionId, boolean negRisk) {
        CallData call = Redeem.ctfRedeem(
                Redeem.PUSD,
                ZERO_BYTES32,
                Hex.hexToBytes(conditionId),
                new BigInteger[]{BigInteger.ONE, BigInteger.TWO},
                negRisk);
        return callModel(call);
    }

    /** buildV2RedeemCall — {target, value:"0", data} 모델. 인코딩은 암호 코어. */
    public static Map<String, Object> buildV2RedeemCall(
            String conditionId, BigInteger outcomeIndex, BigInteger amount) {
        toBytes31(conditionId); // 원본의 검증(예외)을 먼저 태운다
        CallData call = Redeem.v2Redeem(conditionId, outcomeIndex, amount);
        return callModel(call);
    }

    private static Map<String, Object> callModel(CallData call) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", call.target);
        m.put("value", "0");
        m.put("data", call.dataHex());
        return m;
    }

    // ── pending 수집 판정 (ingestRedeemPending) ─────────────────

    /** 수집 결과 — 갱신된 pending 목록과 변경 여부. */
    public static final class Ingest {
        public final List<Object> pending;
        public final boolean changed;

        Ingest(List<Object> pending, boolean changed) {
            this.pending = pending;
            this.changed = changed;
        }
    }

    /**
     * 스냅샷의 정산 행(REDEEMABLE/REDEEMABLE_LOST + CLOSED 승자)을 pending에
     * 수집한다. 이미 회수했거나 worthless로 종결한 condition(doneConds)은
     * 다시 넣지 않고, 이미 수집된 항목은 가치가 바뀌면 최신 행으로 갱신한다.
     * CLOSED 승 행은 current_value가 0으로 오므로 회수 예정액을 진입원가 +
     * 실현손익으로 추정해 넣는다.
     */
    public static Ingest ingest(
            List<Object> pendingIn, Set<Object> doneConds,
            List<Object> winRows, List<Object> lostRows, List<Object> closedRows) {
        List<Object> pending = new ArrayList<>();
        Map<String, Object> byKey = new LinkedHashMap<>();
        boolean droppedDone = false;
        for (Object e : pendingIn != null ? pendingIn : List.<Object>of()) {
            // 이미 회수·종결한 condition이 pending에 남아 있어도 다시 넣지
            // 않는다 — 신규 행에만 done 검사를 하면, history 기록 후 pending
            // 제거가 실패한 비원자 구간에서 기리딤 건이 재제출된다.
            Object cidRaw = Js.field(e, "condition_id");
            if (Js.truthy(cidRaw) && doneConds.contains(cidRaw)) {
                droppedDone = true;
                continue;
            }
            Object copy = e instanceof Map ? new LinkedHashMap<>((Map<?, ?>) e) : e;
            pending.add(copy);
            byKey.putIfAbsent(keyOf(copy), copy);
        }
        boolean[] changed = {droppedDone};
        addRows(pending, byKey, doneConds, winRows, "REDEEMABLE", changed);
        addRows(pending, byKey, doneConds, lostRows, "REDEEMABLE_LOST", changed);

        List<Object> closedWins = new ArrayList<>();
        if (closedRows != null) {
            for (Object r : closedRows) {
                if (!(Js.num(Js.field(r, "current_price")) >= 1)) {
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>();
                if (r instanceof Map) {
                    for (Map.Entry<?, ?> en : ((Map<?, ?>) r).entrySet()) {
                        copy.put(String.valueOf(en.getKey()), en.getValue());
                    }
                }
                double est = Js.round2(
                        Js.numOrZero(Js.field(r, "entry_cost_usdc"))
                                + Js.numOrZero(Js.field(r, "realized_pnl")));
                Object cvRaw = Js.field(r, "current_value");
                copy.put("current_value", Js.num(cvRaw) > 0 ? cvRaw : est);
                if (Js.num(copy.get("current_value")) > 0) {
                    closedWins.add(copy);
                }
            }
        }
        addRows(pending, byKey, doneConds, closedWins, "REDEEMABLE", changed);
        return new Ingest(pending, changed[0]);
    }

    private static String keyOf(Object e) {
        return Js.str(Js.field(e, "condition_id")) + "|" + Js.str(Js.field(e, "token_id"));
    }

    /** status 승격 순위 — REDEEMABLE(승자 확인) > REDEEMABLE_LOST(패배 관측). */
    private static int statusRank(String status) {
        if ("REDEEMABLE".equals(status)) {
            return 2;
        }
        if ("REDEEMABLE_LOST".equals(status)) {
            return 1;
        }
        return 0;
    }

    private static void addRows(
            List<Object> pending, Map<String, Object> byKey, Set<Object> doneConds,
            List<Object> rows, String status, boolean[] changed) {
        if (rows == null) {
            return;
        }
        for (Object r : rows) {
            Object cidRaw = Js.field(r, "condition_id");
            if (!Js.truthy(cidRaw) || doneConds.contains(cidRaw)) {
                continue;
            }
            String k = keyOf(r);
            double value = Js.numOrZero(Js.field(r, "current_value"));
            Object existing = byKey.get(k);
            if (existing != null) {
                // 이미 수집된 항목도 가치는 최신 행으로 갱신한다.
                if (existing instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> em = (Map<String, Object>) existing;
                    if (Double.isFinite(value)) {
                        Object cur = Js.field(existing, "current_value");
                        boolean equal = cur instanceof Double && (Double) cur == value;
                        if (!equal) {
                            em.put("current_value", value);
                            changed[0] = true;
                        }
                    }
                    // status는 승격 방향으로만 갱신한다 — REDEEMABLE_LOST로
                    // 먼저 수집된 토큰이 나중에 승자 행으로 와도 status가
                    // 고정돼 있으면 planRedeems의 승자 판정에서 영원히
                    // 빠진다 (v0.3.39 감사 C). 반대 강등은 없다: 승자로
                    // 확인된 항목이 패배 행 재수집으로 도로 패자가 되지
                    // 않는다.
                    Object curStatus = Js.field(existing, "status");
                    String curStatusStr = curStatus instanceof String ? (String) curStatus : null;
                    if (statusRank(status) > statusRank(curStatusStr)) {
                        em.put("status", status);
                        changed[0] = true;
                    }
                }
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("condition_id", cidRaw);
            Object tokenId = Js.field(r, "token_id");
            if (tokenId != Js.UNDEFINED) {
                entry.put("token_id", tokenId);
            }
            Object slug = Js.field(r, "slug");
            entry.put("slug", slug == Js.UNDEFINED ? null : slug);
            entry.put("status", status);
            entry.put("current_value", value);
            byKey.put(k, entry);
            pending.add(entry);
            changed[0] = true;
        }
    }
}
