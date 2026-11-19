package com.roomedia.boringmech;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

// 외부 감시 알람 장전 — AlarmManager에 inexact 알람을 걸어 둔다.
// 알람은 시스템이 보관하므로 앱 프로세스가 죽어도 남고, 발화하면
// ExternalWatchdogReceiver가 프로세스를 깨워 판정한다. 같은
// PendingIntent로 다시 걸면 기존 알람이 교체되므로 장전은 멱등하다.
//
// setAndAllowWhileIdle을 쓴다 — Doze에서도 발화하는 대신 시스템이
// 간격을 늦출 수 있어(inexact) 감지 지연이 생길 수 있지만, 절전 중
// 전혀 깨지 않는 것보다는 낫다. 정확한 시각이 필요한 감시가 아니다.
public final class ExternalWatchdogScheduler {
    private static final int REQUEST_CODE = 4101;

    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long triggerAt = System.currentTimeMillis() + ExternalWatchdog.ALARM_INTERVAL_MS;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(ctx));
        } catch (Exception ignored) {
            // 장전 실패는 감시가 없다는 뜻이라 치명적이지만, 호출자
            // (서비스·리시버)를 죽일 이유는 없다. 다음 장전 기회에 다시 건다.
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pendingIntent(ctx);
        try {
            am.cancel(pi);
            pi.cancel();
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, ExternalWatchdogReceiver.class)
                .setAction(ExternalWatchdogReceiver.ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags);
    }

    private ExternalWatchdogScheduler() {
    }
}
