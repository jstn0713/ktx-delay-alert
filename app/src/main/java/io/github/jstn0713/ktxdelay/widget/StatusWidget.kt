package io.github.jstn0713.ktxdelay.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.jstn0713.ktxdelay.R
import io.github.jstn0713.ktxdelay.core.Monitor
import io.github.jstn0713.ktxdelay.core.Phase
import io.github.jstn0713.ktxdelay.core.Texts
import io.github.jstn0713.ktxdelay.core.hm
import io.github.jstn0713.ktxdelay.core.nowKst
import io.github.jstn0713.ktxdelay.core.shortLabel
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.service.Notifs
import io.github.jstn0713.ktxdelay.ui.MainActivity

/** 홈 화면 위젯 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        AppRepo.init(context)
        WidgetUpdater.update(context)
    }
}

object WidgetUpdater {
    private val ROWS = listOf(
        intArrayOf(R.id.row1, R.id.row1_title, R.id.row1_delay, R.id.row1_detail),
        intArrayOf(R.id.row2, R.id.row2_title, R.id.row2_delay, R.id.row2_detail),
        intArrayOf(R.id.row3, R.id.row3_title, R.id.row3_delay, R.id.row3_detail),
    )

    fun update(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, build(ctx))
        } catch (e: Exception) {
            // 위젯 갱신 실패는 무시
        }
    }

    private fun build(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_status)
        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_root, open)

        val now = nowKst()
        val settings = AppRepo.settings.value
        val live = AppRepo.statuses.value.filter { !Monitor.isFinished(it, now, settings) }

        if (live.isNotEmpty()) {
            v.setTextViewText(R.id.widget_time, AppRepo.lastPollAt.value?.let { "${it.hm()} 확인" } ?: "확인 중")
            v.setViewVisibility(R.id.widget_message, View.GONE)
            ROWS.forEachIndexed { i, ids ->
                val s = live.getOrNull(i)
                if (s == null) {
                    v.setViewVisibility(ids[0], View.GONE)
                } else {
                    v.setViewVisibility(ids[0], View.VISIBLE)
                    v.setTextViewText(ids[1], "${s.scheduledDep.hm()} ${s.label}")
                    v.setTextViewText(ids[2], Texts.delayShort(s))
                    val color = when {
                        s.phase == Phase.CANCELLED || (s.phase == Phase.RUNNING && (s.delayMin ?: 0) > 0) -> R.color.delay_red
                        s.phase == Phase.RUNNING -> R.color.on_time_green
                        else -> R.color.widget_text_secondary
                    }
                    v.setTextColor(ids[2], ctx.getColor(color))
                    v.setTextViewText(ids[3], s.message ?: Notifs.compactLine(s))
                }
            }
        } else {
            ROWS.forEach { v.setViewVisibility(it[0], View.GONE) }
            v.setViewVisibility(R.id.widget_message, View.VISIBLE)
            v.setTextViewText(R.id.widget_time, "")
            val trains = AppRepo.trains.value
            val skip = AppRepo.isSkippedToday()
            val next = Monitor.nextOccurrence(now, trains, settings, AppRepo.skipDate.value, AppRepo.finishedKeys())
            val msg = when {
                trains.none { it.enabled } -> "등록된 열차가 없어요.\n앱에서 타는 열차를 추가하세요."
                next == null -> "다음 열차가 없어요."
                else -> {
                    val start = Monitor.windowStart(next, settings)
                    (if (skip) "오늘은 알림을 껐어요.\n" else "") +
                        "다음: ${next.date.shortLabel()} ${next.train.depTime.hm()} ${next.train.label}\n" +
                        "${start.hm()}부터 지연 확인"
                }
            }
            v.setTextViewText(R.id.widget_message, msg)
        }
        return v
    }
}
