package com.roomedia.boringmech;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

// 외부 감시 리시버 — 프로세스 밖에서 엔진을 감시하는 최외곽 층.
// AlarmManager 알람(체인)과 BOOT_COMPLETED가 이 리시버를 깨운다.
// 프로세스가 죽어 있어도 시스템이 이 리시버를 위해 프로세스를 띄우므로,
// 내부 워치독(같은 프로세스)이 함께 죽는 구조적 한계를 벗어난다.
//
// 하는 일은 가볍게 유지한다: 상태 확인, 판정(ExternalWatchdog 순수
// 로직), 필요하면 EngineService 재기동, 다음 알람 재장전. 긴 작업은
// 하지 않는다 — 실제 복구 작업은 서비스가 맡는다.
public class ExternalWatchdogReceiver extends BroadcastReceiver {
    public static final String ACTION_TICK =
            "com.roomedia.boringmech.EXT_WATCHDOG_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        if (!boot && !ACTION_TICK.equals(action)) {
            return;
        }
        Context ctx = context.getApplicationContext();
        try {
            ExternalWatchdog.Input in = new ExternalWatchdog.Input();
            in.now = System.currentTimeMillis();
            in.runIntent = EngineService.getRunIntent(ctx);
            in.heartbeatMs = EngineService.getHeartbeatMs(ctx);
            in.cycleChangedMs = EngineService.getCycleChangedMs(ctx);
            in.progressMs = EngineService.getProgressMs(ctx);
            in.lastExternalRestartMs = EngineService.getLastExternalRestartMs(ctx);

            if (ExternalWatchdog.shouldRestart(in)) {
                restartEngine(ctx, in, boot);
            }
        } catch (Throwable ignored) {
            // 리시버가 죽으면 체인 재장전까지 못 가 감시가 끊긴다 —
            // 판정·재기동 실패는 여기서 삼키고 재장전은 반드시 수행한다.
        } finally {
            // 체인 재장전 — 실행 의도가 켜져 있을 때만 이어간다. 꺼져
            // 있으면 감시 대상이 아니므로 체인을 자연히 종료한다
            // (다시 켜면 서비스 시작 경로가 새로 장전한다).
            try {
                if (EngineService.getRunIntent(ctx)) {
                    ExternalWatchdogScheduler.schedule(ctx);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void restartEngine(Context ctx, ExternalWatchdog.Input in, boolean boot) {
        // 재기동 기록을 먼저 남긴다 — 시작이 실패하거나 시스템이 막아도
        // 쿨다운이 걸려 반복 시도가 폭주하지 않고, 제보에서 "외부 감시가
        // 실제로 발동했는가"를 원격으로 가를 수 있다.
        EngineService.recordExternalRestart(ctx);
        long ageSec = ExternalWatchdog.stallAgeSec(in);
        String reason = boot && ageSec < 0
                ? "기기 재부팅 후 실행 의도 복원"
                : ageSec >= 0
                        ? "엔진 활동 " + ageSec + "초 정지 (상한 "
                                + (ExternalWatchdog.STALL_LIMIT_MS / 1000) + "초)"
                        : "엔진 활동 기록 없음";
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        DurableStore.appendLog(ctx, "[" + ts + "] 외부 감시 재기동 — " + reason
                + ", EngineService를 재기동합니다");
        try {
            Intent service = new Intent(ctx, EngineService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(service);
            } else {
                ctx.startService(service);
            }
        } catch (Throwable t) {
            // Android 12+의 백그라운드 FGS 시작 제한 등에 막힌 경우 —
            // 조용히 죽지 않고 로그와 오류 알림으로 남긴다. 사용자가
            // 앱을 열면 실행 의도 복원으로 엔진이 재개된다.
            DurableStore.appendLog(ctx, "[" + ts + "] 외부 감시 재기동 실패 — "
                    + "서비스 시작이 거부됐습니다: " + t);
            Bridge.notifyError(ctx,
                    com.roomedia.boringmech.write.NotifyPolicy.KEY_EXT_WATCHDOG_BLOCKED,
                    "엔진 재기동 실패",
                    "외부 감시가 서비스 시작을 거부당했습니다. 앱을 열면 엔진이 재개됩니다.");
        }
    }
}
