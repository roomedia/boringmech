import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.engine.PositionBook;
import com.roomedia.boringmech.engine.PositionsFetcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 네이티브 이관 2단계 골든 대조 러너.
 * fixtures.json 전 케이스를 네이티브 이식(summarizeBook + sizing)에 통과시켜
 * expected.json과 필드 단위로 대조하고, fetchPositions는 합성 페이지로 검증한다.
 * 숫자 비교는 정확 일치(double 비트 단위) — 반올림·합산 순서까지 재현됐는지가 기준이다.
 *
 * 실행: run.sh (인자: native-golden2 디렉터리 경로, 기본 ".")
 */
public class Golden2Test {
    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        Map<String, Object> fixtures = asMap(Json.parse(Files.readString(dir.resolve("fixtures.json"))));
        Map<String, Object> expected = asMap(Json.parse(Files.readString(dir.resolve("expected.json"))));
        Map<String, Object> expectedCases = asMap(expected.get("cases"));

        for (Object cObj : asList(fixtures.get("cases"))) {
            Map<String, Object> c = asMap(cObj);
            String name = (String) c.get("name");
            runCase(name, asMap(c.get("input")), asMap(expectedCases.get(name)));
        }
        runFetchTests();

        System.out.println("----");
        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- summarizeBook + sizing 골든 대조 ---

    private static void runCase(String name, Map<String, Object> inp, Map<String, Object> exp) {
        int before = failures;
        List<Object> allRows = asListOrNull(inp.get("allRows"));
        List<Object> closedRows = asListOrNull(inp.get("closedRows"));
        List<Object> stopLossRecords = asListOrNull(inp.get("stopLossRecords"));

        PositionBook.Book book = PositionBook.summarizeBook(
                allRows, closedRows, stopLossRecords);

        eqNum(name, "openCount", exp.get("openCount"), book.openCount);
        eqNum(name, "positionsPnlPct", exp.get("positionsPnlPct"), book.positionsPnlPct);
        eqNum(name, "settledCount", exp.get("settledCount"), book.settledCount);
        eqNum(name, "settledWins", exp.get("settledWins"), book.settledWins);
        eqNum(name, "settledLosses", exp.get("settledLosses"), book.settledLosses);
        eqNum(name, "settledRealizedPnl", exp.get("settledRealizedPnl"), book.settledRealizedPnl);
        eqNum(name, "settledRealizedExcluded", exp.get("settledRealizedExcluded"), book.settledRealizedExcluded);
        eqNum(name, "settledStopLoss", exp.get("settledStopLoss"), book.settledStopLoss);
        eqSlugs(name, "heldSlugs", exp.get("heldSlugs"), book.heldSlugs);
        eqSlugs(name, "stopLossSlugs", exp.get("stopLossSlugs"), book.stopLossSlugs);

        Object cash = inp.get("cash");
        Object positionsValue = inp.get("positionsValue");
        Object expSizing = exp.get("sizing");
        if (cash instanceof Double && positionsValue instanceof Double) {
            double equity = Js.round2((Double) cash + (Double) positionsValue);
            PositionBook.Sizing s = PositionBook.sizing((Double) cash, equity, book.openCount);
            if (!(expSizing instanceof Map)) {
                fail(name, "sizing", "기대값이 null인데 입력에 cash/positionsValue가 있음");
            } else {
                Map<String, Object> es = asMap(expSizing);
                eqNum(name, "sizing.equity", es.get("equity"), equity);
                eqNum(name, "sizing.cashFloor", es.get("cashFloor"), s.cashFloor);
                eqNum(name, "sizing.available", es.get("available"), s.available);
                eqNum(name, "sizing.bet", es.get("bet"), s.bet);
                eqNum(name, "sizing.slots", es.get("slots"), s.slots);
            }
        } else if (expSizing != null) {
            fail(name, "sizing", "기대값에 sizing이 있는데 입력 cash/positionsValue가 숫자가 아님");
        }
        System.out.println((failures == before ? "PASS " : "FAIL ") + name);
    }

    // --- fetchPositions 합성 검증 (fixture 범위 밖 — 원본 의미론 기준) ---

