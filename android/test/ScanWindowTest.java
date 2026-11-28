// 만기 창 경계 JVM 테스트 (v0.3.64) — ScanFilter.scanCandidates의 만기
// 창이 기본 14일에서 오늘 마감 제외·잔여 14일 포함·15일 제외로 동작하는지
// 고정한다. 창을 45로 넓히면 15일이 들어오는 것도 함께 봐서, 픽스처가
// 창 값을 실제로 타는지 확인한다.
// 실행: android/test/run_scan_window_test.sh
import com.roomedia.boringmech.engine.ScanFilter;
import com.roomedia.boringmech.engine.TradeSettings;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ScanWindowTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String label) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + label);
        }
    }

    private static Map<String, Object> mkt(String slug, LocalDate end) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", Boolean.TRUE);
        m.put("closed", Boolean.FALSE);
        m.put("slug", slug);
        m.put("endDate", end.toString() + "T12:00:00.000Z");
        m.put("volume", 20000.0);
        m.put("outcomePrices", "[0.75,0.25]");
        return m;
    }

    public static void main(String[] args) {
        long nowMs = Instant.parse("2026-11-28T12:00:00Z").toEpochMilli();
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC);
        List<Object> markets = new ArrayList<>();
        markets.add(mkt("win-today", today));
        markets.add(mkt("win-1d", today.plusDays(1)));
        markets.add(mkt("win-14d", today.plusDays(14)));
        markets.add(mkt("win-15d", today.plusDays(15)));

        TradeSettings cfg = TradeSettings.defaults();
        check(cfg.endWindowDays == 14, "defaults: endWindowDays is 14, got " + cfg.endWindowDays);
        List<ScanFilter.Candidate> out = ScanFilter.scanCandidates(
                markets, Set.of(), Set.of(), new HashMap<>(), nowMs, cfg);
        List<String> slugs = new ArrayList<>();
        for (ScanFilter.Candidate c : out) {
            slugs.add(c.slug);
        }
        check(slugs.size() == 2 && slugs.contains("win-1d") && slugs.contains("win-14d"),
                "window: today excluded, 14d included, 15d excluded at default 14, got " + slugs);

        cfg.endWindowDays = 45;
        List<ScanFilter.Candidate> wide = ScanFilter.scanCandidates(
                markets, Set.of(), Set.of(), new HashMap<>(), nowMs, cfg);
        check(wide.size() == 3,
                "window: 15d included when window widened to 45, got " + wide.size());

        System.out.println("ScanWindow tests: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
