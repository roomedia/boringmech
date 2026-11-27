// 자금 배분 계층 패리티 JVM 테스트 — 공리적 분리 2단계.
// 1) 픽스처 대조: 리팩터 전(v0.3.57) JS 순수 판정 + 드라이버 식으로
//    캡처한 기준선(app/test/fixtures/allocation-parity.json)과
//    CycleAllocation 출력을 전수 대조한다 (JS allocation.test.mjs와
//    같은 픽스처).
// 2) 라이브 교차 대조: 배분 판정과 TempNo 순수 판정을 격자로 직접
//    맞붙인다.
// 3) 구조 가드: NativeCycle이 배분 결과만 소비하고 TempNo 판정을
//    직접 호출하지 않는지 소스로 확인한다.
// 실행: android/test/run_cycle_allocation_test.sh
import com.roomedia.boringmech.engine.CycleAllocation;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.TempNo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class CycleAllocationTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void eq(String label, boolean actual, boolean expected) {
        if (actual == expected) {
            passed++;
        } else {
            failed++;
            if (failed <= 20) {
                System.out.println("FAIL: " + label + " expected=" + expected + " got=" + actual);
            }
        }
    }

    private static void eqNum(String label, double actual, double expected) {
        if (actual == expected) {
            passed++;
        } else {
            failed++;
            if (failed <= 20) {
                System.out.println("FAIL: " + label + " expected=" + expected + " got=" + actual);
            }
        }
    }

    private static void eqFloor(String label, Double actual, Double expected) {
        boolean same = actual == null ? expected == null
                : expected != null && actual.doubleValue() == expected.doubleValue();
        if (same) {
            passed++;
        } else {
            failed++;
            if (failed <= 20) {
                System.out.println("FAIL: " + label + " expected=" + expected + " got=" + actual);
            }
        }
    }

    private static double num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).doubleValue();
    }

    private static int inum(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).intValue();
    }

    private static boolean bool(Map<String, Object> m, String k) {
        return (Boolean) m.get(k);
    }

    private static String strat(Map<String, Object> m) {
        Object v = m.get("strategy");
        return v == null ? null : (String) v;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, Object> fixture = (Map<String, Object>) JsonText.parse(
                Files.readString(Path.of(args[0])));
        List<Object> scenarios = (List<Object>) fixture.get("scenarios");
        for (Object so : scenarios) {
            Map<String, Object> sc = (Map<String, Object>) so;
            String name = (String) sc.get("name");
            Map<String, Object> inp = (Map<String, Object>) sc.get("input");
            Map<String, Object> exp = (Map<String, Object>) sc.get("expect");
            boolean signal = bool(inp, "holdFailed") || bool(inp, "holdIncomplete")
                    || bool(inp, "holdUpcoming");
            CycleAllocation alloc = CycleAllocation.compute(
                    inum(inp, "slots"), num(inp, "bet"), num(inp, "cashFloor"),
                    num(inp, "cash"), inum(inp, "openCount"),
                    inum(inp, "maxPositions"), inum(inp, "verifyCap"), num(inp, "reserveUsd"),
                    bool(inp, "tempEnabled"), inum(inp, "tempReserveSlots"),
                    num(inp, "tempBetUsd"), signal);
            eqNum(name + ": reserveSlotsEff", alloc.reserveSlotsEff, inum(exp, "reserveSlotsEff"));
            eq(name + ": tempHold", alloc.tempHold, bool(exp, "tempHold"));
            eqNum(name + ": laneSlots", alloc.laneSlots, inum(exp, "laneSlots"));
            Object expFloor = exp.get("cashGuardFloor");
            eqFloor(name + ": cashGuardFloor", alloc.cashGuardFloor,
                    expFloor == null ? null : ((Number) expFloor).doubleValue());
            eqNum(name + ": mainSlots", alloc.mainSlots, inum(inp, "slots"));
            eqNum(name + ": mainBet", alloc.mainBet, num(inp, "bet"));
            eq(name + ": laneEntryPossible",
                    CycleAllocation.laneEntryPossible(
                            bool(inp, "tempEnabled"), inum(inp, "tempReserveSlots"),
                            inum(inp, "maxPositions"), inum(inp, "openCount"),
                            num(inp, "tempBetUsd"), num(inp, "cash"), num(inp, "reserveUsd")),
                    bool(exp, "laneEntryPossible"));
            for (Object bo : (List<Object>) sc.get("blockSteps")) {
                Map<String, Object> st = (Map<String, Object>) bo;
                eq(name + ": blocksMain " + st,
                        alloc.blocksMain(strat(st), inum(st, "slotsLeft"), inum(st, "verified")),
                        bool(st, "expect"));
            }
            for (Object lo : (List<Object>) sc.get("laneSteps")) {
                Map<String, Object> st = (Map<String, Object>) lo;
                eq(name + ": laneAllows " + st,
                        alloc.laneAllows(strat(st), inum(st, "laneLeft"),
                                inum(st, "positionsUsed"), num(st, "cashLeft")),
                        bool(st, "expectAllows"));
                eq(name + ": laneCashBlocked " + st,
                        alloc.laneCashBlocked(strat(st), inum(st, "laneLeft"),
                                inum(st, "positionsUsed"), num(st, "cashLeft")),
                        bool(st, "expectCashBlocked"));
            }
            for (Object go : (List<Object>) sc.get("guardSteps")) {
                Map<String, Object> st = (Map<String, Object>) go;
                eq(name + ": cashGuardBlocks " + st,
                        alloc.cashGuardBlocks(strat(st), num(st, "cashLeft"), num(st, "bet")),
                        bool(st, "expect"));
            }
        }

        crossCheck();
        structuralGuards(args[1]);

        System.out.println("CycleAllocation tests: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // 라이브 교차 대조 — 배분 판정 vs TempNo 순수 판정 (비유한 값 포함).
    private static void crossCheck() {
        double[] cashProbes = {0, 3.57, 5.49, 5.5, 8.67, 8.87, 13.86, 13.87, 20, 100,
                Double.NaN, Double.POSITIVE_INFINITY};
        String[] strategies = {null, "temp_no", "other"};
        for (boolean enabled : new boolean[]{true, false}) {
            for (int reserve : new int[]{0, 1, 2, 3}) {
                for (double tempBet : new double[]{0, 3, 5, 6, Double.NaN}) {
                    for (boolean signal : new boolean[]{true, false}) {
                        CycleAllocation alloc = CycleAllocation.compute(
                                3, 5, 2.87, 50, 4, 70, 60, 2.87,
                                enabled, reserve, tempBet, signal);
                        int eff = TempNo.effectiveReserveSlots(reserve, 70);
                        boolean hold = enabled && eff > 0 && signal;
                        for (String strategy : strategies) {
                            for (int slotsLeft : new int[]{0, 1, 2, 4}) {
                                for (int verified : new int[]{0, 58, 59, 60}) {
                                    eq("cross blocksMain",
                                            alloc.blocksMain(strategy, slotsLeft, verified),
                                            hold && TempNo.reserveBlocksMain(
                                                    strategy, slotsLeft, verified, 60, eff));
                                }
                            }
                            for (int laneLeft : new int[]{0, 1, 2}) {
                                for (int positionsUsed : new int[]{0, 69, 70}) {
                                    for (double cashLeft : cashProbes) {
                                        eq("cross laneAllows",
                                                alloc.laneAllows(strategy, laneLeft, positionsUsed, cashLeft),
                                                TempNo.reserveLaneAllows(strategy, hold, laneLeft,
                                                        positionsUsed, 70, cashLeft, tempBet, 2.87));
                                        eq("cross laneCashBlocked",
                                                alloc.laneCashBlocked(strategy, laneLeft, positionsUsed, cashLeft),
                                                TempNo.laneCashBlocked(strategy, hold, laneLeft,
                                                        positionsUsed, 70, cashLeft, tempBet, 2.87));
                                        eq("cross cashGuardBlocks",
                                                alloc.cashGuardBlocks(strategy, cashLeft, 5),
                                                TempNo.cashGuardBlocks(strategy, enabled, eff,
                                                        tempBet, 2.87, cashLeft, 5));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 구조 가드 — 드라이버가 배분 결과만 소비하는지.
    private static void structuralGuards(String nativeCyclePath) throws Exception {
        String src = Files.readString(Path.of(nativeCyclePath));
        eq("guard: driver computes allocation", src.contains("CycleAllocation.compute("), true);
        eq("guard: lane entry via allocation", src.contains("CycleAllocation.laneEntryPossible("), true);
        eq("guard: blocks via allocation", src.contains("alloc.blocksMain("), true);
        eq("guard: lane via allocation", src.contains("alloc.laneAllows("), true);
        eq("guard: guard via allocation", src.contains("alloc.cashGuardBlocks("), true);
        eq("guard: lane budget from allocation", src.contains("int laneLeft = alloc.laneSlots;"), true);
        for (String direct : new String[]{
                "TempNo.reserveBlocksMain(", "TempNo.reserveLaneAllows(",
                "TempNo.laneCashBlocked(", "TempNo.cashGuardBlocks(",
                "TempNo.cashGuardFloor(", "TempNo.effectiveReserveSlots("}) {
            eq("guard: no direct " + direct, src.contains(direct), false);
        }
        // 단계 실패 격리 가드 (2026-11-27) — runCycle의 9단계 전부가 격리
        // 경계로 감싸져 있고, runIteration이 격리 기록으로 오류 사이클을
        // 판정하는지.
        for (String stage : new String[]{
                "snapshot", "stoploss", "redeem", "settle", "prescan",
                "scan", "selection", "shadow", "summary"}) {
            eq("guard: stage isolated " + stage,
                    src.contains("isolateStage(ctx, \"" + stage + "\""), true);
        }
        eq("guard: isolation records failure",
                src.contains("ctx.stageFailures.add(new CycleContext.StageFailure("), true);
        eq("guard: iteration counts isolated failures",
                src.contains("!ctx.stageFailures.isEmpty()"), true);
    }
}
