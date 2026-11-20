package com.roomedia.boringmech.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * engine.js 기매수 정화 판정 이식 — reconcileBought·boughtCorrupted·
 * fetchBuySlugs. 온체인 매수 이력과 현재 보유에 모두 없는 bought 항목은
 * 산 적이 없는 것으로 판정해 제거한다. 이력 순회가 중간에 끊긴 부분
 * 집합을 완전한 진실로 채택하면 실제 체결 기록까지 지워져 재매수가
 * 열리므로, fetchBuySlugs는 complete 플래그를 함께 돌려준다.
 */
public final class BoughtReconcile {
    private BoughtReconcile() {}

    /** engine.js ACTIVITY_PAGE_CAP — activity 커서 순회 상한. */
    public static final int ACTIVITY_PAGE_CAP = 10;

    /** reconcileBought 결과 — kept·dropped 모두 원본 집합의 순서를 유지한다. */
    public static final class Reconcile {
        public final List<Object> kept;
        public final List<Object> dropped;

        Reconcile(List<Object> kept, List<Object> dropped) {
            this.kept = kept;
            this.dropped = dropped;
        }
    }

    public static Reconcile reconcileBought(
            Set<Object> boughtSet, Set<Object> onChainBuySlugs, Set<Object> heldSlugsSet) {
        Set<Object> truth = new LinkedHashSet<>(onChainBuySlugs);
        truth.addAll(heldSlugsSet);
        List<Object> kept = new ArrayList<>();
        List<Object> dropped = new ArrayList<>();
        for (Object s : boughtSet) {
            // 진실 대조는 항목 형태가 아니라 slug로 한다 — 전략 태그를 단
            // 객체 항목도 문자열 항목과 같은 기준으로 남기고, 남는 항목은
            // 원본 형태(태그 포함) 그대로 돌려준다.
            String slug = slugOfEntry(s);
            (slug != null && truth.contains(slug) ? kept : dropped).add(s);
        }
        return new Reconcile(kept, dropped);
    }

    /**
     * 매수 기록 항목에서 slug를 뽑는다 — 문자열 항목은 그 자체, 전략 태그
     * 객체({slug, strategy})는 slug 필드. 어느 쪽도 아니면 null.
     */
    public static String slugOfEntry(Object entry) {
        if (entry instanceof String) {
            return (String) entry;
        }
        Object slug = Js.field(entry, "slug");
        return slug instanceof String ? (String) slug : null;
    }

    /**
     * 해당 slug의 매수 기록에 붙은 전략 태그 — 기온 매수면 "temp_no",
     * 태그가 없거나 기록 자체가 없으면 null (메인 매수로 간주한다).
     */
    public static String strategyOf(List<Object> boughtEntries, String slug) {
        if (slug == null || boughtEntries == null) {
            return null;
        }
        for (Object e : boughtEntries) {
            if (!slug.equals(slugOfEntry(e))) {
                continue;
            }
            Object st = Js.field(e, "strategy");
            return TempNo.STRATEGY.equals(st) ? TempNo.STRATEGY : null;
        }
        return null;
    }

    /**
     * 기매수 기록이 손상됐는가 — 원문이 있는데 배열로 파싱되지 않으면 손상.
     * 손상을 "기록 없음"과 구분하지 못하면 닫힌 종목 전체가 신규로 떨어진다.
     */
    public static boolean boughtCorrupted(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        try {
            return !(JsonText.parse(raw) instanceof List);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** activity 한 페이지의 원응답(JSON 모델)을 돌려주는 공급자. cursor가 null이면 첫 페이지. */
    public interface PageSource {
        Object fetchPage(String funder, String cursor) throws Exception;
    }

    /** fetchBuySlugs 결과 — slugs(삽입 순서 집합)와 순회 완결 여부. */
    public static final class BuySlugs {
        public final LinkedHashSet<Object> slugs;
        public final boolean complete;

        BuySlugs(LinkedHashSet<Object> slugs, boolean complete) {
            this.slugs = slugs;
            this.complete = complete;
        }
    }

    /**
     * Data API v2 activity 커서 순회로 온체인 매수 slug를 모은다.
     * 부분 응답·형식 오류·페이지 상한 도달은 complete=false로 돌려준다
     * (호출자가 정화를 중단한다). 조회 자체의 예외는 그대로 던진다.
     */
    public static BuySlugs fetchBuySlugs(String funder, PageSource source) throws Exception {
        LinkedHashSet<Object> slugs = new LinkedHashSet<>();
        Object cursor = null;
        for (int page = 0; page < ACTIVITY_PAGE_CAP; page++) {
            Object data = source.fetchPage(funder, cursor == null ? null : Js.str(cursor));
            List<?> rows;
            if (data instanceof List) {
                rows = (List<?>) data;
            } else {
                Object d = Js.field(data, "data");
                if (!(d instanceof List)) {
                    return new BuySlugs(slugs, false);
                }
                rows = (List<?>) d;
            }
            Object pagination = Js.field(data, "pagination");
            boolean hasMore = Js.truthy(Js.field(pagination, "has_more"));
            if (rows.isEmpty()) {
                // 빈 페이지인데 뒤가 더 있다고 하면 부분 응답이다
                return new BuySlugs(slugs, !hasMore);
            }
            for (Object r : rows) {
                if ("TRADE".equals(Js.field(r, "type"))
                        && "BUY".equals(Js.field(r, "side"))
                        && Js.truthy(Js.field(r, "slug"))) {
                    slugs.add(Js.field(r, "slug"));
                }
            }
            Object next = Js.field(pagination, "next_cursor");
            if (!hasMore) {
                return new BuySlugs(slugs, true);
            }
            if (!Js.truthy(next) || (cursor != null && next.equals(cursor))) {
                // 뒤가 더 있는데 진행할 커서가 없거나 순환한다 — 부분 응답이라
                // 완결로 판정하면 정화가 실체결 기록을 지울 수 있다.
                return new BuySlugs(slugs, false);
            }
            cursor = next;
        }
        // 페이지 상한에 닿아 끊겼다 — 뒤에 남은 이력이 진실에서 빠져 있다
        return new BuySlugs(slugs, false);
    }
}
