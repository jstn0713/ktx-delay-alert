package io.github.jstn0713.ktxdelay.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.jstn0713.ktxdelay.core.KST
import io.github.jstn0713.ktxdelay.core.Monitor
import io.github.jstn0713.ktxdelay.core.nowKst
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.widget.WidgetUpdater

/** 다음 감시 시작 시각에 맞춰 알람을 예약한다. */
object Scheduling {
    private const val ACTION_ALARM = "io.github.jstn0713.ktxdelay.ALARM"

    fun reschedule(ctx: Context) {
        AppRepo.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(ctx)
        am.cancel(pi)

        val now = nowKst()
        val next = Monitor.nextStart(
            now, AppRepo.trains.value, AppRepo.settings.value, AppRepo.skipDate.value,
            AppRepo.finishedKeys(), AppRepo.statusMap(),
        ) ?: return
        // 이미 감시 중이면 그대로 둔다
        if (AppRepo.monitoring.value && !next.isAfter(now)) return

        val at = maxOf(next.atZone(KST).toInstant().toEpochMilli(), System.currentTimeMillis() + 1_500)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** 예약한 시각이 되면 감시 서비스를 시작 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppRepo.init(context)
        MonitorService.start(context)
    }
}

/** 휴대폰을 껐다 켜거나 앱이 업데이트되면 알람을 다시 예약 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppRepo.init(context)
        Scheduling.reschedule(context)
        WidgetUpdater.update(context)
    }
}
