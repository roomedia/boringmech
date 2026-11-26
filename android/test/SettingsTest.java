// 설정 파서 단일화 패리티 JVM 테스트 — 픽스처 전건에 대해 정본 해석
// (Settings.parse)과 투영(TradeSettings.parse·TempNo.parseSettings)의
// 결과가 리팩터 전 구 파서 출력(기준선)과 같은지 대조한다. 픽스처는
// JS 테스트와 공유한다 (app/test/fixtures/settings-parity.json).
// 실행: android/test/run_settings_test.sh
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.Settings;
import com.roomedia.boringmech.engine.TempNo;
import com.roomedia.boringmech.engine.TradeSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SettingsTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void cmp(String name, String field, double actual, double expected) {
        if (actual == expected) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name + " " + field + " expected=" + expected + " got=" + actual);
        }
    }

    private static void cmpBool(String name, String field, boolean actual, boolean expected) {
        if (actual == expected) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name + " " + field + " expected=" + expected + " got=" + actual);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String text = Files.readString(Path.of(args[0]));
        List<Object> fixtures = (List<Object>) JsonText.parse(text);
        for (Object fo : fixtures) {
            Map<String, Object> f = (Map<String, Object>) fo;
            String name = (String) f.get("name");
            Object raw = f.get("raw");
            // 객체 형태 raw는 저장 블롭과 동등한 JSON 문자열로 직렬화해 넣는다.
            String rawStr = raw == null || raw instanceof String
                    ? (String) raw : JsonText.stringify(raw);
            Map<String, Object> et = (Map<String, Object>) f.get("trade");
            Map<String, Object> ep = (Map<String, Object>) f.get("temp");

            Settings s = Settings.parse(rawStr);
            cmp(name, "scan.bandLo", s.scan.bandLo, num(et, "bandLo"));
            cmp(name, "scan.bandHi", s.scan.bandHi, num(et, "bandHi"));
            cmp(name, "scan.priorityBandLo", s.scan.priorityBandLo, num(et, "priorityBandLo"));
            cmp(name, "scan.priorityBandHi", s.scan.priorityBandHi, num(et, "priorityBandHi"));
            cmp(name, "scan.volumeMin", s.scan.volumeMin, num(et, "volumeMin"));
            cmp(name, "scan.endWindowDays", s.scan.endWindowDays, num(et, "endWindowDays"));
            cmp(name, "scan.spreadMax", s.scan.spreadMax, num(et, "spreadMax"));
            cmp(name, "scan.askDepthMinUsd", s.scan.askDepthMinUsd, num(et, "askDepthMinUsd"));
            cmp(name, "scan.slippageCap", s.scan.slippageCap, num(et, "slippageCap"));
            cmp(name, "scan.snapshotMatchTol", s.scan.snapshotMatchTol, num(et, "snapshotMatchTol"));
            cmp(name, "scan.askAboveSignal", s.scan.askAboveSignal, num(et, "askAboveSignal"));
            cmp(name, "sizing.betUsd", s.sizing.betUsd, num(et, "betUsd"));
            cmp(name, "sizing.cashFloorPct", s.sizing.cashFloorPct, num(et, "cashFloorPct"));
            cmp(name, "sizing.reserveUsd", s.sizing.reserveUsd, num(et, "reserveUsd"));
            cmp(name, "sizing.maxPositions", s.sizing.maxPositions, num(et, "maxPositions"));
            cmp(name, "sizing.verifyCap", s.sizing.verifyCap, num(et, "verifyCap"));
            cmp(name, "stoploss.stopDrop", s.stoploss.stopDrop, num(et, "stopDrop"));
            cmp(name, "stoploss.tempStopDrop", s.stoploss.tempStopDrop, num(et, "tempStopDrop"));
            cmp(name, "stoploss.decidedHigh", s.stoploss.decidedHigh, num(et, "decidedHigh"));
            cmp(name, "stoploss.decidedLow", s.stoploss.decidedLow, num(et, "decidedLow"));
            cmp(name, "orderTestRounds", s.orderTestRounds, num(et, "orderTestRounds"));
            cmpBool(name, "temp.enabled", s.temp.enabled, (Boolean) ep.get("enabled"));
            cmp(name, "temp.bandLo", s.temp.bandLo, num(ep, "bandLo"));
            cmp(name, "temp.bandHi", s.temp.bandHi, num(ep, "bandHi"));
            cmp(name, "temp.entryHourUtc", s.temp.entryHourUtc, num(ep, "entryHourUtc"));
            cmp(name, "temp.reserveSlots", s.temp.reserveSlots, num(ep, "reserveSlots"));
            cmp(name, "temp.betUsd", s.temp.betUsd, num(ep, "betUsd"));

            TradeSettings t = TradeSettings.parse(rawStr);
            cmp(name, "view.bandLo", t.bandLo, num(et, "bandLo"));
            cmp(name, "view.bandHi", t.bandHi, num(et, "bandHi"));
            cmp(name, "view.betUsd", t.betUsd, num(et, "betUsd"));
            cmp(name, "view.maxPositions", t.maxPositions, num(et, "maxPositions"));
            cmp(name, "view.stopDrop", t.stopDrop, num(et, "stopDrop"));
            cmp(name, "view.decidedLow", t.decidedLow, num(et, "decidedLow"));
            cmp(name, "view.orderTestRounds", t.orderTestRounds, num(et, "orderTestRounds"));

            TempNo.Settings p = TempNo.parseSettings(rawStr);
            cmpBool(name, "tempview.enabled", p.enabled, (Boolean) ep.get("enabled"));
            cmp(name, "tempview.bandLo", p.bandLo, num(ep, "bandLo"));
            cmp(name, "tempview.bandHi", p.bandHi, num(ep, "bandHi"));
            cmp(name, "tempview.entryHourUtc", p.entryHourUtc, num(ep, "entryHourUtc"));
            cmp(name, "tempview.reserveSlots", p.reserveSlots, num(ep, "reserveSlots"));
            cmp(name, "tempview.betUsd", p.betUsd, num(ep, "betUsd"));
        }
        System.out.println("Settings tests: " + passed + " passed, " + failed
                + " failed (" + fixtures.size() + " fixtures)");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static double num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }
}
