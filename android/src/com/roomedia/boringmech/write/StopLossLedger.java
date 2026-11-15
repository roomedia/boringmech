package com.roomedia.boringmech.write;

import com.roomedia.boringmech.engine.Js;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 손절 원장의 순수 로직 — engine.js parseStopLossList·stopLossRecords·
 * recordStopLoss·stopLossAttemptedTokens·recordStopLossAttempt의 이식.
 *
 * 저장은 Store 인터페이스 뒤에 있다: 읽기 원본(JSON 모델 리스트)을 주고
 * 쓰기 값(모델 리스트)을 받는 형태라, 통합 단계에서 bm_durable 어댑터를
 * 붙이면 되고 테스트에서는 인메모리로 검증한다.
 * 캡 규칙: 최근 기록 200건은 전체 유지, 넘친 건은 {token_id, realized_pnl}만
 * 아카이브(상한 10,000건)로, 시도 토큰은 최근 200개만 남긴다.
 *
 * 동시성: read-modify-write 전체를 store 객체 모니터로 감싼다. 세션이
 * 겹쳐 같은 Store를 공유해도 한쪽의 읽기가 다른 쪽의 쓰기를 덮어
 * 기록이 유실되지 않는다. 호출자는 세션마다 다른 Store 어댑터를 만들지
 * 말고 공유 인스턴스를 써야 이 보장이 성립한다 (DurableStore가 단일
 * 인스턴스를 돌려준다).
 */
public final class StopLossLedger {
    private StopLossLedger() {}

    public static final int RECENT_CAP = 200;
    public static final int ARCHIVE_CAP = 10_000;
    public static final int ATTEMPTED_CAP = 200;

    public static final String KEY_RECENT = "bm_stoploss";
    public static final String KEY_ARCHIVE = "bm_stoploss_archive";
    public static final String KEY_ATTEMPTED = "bm_stoploss_attempted";

    /** 원장 저장 추상화 — 값은 JSON 모델(리스트) 단위로 오간다. */
    public interface Store {
        /** 키의 원본 모델 (없거나 배열이 아니면 null). */
        Object read(String key);

        void write(String key, Object model);
    }

    /** 손절 실행 기록 1건 (recordStopLoss의 rec와 같은 필드·순서). */
    public static Map<String, Object> record(String slug, Object conditionId, String tokenId,
                                             Object outcome, double entryPrice, double exitPrice,
                                             double shares, double proceeds, double realizedPnl,
                                             String tsIso) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("slug", slug);
        rec.put("condition_id", conditionId);
        rec.put("token_id", tokenId);
        rec.put("outcome", outcome);
        rec.put("entry_price", entryPrice);
        rec.put("exit_price", exitPrice);
        rec.put("shares", shares);
        rec.put("proceeds", proceeds);
        rec.put("realized_pnl", realizedPnl);
        rec.put("ts", tsIso);
        return rec;
    }

    /** parseStopLossList — token_id 문자열 + realized_pnl 유한수인 행만 남긴다. */
    public static List<Object> parseStopLossList(Object model) {
        List<Object> out = new ArrayList<>();
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
        return out;
    }

    /** stopLossRecords — 아카이브가 먼저, 최근 기록이 뒤 (summarizeBook 입력 순서). */
    public static List<Object> records(Store store) {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            out.addAll(parseStopLossList(store.read(KEY_ARCHIVE)));
            out.addAll(parseStopLossList(store.read(KEY_RECENT)));
            return out;
        }
    }

    /** recordStopLoss — 캡을 적용해 recent·archive 양쪽을 다시 쓴다. */
    public static void recordStopLoss(Store store, Map<String, Object> rec) {
        synchronized (store) {
            List<Object> recent = parseStopLossList(store.read(KEY_RECENT));
            recent.add(rec);
            List<Object> archive = parseStopLossList(store.read(KEY_ARCHIVE));
            while (recent.size() > RECENT_CAP) {
                Object old = recent.remove(0);
                Map<String, Object> slim = new LinkedHashMap<>();
                slim.put("token_id", Js.field(old, "token_id"));
                slim.put("realized_pnl", Js.fieldNum(old, "realized_pnl"));
                archive.add(slim);
            }
            if (archive.size() > ARCHIVE_CAP) {
                archive = new ArrayList<>(archive.subList(archive.size() - ARCHIVE_CAP, archive.size()));
            }
            store.write(KEY_RECENT, recent);
            store.write(KEY_ARCHIVE, archive);
        }
    }

    /** stopLossAttemptedTokens — 문자열만 Set으로. */
    public static Set<String> attemptedTokens(Store store) {
        Set<String> out = new LinkedHashSet<>();
        Object model = store.read(KEY_ATTEMPTED);
        if (model instanceof List) {
            for (Object t : (List<?>) model) {
                if (t instanceof String) {
                    out.add((String) t);
                }
            }
        }
        return out;
    }

    /** recordStopLossAttempt — 중복이면 무동작, 넘치면 최근 200개만. */
    public static void recordAttempt(Store store, String tokenId) {
        if (tokenId == null || tokenId.isEmpty()) {
            return;
        }
        synchronized (store) {
            Set<String> set = attemptedTokens(store);
            if (set.contains(tokenId)) {
                return;
            }
            set.add(tokenId);
            List<Object> list = new ArrayList<>(set);
            if (list.size() > ATTEMPTED_CAP) {
                list = new ArrayList<>(list.subList(list.size() - ATTEMPTED_CAP, list.size()));
            }
            store.write(KEY_ATTEMPTED, list);
        }
    }

    /** 테스트·통합 공용 인메모리 Store. */
    public static final class InMemoryStore implements Store {
        public final Map<String, Object> data = new LinkedHashMap<>();

        @Override
        public Object read(String key) {
            return data.get(key);
        }

        @Override
        public void write(String key, Object model) {
            data.put(key, model);
        }
    }
}
