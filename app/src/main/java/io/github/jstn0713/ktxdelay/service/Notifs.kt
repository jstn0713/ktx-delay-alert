package io.github.jstn0713.ktxdelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jstn0713.ktxdelay.R
import io.github.jstn0713.ktxdelay.core.AlertEvent
import io.github.jstn0713.ktxdelay.core.Phase
import io.github.jstn0713.ktxdelay.core.TrainStatus
import io.github.jstn0713.ktxdelay.core.Texts
import io.github.jstn0713.ktxdelay.core.hm
import io.github.jstn0713.ktxdelay.ui.MainActivity
import java.time.LocalDateTime

object Notifs {
    const val CH_STATUS = "status"
    const val CH_ALERT = "alert"
    const val ID_STATUS = 1
    private const val ID_CANNOT_START = 2
    private const val MAX_ROWS = 4

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "실시간 지연 표시 (상단바)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "열차를 확인하는 동안 상단 알림바에 계속 떠 있는 지연 정보 (소리 없음)"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "지연 발생·변경 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "처음 지연이 생기거나, 지연 시간이 크게 바뀌거나, 운행이 취소되면 소리·진동으로 알림"
                enableVibration(true)
            },
        )
    }

    private fun openAppIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun serviceIntent(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            ctx, code,
            Intent(ctx, MonitorService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun delayColor(ctx: Context, s: TrainStatus): Int? = when {
        s.phase == Phase.CANCELLED -> ctx.getColor(R.color.delay_red)
        s.phase == Phase.RUNNING && (s.delayMin ?: 0) > 0 -> ctx.getColor(R.color.delay_red)
        s.phase == Phase.RUNNING -> ctx.getColor(R.color.on_time_green)
        else -> null
    }

    /** 예: "20:12 출발 → 20:19 · 수서 21:07 도착" (한 줄용 짧은 문구) */
    fun compactLine(s: TrainStatus): String {
        val d = s.delayMin ?: 0
        val delayed = s.phase == Phase.RUNNING && d > 0
        val dep = if (delayed) "${s.scheduledDep.hm()} → ${s.expectedDep.hm()} 출발" else "${s.scheduledDep.hm()} 출발"
        val arrTime = (if (delayed) s.expectedArr else s.scheduledArr)?.hm()
        val arr = if (arrTime != null) "${s.arrStation} ${arrTime} 도착${if (delayed) " 예상" else ""}" else "${s.arrStation}행"
        return "$dep · $arr"
    }

    /** 상단바에 계속 떠 있는 알림 */
    fun buildStatus(
        ctx: Context,
        statuses: List<TrainStatus>,
        lastPoll: LocalDateTime?,
        intervalMin: Int?,
    ): Notification {
        val b = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(ctx))
            .addAction(0, "지금 확인", serviceIntent(ctx, MonitorService.ACTION_REFRESH, 1))
            .addAction(0, "오늘은 끄기", serviceIntent(ctx, MonitorService.ACTION_SKIP_TODAY, 2))

        val primary = statuses.firstOrNull()
        if (primary == null) {
            return b.setContentTitle("KTX 지연 알림")
                .setContentText("열차 정보를 확인하고 있어요…")
                .build()
        }

        val footer = buildString {
            if (lastPoll != null) append("${lastPoll.hm()} 확인")
            if (intervalMin != null) append(" · ${intervalMin}분마다 확인 중")
        }
        val others = statuses.drop(1)
        val title = "${Texts.delayShort(primary)} · ${primary.label} (${primary.scheduledDep.hm()})"
        val detail = if (others.isEmpty()) compactLine(primary)
        else "다른 열차: " + others.joinToString(" · ") { "${it.scheduledDep.hm()} ${Texts.delayShort(it)}" }

        // 접힌 상태: 지연 시간을 크게
        val collapsed = RemoteViews(ctx.packageName, R.layout.notif_collapsed).apply {
            setTextViewText(R.id.delay, Texts.delayShort(primary))
            delayColor(ctx, primary)?.let { setTextColor(R.id.delay, it) }
            setTextViewText(R.id.title, "${primary.label} · ${primary.depStation} ${primary.scheduledDep.hm()}")
            setTextViewText(R.id.detail, detail)
        }

        // 펼친 상태: 열차별로 한 줄씩
        val expanded = RemoteViews(ctx.packageName, R.layout.notif_expanded).apply {
            removeAllViews(R.id.rows)
            for (s in statuses.take(MAX_ROWS)) {
                val row = RemoteViews(ctx.packageName, R.layout.notif_row)
                row.setTextViewText(R.id.row_title, "${s.scheduledDep.hm()} ${s.label}")
                row.setTextViewText(R.id.row_delay, Texts.delayShort(s))
                delayColor(ctx, s)?.let { row.setTextColor(R.id.row_delay, it) }
                row.setTextViewText(R.id.row_detail, s.message ?: compactLine(s))
                addView(R.id.rows, row)
            }
            setTextViewText(R.id.footer, footer)
        }

        return b.setContentTitle(title)
            .setContentText(detail)
            .setSubText(if (lastPoll != null) "${lastPoll.hm()} 확인" else null)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .build()
    }

    fun updateStatus(ctx: Context, n: Notification) = safeNotify(ctx, ID_STATUS, n)

    /** 소리·진동 알림 */
    fun postAlert(ctx: Context, e: AlertEvent) {
        val s = e.status
        val title = when (e) {
            is AlertEvent.FirstDelay -> "${s.label} ${e.delay}분 지연 예상"
            is AlertEvent.Changed ->
                if (e.to == 0) "${s.label} 정시 운행으로 회복 (${e.from}분 → 정시)"
                else "${s.label} 지연 ${e.from}분 → ${e.to}분"
            is AlertEvent.Cancelled -> "${s.label} 운행 취소/중지"
        }
        val text = if (e is AlertEvent.Cancelled) {
            "${s.depStation} ${s.scheduledDep.hm()} 출발 열차예요. 코레일 안내를 확인하세요."
        } else {
            "${Texts.depLine(s)}\n${Texts.arrLine(s)}"
        }
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle(title)
            .setContentText(text.replace("\n", " · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(90 * 60 * 1000L)
            .setContentIntent(openAppIntent(ctx))
            .build()
        safeNotify(ctx, 1000 + (s.key.hashCode() and 0x7fff), n)
    }

    /** 휴대폰이 백그라운드 시작을 막았을 때 */
    fun postCannotStart(ctx: Context) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle("KTX 지연 확인을 시작하지 못했어요")
            .setContentText("탭해서 앱을 열면 바로 시작해요. (배터리 설정을 '제한 없음'으로 바꾸면 해결돼요)")
            .setStyle(NotificationCompat.BigTextStyle().bigText("탭해서 앱을 열면 바로 시작해요. (배터리 설정을 '제한 없음'으로 바꾸면 해결돼요)"))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        safeNotify(ctx, ID_CANNOT_START, n)
    }

    private fun safeNotify(ctx: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // 알림 권한 없음
        }
    }
}