    private static void runFetchTests() throws Exception {
        int before = failures;

        // 1) 다중 페이지 순회 + 페이지 간 dedupe + 키 없는 행 보존 + 인자 전달
        Map<String, Object> rowA = row("tok-a", "a");
        Map<String, Object> rowB = row("tok-b", "b");
        Map<String, Object> rowNoKey1 = new LinkedHashMap<>();
        rowNoKey1.put("slug", "nk1");
        Map<String, Object> rowNoKey2 = new LinkedHashMap<>();
        rowNoKey2.put("slug", "nk2");
        List<String> seenArgs = new ArrayList<>();
        PositionsFetcher.PageSource twoPages = (funder, status, cursor) -> {
            seenArgs.add(funder + "|" + status + "|" + cursor);
            if (cursor == null) {
                return page(List.of(rowA, rowB), true, "cur-2");
            }
            return page(List.of(rowB, rowNoKey1, rowNoKey2), false, null);
        };
        List<Object> out = PositionsFetcher.fetchPositions("0xFUNDER", "OPEN", twoPages);
        check("fetch.multi.size", out.size() == 4, "size=" + out.size());
        check("fetch.multi.args",
                seenArgs.equals(List.of("0xFUNDER|OPEN|null", "0xFUNDER|OPEN|cur-2")),
                seenArgs.toString());

        // 2) pagination 없는 단일 페이지에서 종료
        List<Object> single = PositionsFetcher.fetchPositions("f", null,
                (funder, status, cursor) -> {
                    Map<String, Object> root = new LinkedHashMap<>();
                    root.put("data", List.of(rowA));
                    return root;
                });
        check("fetch.single.size", single.size() == 1, "size=" + single.size());

        // 3) 빈 페이지 + has_more 없음 → 빈 목록 정상 반환
        List<Object> empty = PositionsFetcher.fetchPositions("f", null,
                (funder, status, cursor) -> page(List.of(), false, null));
        check("fetch.empty.ok", empty.isEmpty(), "size=" + empty.size());

        // 4) 빈 페이지인데 has_more → 부분 응답 오류
        expectFetchError("fetch.empty.hasMore",
                (funder, status, cursor) -> page(List.of(), true, "c"),
                "포지션 조회 부분 응답 — 빈 페이지인데 has_more");

        // 5) data가 배열이 아님 → 형식 오류 (객체·null·배열 루트 세 형태)
        expectFetchError("fetch.format.objectData",
                (funder, status, cursor) -> {
                    Map<String, Object> root = new LinkedHashMap<>();
                    root.put("data", new LinkedHashMap<>());
                    return root;
                },
                "포지션 조회 응답 형식 오류");
        expectFetchError("fetch.format.nullRoot",
                (funder, status, cursor) -> null,
                "포지션 조회 응답 형식 오류");
        expectFetchError("fetch.format.arrayRoot",
                (funder, status, cursor) -> List.of(),
                "포지션 조회 응답 형식 오류");

        // 6) 커서 순환 → 50페이지에서 상한 오류 (호출 횟수도 50)
        int[] calls = {0};
        expectFetchError("fetch.cap",
                (funder, status, cursor) -> {
                    calls[0]++;
                    return page(List.of(row("tok-x" + calls[0], "x")), true, "loop");
                },
                "포지션 조회 페이지 상한 초과 — 커서 순환 의심");
        check("fetch.cap.calls", calls[0] == PositionsFetcher.POSITIONS_PAGE_CAP,
                "calls=" + calls[0]);

        System.out.println((failures == before ? "PASS " : "FAIL ") + "fetchPositions 합성 검증");
    }

    private static Map<String, Object> row(String tokenId, String slug) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("token_id", tokenId);
        r.put("slug", slug);
        return r;
    }

    private static Map<String, Object> page(List<Object> rows, boolean hasMore, String nextCursor) {
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("has_more", hasMore);
        pagination.put("next_cursor", nextCursor);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("data", rows);
        root.put("pagination", pagination);
        return root;
    }

    private static void expectFetchError(
            String name, PositionsFetcher.PageSource source, String expectedMessage) {
        checks++;
        try {
            PositionsFetcher.fetchPositions("f", null, source);
            failures++;
            System.out.println("  FAIL " + name + ": 예외가 나지 않음");
        } catch (PositionsFetcher.FetchException e) {
            if (!expectedMessage.equals(e.getMessage())) {
                failures++;
                System.out.println("  FAIL " + name + ": 메시지 불일치 — 기대 \""
                        + expectedMessage + "\" 실제 \"" + e.getMessage() + "\"");
            }
        } catch (Exception e) {
            failures++;
            System.out.println("  FAIL " + name + ": 다른 예외 " + e);
        }
    }

    // --- 비교 헬퍼 ---

    /** 숫자/ null 대조 — expected는 JSON 숫자(Double) 또는 null, actual은 Number 또는 null. */
    private static void eqNum(String name, String field, Object expected, Object actual) {
        checks++;
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            fail(name, field, "기대 " + expected + " vs 실제 " + actual);
            return;
        }
        double e = ((Number) expected).doubleValue();
        double a = ((Number) actual).doubleValue();
        if (Double.compare(e, a) != 0) {
            fail(name, field, "기대 " + e + " vs 실제 " + a);
        }
    }

    private static void eqSlugs(String name, String field, Object expected, Set<String> actualSet) {
        checks++;
        List<String> expList = new ArrayList<>();
        if (expected instanceof List) {
            for (Object o : (List<?>) expected) {
                expList.add((String) o);
            }
        }
        List<String> actList = new ArrayList<>(actualSet);
        Collections.sort(expList);
        Collections.sort(actList);
        if (!expList.equals(actList)) {
            fail(name, field, "기대 " + expList + " vs 실제 " + actList);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL " + name + ": " + detail);
        }
    }

    private static void fail(String name, String field, String detail) {
        failures++;
        System.out.println("  FAIL " + name + "." + field + ": " + detail);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    /** JS 인자 의미론: 배열이 아니면(부재·null·비배열) null로 넘긴다. */
    private static List<Object> asListOrNull(Object o) {
        return o instanceof List ? asList(o) : null;
    }
}
