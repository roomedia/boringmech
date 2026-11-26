package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.write.ModelJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기매수 장부(bm_bought)의 단일 소유자 (공리적 분리 4단계, 감사 결합 4).
 *
 * 저장소 키 접근은 이 클래스만 한다 — NativeCycle 등 다른 코드는
 * 조회 인터페이스(entries·entrySet·slugStrings·mark·corrupted·
 * isTempTagged·writeEntries)로만 읽고 쓴다. 순수 판정(정화 대조·
 * 손상 판정·slug 추출)은 BoughtReconcile이 그대로 맡는다.
 *
 * 저장은 Store 인터페이스 뒤에 있다: 원문 문자열 단위로 오가며,
 * 실기기에서는 NativeCycle.Env(durable) 어댑터, 테스트에서는
 * 인메모리로 검증한다.
 */
public final class BoughtLedger {

    public static final String KEY = "bm_bought";

    /** 원장 저장 추상화 — 값은 원문 JSON 문자열 단위로 오간다. */
    public interface Store {
        String read(String key);

        void write(String key, String value);

        /** 접두사 없는 로그 본문 한 줄 (NativeCycle.log와 같은 채널). */
        void log(String body);
    }

    private final Store store;

    public BoughtLedger(Store store) {
        this.store = store;
    }

    /** 장부 원본 항목 목록 — 배열이 아니거나 깨진 원문은 빈 목록. */
    public List<Object> entries() {
        String raw = store.read(KEY);
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

    /** 장부 항목 집합 (원본 순서 유지) — 정화 대조의 입력 형태. */
    public Set<Object> entrySet() {
        Set<Object> out = new LinkedHashSet<>();
        for (Object v : entries()) {
            out.add(v);
        }
        return out;
    }

    /** 장부의 slug 문자열 집합 — 문자열 항목과 전략 태그 객체 항목 양쪽에서 뽑는다. */
    public Set<String> slugStrings() {
        Set<String> out = new LinkedHashSet<>();
        for (Object v : entrySet()) {
            String s = BoughtReconcile.slugOfEntry(v);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** 해당 slug의 매수 기록에 기온 태그가 붙어 있는가. */
    public boolean isTempTagged(String slug) {
        return TempNo.isTempTagged(entries(), slug);
    }

    /** 기매수 기록이 손상됐는가 — 원문이 있는데 배열로 파싱되지 않으면 손상. */
    public boolean corrupted() {
        return BoughtReconcile.boughtCorrupted(store.read(KEY));
    }

    /** 장부 전체를 되쓴다 — 정화 결과(kept) 반영 전용. */
    public void writeEntries(List<Object> entries) {
        store.write(KEY, ModelJson.stringify(entries));
    }

    /**
     * 매수 기록 — 같은 slug의 기존 항목은 지우고 한 건만 남긴다. 기온
     * 매수는 전략 태그를 단 객체로, 메인 매수는 기존 문자열 형태로
     * 기록한다. 쓰기가 조용히 실패하면 잠금·기록이 동시에 없는 상태가
     * 되므로, 다시 읽어 실제로 남았는지 확인하고 없으면 로그로 드러낸다.
     */
    public void mark(String slug, String strategy) {
        List<Object> entries = new ArrayList<>();
        for (Object v : entrySet()) {
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
        store.write(KEY, ModelJson.stringify(entries));
        if (!slugStrings().contains(slug)) {
            store.log("매수 기록 저장 확인 실패 " + slug + " — 저장소 쓰기가 반영되지 않았습니다");
        }
    }
}
