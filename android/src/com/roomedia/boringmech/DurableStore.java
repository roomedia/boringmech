package com.roomedia.boringmech;

import android.content.Context;
import android.content.SharedPreferences;

import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.StopLossLedger;

import java.util.ArrayList;
import java.util.List;

/**
 * 네이티브 엔진의 영속 저장소 접근.
 *
 * - 돈 관련 상태: bm_durable prefs (Bridge.durablePut과 같은 파일·같은 bm_ 키 가드).
 *   JS가 LS와 이중 기록하던 키들을 네이티브가 같은 JSON 형식으로 직접 읽고 쓴다.
 * - 로그 링·stage·업데이트 정보: bm_engine prefs. JS 시절 LS(bm_log/bm_stage/bm_update)에
 *   있던 값은 뷰어가 첫 실행에 seed*로 한 번 승계한다 (비어 있을 때만 채운다).
 */
final class DurableStore {
    private DurableStore() {}

    private static final String ENGINE_PREFS = "bm_engine";
    private static final String KEY_LOG_LINES = "log_lines";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_UPDATE_INFO = "update_info";
    private static final String KEY_SELF_RELOAD_MS = "self_reload_ms";
    private static final int LOG_CAP = 200;

    private static final Object LOG_LOCK = new Object();

    // ── bm_durable (돈 관련 상태 정본) ──────────────────────────────────────

    // Bridge.putDurable과 같은 파일 (bm_durable).
    private static final String DURABLE_PREFS = "bm_durable";

    static String durableRead(Context ctx, String key) {
        if (key == null || !key.startsWith("bm_")) {
            return null;
        }
        return ctx.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE)
                .getString(key, null);
    }

    static void durableWrite(Context ctx, String key, String value) {
        if (key == null || !key.startsWith("bm_")) {
            return;
        }
        ctx.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).commit();
    }

    private static StopLossLedger.Store sharedLedgerStore;

    /**
     * StopLossLedger.Store 어댑터 — bm_durable의 bm_stoploss 원장을 모델로
     * 읽고 쓴다. 세션이 겹쳐도 같은 인스턴스를 공유해야 StopLossLedger의
     * store 모니터 기반 read-modify-write 동기화가 성립하므로 단일
     * 인스턴스만 돌려준다.
     */
    static synchronized StopLossLedger.Store ledgerStore(Context ctx) {
        if (sharedLedgerStore == null) {
            final Context app = ctx.getApplicationContext();
            sharedLedgerStore = new StopLossLedger.Store() {
                @Override
                public Object read(String key) {
                    String raw = durableRead(app, key);
                    if (raw == null) {
                        return null;
                    }
                    try {
                        return JsonText.parse(raw);
                    } catch (Exception e) {
                        return null;
                    }
                }

                @Override
                public void write(String key, Object model) {
                    durableWrite(app, key, ModelJson.stringify(model));
                }
            };
        }
        return sharedLedgerStore;
    }

    // ── 로그 링 (bm_engine) ─────────────────────────────────────────────────

    private static SharedPreferences enginePrefs(Context ctx) {
        return ctx.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE);
    }

    static void appendLog(Context ctx, String line) {
        synchronized (LOG_LOCK) {
            List<Object> lines = new ArrayList<>();
            String raw = enginePrefs(ctx).getString(KEY_LOG_LINES, null);
            if (raw != null) {
                try {
                    Object parsed = JsonText.parse(raw);
                    if (parsed instanceof List) {
                        for (Object o : (List<?>) parsed) {
                            if (o instanceof String) {
                                lines.add(o);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            lines.add(line);
            while (lines.size() > LOG_CAP) {
                lines.remove(0);
            }
            enginePrefs(ctx).edit().putString(KEY_LOG_LINES, ModelJson.stringify(lines)).apply();
        }
    }

    /** 뷰어 readLogs가 읽는 JSON 배열 문자열. 기록이 없으면 null. */
    static String logJson(Context ctx) {
        synchronized (LOG_LOCK) {
            return enginePrefs(ctx).getString(KEY_LOG_LINES, null);
        }
    }

    /** LS bm_log 승계 — 네이티브 로그가 비어 있을 때만 채운다. 채웠으면 true. */
    static boolean seedLog(Context ctx, String json) {
        synchronized (LOG_LOCK) {
            String cur = enginePrefs(ctx).getString(KEY_LOG_LINES, null);
            if (cur != null) {
                try {
                    Object parsed = JsonText.parse(cur);
                    if (parsed instanceof List && !((List<?>) parsed).isEmpty()) {
                        return false;
                    }
                } catch (Exception ignored) {
                }
            }
            try {
                Object parsed = JsonText.parse(json);
                if (!(parsed instanceof List)) {
                    return false;
                }
                List<Object> lines = new ArrayList<>();
                for (Object o : (List<?>) parsed) {
                    if (o instanceof String) {
                        lines.add(o);
                    }
                }
                if (lines.isEmpty()) {
                    return false;
                }
                while (lines.size() > LOG_CAP) {
                    lines.remove(0);
                }
                enginePrefs(ctx).edit().putString(KEY_LOG_LINES, ModelJson.stringify(lines)).apply();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ── stage / 업데이트 정보 / 자가 재시작 시각 ────────────────────────────

    static void putStage(Context ctx, String json) {
        enginePrefs(ctx).edit().putString(KEY_STAGE, json).apply();
    }

    static String stageJson(Context ctx) {
        return enginePrefs(ctx).getString(KEY_STAGE, null);
    }

    /** LS bm_stage 승계 — 네이티브 stage가 없을 때만 채운다. */
    static boolean seedStage(Context ctx, String json) {
        if (enginePrefs(ctx).getString(KEY_STAGE, null) != null) {
            return false;
        }
        try {
            Object parsed = JsonText.parse(json);
            if (!(parsed instanceof java.util.Map) || !Js.truthy(Js.field(parsed, "stage"))) {
                return false;
            }
            enginePrefs(ctx).edit().putString(KEY_STAGE, ModelJson.stringify(parsed)).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void putUpdateInfo(Context ctx, String json) {
        enginePrefs(ctx).edit().putString(KEY_UPDATE_INFO, json).apply();
    }

    static String updateInfoJson(Context ctx) {
        return enginePrefs(ctx).getString(KEY_UPDATE_INFO, null);
    }

    static long selfReloadMs(Context ctx) {
        return enginePrefs(ctx).getLong(KEY_SELF_RELOAD_MS, 0L);
    }

    static void saveSelfReloadMs(Context ctx, long ms) {
        enginePrefs(ctx).edit().putLong(KEY_SELF_RELOAD_MS, ms).apply();
    }
}
