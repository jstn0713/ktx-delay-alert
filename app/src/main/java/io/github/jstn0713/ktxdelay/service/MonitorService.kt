package io.github.jstn0713.ktxdelay.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.jstn0713.ktxdelay.core.HistoryRecord
import io.github.jstn0713.ktxdelay.core.KorailClient
import io.github.jstn0713.ktxdelay.core.Monitor
import io.github.jstn0713.ktxdelay.core.nowKst
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * 열차 출발 전 정해진 시간 동안 코레일을 주기적으로 조회하고
 * 상단 알림바에 지연 시간을 계속 보여주는 서비스.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppRepo.init(this)
        goForeground()
        when (intent?.action) {
            ACTION_SKIP_TODAY -> AppRepo.setSkipToday(true)
        }
        if (loopJob?.isActive == true) {
            wake.trySend(Unit)
        } else {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private fun currentNotification() = Notifs.buildStatus(
        this,
        AppRepo.statuses.value,
        AppRepo.lastPollAt.value,
        AppRepo.pollIntervalMin.value,
    )

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifs.ID_STATUS, currentNotification(), type)
    }

    private suspend fun runLoop() {
        acquireWakeLock()
        AppRepo.monitoring.value = true
        try {
            while (true) {
                val now = nowKst()
                val settings = AppRepo.settings.value
                val prev = AppRepo.statusMap()
                val active = Monitor.activeOccurrences(
                    now, AppRepo.trains.value, settings, AppRepo.skipDate.value, AppRepo.finishedKeys(), prev,
                )
                val outcome = try {
                    Monitor.poll(now, active, prev, settings, KorailClient(settings.korailVersion))
                } catch (e: Exception) {
                    null
                }
                if (outcome == null) {
                    // 예상 못 한 오류 — 잠시 뒤 다시 시도
                    waitFor(Duration.ofMinutes(1))
                    continue
                } else {
                    if (outcome.finished.isNotEmpty()) {
                        AppRepo.addHistory(outcome.finished.map { HistoryRecord.from(it) })
                        AppRepo.addFinished(outcome.finished.map { it.key })
                    }
                    AppRepo.setStatuses(outcome.statuses)
                    if (active.isNotEmpty()) AppRepo.lastPollAt.value = now
                    outcome.alerts.forEach { Notifs.postAlert(this, it) }
                }

                val statuses = AppRepo.statuses.value
                if (statuses.isEmpty()) {
                    // 곧 시작할 열차가 있으면 서비스를 유지하고 잠깐 기다린다
                    val next = Monitor.nextStart(
                        nowKst(), AppRepo.trains.value, settings, AppRepo.skipDate.value, AppRepo.finishedKeys(), emptyMap(),
                    )
                    val wait = next?.let { Duration.between(nowKst(), it) }
                    if (wait != null && wait <= Duration.ofMinutes(3)) {
                        WidgetUpdater.update(this)
                        waitFor(wait.coerceAtLeast(Duration.ofSeconds(1)))
                        continue
                    }
                    break
                }

                val interval = Monitor.pollInterval(statuses, nowKst(), settings)
                AppRepo.pollIntervalMin.value = interval.toMinutes().toInt()
                Notifs.updateStatus(this, currentNotification())
                WidgetUpdater.update(this)
                waitFor(interval)
            }
        } finally {
            AppRepo.monitoring.value = false
            AppRepo.pollIntervalMin.value = null
            releaseWakeLock()
        }
        Scheduling.reschedule(this)
        WidgetUpdater.update(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 정해진 시간만큼 기다린다. "지금 확인"을 누르면 바로 깨어난다. */
    private suspend fun waitFor(d: Duration) {
        withTimeoutOrNull(d.toMillis()) { wake.receive() }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ktxdelay:monitor").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // 이미 해제됨
        }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        AppRepo.monitoring.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_REFRESH = "io.github.jstn0713.ktxdelay.REFRESH"
        const val ACTION_SKIP_TODAY = "io.github.jstn0713.ktxdelay.SKIP_TODAY"

        /** 감시 시작 (이미 실행 중이면 바로 다시 확인) */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            true
        } catch (e: Exception) {
            // 안드로이드 12+ 에서 백그라운드 시작이 막힌 경우
            Notifs.postCannotStart(ctx)
            false
        }

        /** 실행 중일 때만 "지금 확인" */
        fun refreshIfRunning(ctx: Context) {
            if (!AppRepo.monitoring.value) return
            try {
                ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_REFRESH))
            } catch (e: Exception) {
                // 무시
            }
        }
    }
}
