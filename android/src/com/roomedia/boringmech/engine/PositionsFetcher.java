package com.roomedia.boringmech.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * engine.js fetchPositions의 순수 로직 이식 — 커서 페이지네이션·형식 검증·
 * 토큰 단위 dedupe. HTTP 자체는 PageSource 인터페이스 뒤에 분리한다
 * (운영에서는 네이티브 HTTP 구현, 테스트에서는 fixture 공급).
 */
public final class PositionsFetcher {
    private PositionsFetcher() {}

    /** engine.js POSITIONS_PAGE_CAP — 커서 순환 시 스냅샷이 끝나지 않는 것을 막는 상한. */
    public static final int POSITIONS_PAGE_CAP = 50;

    /** 포지션 조회 실패 — JS가 throw하는 Error의 메시지를 그대로 실어 나른다. */
    public static class FetchException extends Exception {
        public FetchException(String message) {
            super(message);
        }
    }

    /**
     * 한 페이지의 원응답(JSON 모델)을 돌려주는 공급자.
     * cursor가 null이면 첫 페이지. status가 null이면 무지정 조회.
     */
    public interface PageSource {
        Object fetchPage(String funder, String status, String cursor) throws Exception;
    }

    /**
     * 전체 페이지를 순회해 dedupe된 포지션 행 목록을 만든다.
     * 형식 오류·부분 응답·페이지 상한 초과는 FetchException으로 던진다
     * (빈 목록으로 삼키지 않는 것이 원본 의미론이다).
     */
    public static List<Object> fetchPositions(String funder, String status, PageSource source)
            throws Exception {
        List<Object> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        String cursor = null;
        for (int page = 0; ; page += 1) {
            // 커서가 순환하면 스냅샷이 영영 끝나지 않는다 — 상한에서 오류로 끊는다.
            if (page >= POSITIONS_PAGE_CAP) {
                throw new FetchException("포지션 조회 페이지 상한 초과 — 커서 순환 의심");
            }
            Object data = source.fetchPage(funder, status, cursor);
            Object rows = Js.field(data, "data");
            // data가 배열이 아닌 200 응답을 빈 목록으로 삼키면 보유 0건으로
            // 오독한다 — 형식 오류는 실패로 던진다.
            if (!(rows instanceof List)) {
                throw new FetchException("포지션 조회 응답 형식 오류");
            }
            List<?> pageRows = (List<?>) rows;
            Object pagination = Js.field(data, "pagination");
            boolean hasMore = Js.truthy(Js.field(pagination, "has_more"));
            // 페이지가 비었는데 다음이 있다고 하면 부분 목록을 전체로 확정할
            // 수 없다 — 같은 이유로 실패로 던진다.
            if (pageRows.isEmpty() && hasMore) {
                throw new FetchException("포지션 조회 부분 응답 — 빈 페이지인데 has_more");
            }
            for (Object p : pageRows) {
                // 순회 중 상태 전이로 페이지가 밀리면 같은 토큰이 두 번 잡힌다 —
                // 토큰 단위로 중복을 제거한다. 키가 없는 행은 항상 남긴다.
                String key = PositionBook.positionKey(p);
                if (key != null) {
                    if (seenKeys.contains(key)) {
                        continue;
                    }
                    seenKeys.add(key);
                }
                out.add(p);
            }
            Object nextCursor = Js.field(pagination, "next_cursor");
            if (!Js.truthy(nextCursor)) {
                return out;
            }
            cursor = Js.str(nextCursor);
        }
    }
}
