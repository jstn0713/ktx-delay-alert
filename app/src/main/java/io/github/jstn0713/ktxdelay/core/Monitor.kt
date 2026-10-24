package io.github.jstn0713.ktxdelay.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

sealed class AlertEvent {
    abstract val status: TrainStatus

    data class FirstDelay(override val status: TrainStatus, val delay: Int) : AlertEvent()
    data class Changed(override val status: TrainStatus, val from: Int, val to: Int) : AlertEvent()
    data class Cancelled(override val status: TrainStatus) : AlertEvent()
}

data class PollOutcome(
    /** 아직 감시 중인 열차들 */
    val statuses: List<TrainStatus>,
    val alerts: List<AlertEvent>,
    /** 이번에 감시가 끝난 열차들 (기록으로 옮길 것) */
    val finished: List<TrainStatus>,
)

/** 어떤 열차를 언제 감시할지, 조회 결과로 상태를 어떻게 바꿀지 정하는 순수 로직. */
object Monitor {
    /** 출발 예정 시각에서 이만큼 지나면 무조건 감시 종료 */
    private const val HARD_END_MIN = 180L

    fun occurrences(trains: List<WatchTrain>, from: LocalDate, days: Int): List<Occurrence> =
        (0 until days).flatMap { i ->
            val date = from.plusDays(i.toLong())
            trains.filter { it.enabled && date.dayOfWeek.value in it.days }.map { Occurrence(it, date) }
        }

    fun windowStart(occ: Occurrence, settings: AppSettings): LocalDateTime =
        occ.scheduledDep.minusMinutes(settings.startBeforeMin.toLong())

    fun isFinished(s: TrainStatus, now: LocalDateTime, settings: AppSettings): Boolean {
        if (!now.isBefore(s.scheduledDep.plusMinutes(HARD_END_MIN))) return true
        return !now.isBefore(s.expectedDep.plusMinutes(settings.endAfterDepartureMin.toLong()))
    }

    /** 지금 감시해야 하는 열차들. */
    fun activeOccurrences(
        now: LocalDateTime,
        trains: List<WatchTrain>,
        settings: AppSettings,
        skipDate: LocalDate?,
        finishedKeys: Set<String>,
        statuses: Map<String, TrainStatus>,
    ): List<Occurrence> =
        occurrences(trains, now.toLocalDate().minusDays(1), 3)
            .filter { occ ->
                occ.date != skipDate &&
                    occ.key !in finishedKeys &&
                    !now.isBefore(windowStart(occ, settings)) &&
                    run {
                        val s = statuses[occ.key]
                        if (s != null) !isFinished(s, now, settings)
                        else now.isBefore(occ.scheduledDep.plusMinutes(settings.endAfterDepartureMin.toLong()))
                    }
            }
            .sortedBy { it.scheduledDep }

    /** 다음에 감시를 시작해야 하는 시각. 지금 감시할 것이 있으면 now. 없으면 null. */
    fun nextStart(
        now: LocalDateTime,
        trains: List<WatchTrain>,
        settings: AppSettings,
        skipDate: LocalDate?,
        finishedKeys: Set<String>,
        statuses: Map<String, TrainStatus>,
    ): LocalDateTime? {
        if (activeOccurrences(now, trains, settings, skipDate, finishedKeys, statuses).isNotEmpty()) return now
        return nextOccurrence(now, trains, settings, skipDate, finishedKeys)?.let { windowStart(it, settings) }
    }

    /** 아직 감시가 시작되지 않은 다음 열차 */
    fun nextOccurrence(
        now: LocalDateTime,
        trains: List<WatchTrain>,
        settings: AppSettings,
        skipDate: LocalDate?,
        finishedKeys: Set<String> = emptySet(),
    ): Occurrence? =
        occurrences(trains, now.toLocalDate(), 9)
            .filter { it.date != skipDate && it.key !in finishedKeys && windowStart(it, settings).isAfter(now) }
            .minByOrNull { it.scheduledDep }

    /** 다음 확인까지 기다릴 시간 */
    fun pollInterval(statuses: List<TrainStatus>, now: LocalDateTime, settings: AppSettings): Duration {
        val near = statuses.any {
            Duration.between(now, it.scheduledDep).toMinutes() <= settings.fastWindowMin
        }
        val min = if (near) settings.fastIntervalMin else settings.normalIntervalMin
        return Duration.ofMinutes(min.coerceAtLeast(1).toLong())
    }

    /**
     * 같은 구간·날짜의 열차들을 한 번(또는 몇 번)의 조회로 찾는다.
     * 기본 조회에서 못 찾으면 "수서 함께 보기" 방식으로 한 번 더 조회한다.
     */
    fun searchGroup(
        dep: String,
        arr: String,
        date: LocalDate,
        occs: List<Occurrence>,
        searcher: Searcher,
    ): Pair<Map<String, KorailTrain>, String?> {
        val found = mutableMapOf<String, KorailTrain>()
        var error: String? = null
        var anySuccess = false
        for (includeSuseo in listOf(false, true)) {
            var remaining = occs.filter { it.key !in found }.sortedBy { it.scheduledDep }
            if (remaining.isEmpty()) break
            var from = searchFrom(remaining.first().train.depTime)
            var pages = 0
            while (remaining.isNotEmpty() && pages < 3) {
                pages++
                when (val res = searcher.search(dep, arr, date, from, includeSuseo)) {
                    is SearchResult.Failure -> {
                        error = res.message
                        remaining = emptyList()
                    }
                    is SearchResult.Success -> {
                        anySuccess = true
                        for (o in remaining) matchTrain(o, res.trains)?.let { found[o.key] = it }
                        remaining = remaining.filter { it.key !in found }
                        val lastDep = res.trains.mapNotNull { it.depLocalTime }.maxOrNull()
                        val beyond = remaining.filter { lastDep != null && it.train.depTime > lastDep }
                        if (beyond.isEmpty()) {
                            remaining = emptyList()
                        } else {
                            remaining = beyond
                            from = searchFrom(beyond.first().train.depTime)
                        }
                    }
                }
            }
            if (error != null && !anySuccess) break
        }
        return found to (if (anySuccess) null else error)
    }

    private fun searchFrom(t: LocalTime): LocalTime =
        if (t.hour == 0 && t.minute == 0) t else t.minusMinutes(1)

    fun matchTrain(occ: Occurrence, results: List<KorailTrain>): KorailTrain? {
        val date = occ.date.toString().replace("-", "")
        val sameDay = results.filter { it.depDate.isBlank() || it.depDate == date }
        val wantNo = occ.train.trainNo.trimStart('0')
        if (wantNo.isNotEmpty()) {
            sameDay.firstOrNull { it.trainNo == wantNo }?.let { return it }
        }
        return sameDay.firstOrNull { it.depLocalTime == occ.train.depTime }
    }

    /** 조회 결과 하나를 상태에 반영하고, 필요하면 소리 알림 이벤트를 만든다. */
    fun apply(
        prev: TrainStatus,
        train: KorailTrain?,
        error: String?,
        now: LocalDateTime,
        settings: AppSettings,
    ): Pair<TrainStatus, AlertEvent?> {
        if (train == null) {
            val next = when {
                error != null -> prev.copy(
                    lastCheckedAt = now,
                    phase = if (prev.lastDataAt == null) Phase.ERROR else prev.phase,
                    message = if (prev.lastDataAt == null) "조회 실패: $error"
                    else "조회 실패 — ${prev.lastDataAt.hm()} 정보 기준 ($error)",
                )
                prev.lastDataAt != null && !now.isBefore(prev.scheduledDep) -> prev.copy(
                    lastCheckedAt = now,
                    message = "예정 시각이 지나 조회에서 빠졌어요 — ${prev.lastDataAt.hm()} 정보 기준",
                )
                else -> prev.copy(
                    lastCheckedAt = now,
                    phase = if (prev.lastDataAt == null) Phase.NOT_FOUND else prev.phase,
                    message = if (prev.lastDataAt == null) "조회 결과에 이 열차가 없어요 (역 이름·시각·번호 확인)"
                    else "이번 조회에서 열차가 빠졌어요 — ${prev.lastDataAt.hm()} 정보 기준",
                )
            }
            return next to null
        }

        val delay = train.delayMin.coerceAtLeast(0)
        val timeline = if (prev.timeline.lastOrNull()?.delay == delay) prev.timeline
        else prev.timeline + TimelinePoint(now, delay)
        var next = prev.copy(
            label = train.label.ifBlank { prev.label },
            scheduledArr = train.arrDateTime() ?: prev.scheduledArr,
            delayMin = delay,
            delayRaw = train.delayRaw,
            phase = if (train.cancelled) Phase.CANCELLED else Phase.RUNNING,
            lastCheckedAt = now,
            lastDataAt = now,
            firstDelayAt = prev.firstDelayAt ?: if (delay > 0) now else null,
            maxDelay = maxOf(prev.maxDelay ?: 0, delay),
            timeline = timeline,
            message = null,
        )

        var alert: AlertEvent? = null
        if (train.cancelled) {
            if (prev.phase != Phase.CANCELLED) alert = AlertEvent.Cancelled(next)
        } else {
            val last = prev.lastAlertedDelay
            if (last == null) {
                if (delay >= settings.minDelayToAlert.coerceAtLeast(1)) {
                    next = next.copy(lastAlertedDelay = delay)
                    alert = AlertEvent.FirstDelay(next, delay)
                }
            } else if (abs(delay - last) >= settings.alertChangeMin.coerceAtLeast(1)) {
                next = next.copy(lastAlertedDelay = delay)
                alert = AlertEvent.Changed(next, last, delay)
            }
        }
        if (!settings.alertsEnabled) alert = null
        return next to alert
    }

    /** 한 번의 확인: 조회 → 상태 갱신 → 알림 판단 → 끝난 열차 분리. */
    fun poll(
        now: LocalDateTime,
        active: List<Occurrence>,
        prev: Map<String, TrainStatus>,
        settings: AppSettings,
        searcher: Searcher,
    ): PollOutcome {
        val updated = mutableListOf<TrainStatus>()
        val alerts = mutableListOf<AlertEvent>()
        val groups = active.groupBy { Triple(it.train.depStation, it.train.arrStation, it.date) }
        for ((g, occs) in groups) {
            val (found, error) = searchGroup(g.first, g.second, g.third, occs, searcher)
            for (occ in occs) {
                val base = prev[occ.key] ?: TrainStatus.start(occ, now)
                val (s, alert) = apply(base, found[occ.key], error, now, settings)
                updated += s
                if (alert != null) alerts += alert
            }
        }
        val (finished, still) = updated.partition { isFinished(it, now, settings) }
        // 이번에 조회하지 않았지만 시간이 지나 끝난 열차도 기록으로 넘긴다.
        // (삭제했거나 "오늘은 끄기"한 열차는 끝나지 않은 채 사라지므로 기록하지 않는다)
        val activeKeys = active.map { it.key }.toSet()
        val expired = prev.values.filter { it.key !in activeKeys && isFinished(it, now, settings) }
        return PollOutcome(still.sortedBy { it.scheduledDep }, alerts, finished + expired)
    }
}

/** 화면/알림에 쓰는 문구 */
object Texts {
    fun delayShort(s: TrainStatus): String = when (s.phase) {
        Phase.CANCELLED -> "운행 취소"
        Phase.WAITING -> "확인 중"
        Phase.NOT_FOUND -> "정보 없음"
        Phase.ERROR -> "조회 실패"
        Phase.RUNNING -> delayText(s.delayMin ?: 0)
    }

    fun delayText(delay: Int): String = if (delay <= 0) "정시" else "${delay}분 지연"

    /** 예: "천안아산 20:12 → 20:19 출발" */
    fun depLine(s: TrainStatus): String {
        val d = s.delayMin ?: 0
        return if (s.phase == Phase.RUNNING && d > 0) "${s.depStation} ${s.scheduledDep.hm()} → ${s.expectedDep.hm()} 출발"
        else "${s.depStation} ${s.scheduledDep.hm()} 출발"
    }

    /** 예: "수서 21:00 → 21:07 도착 예상" */
    fun arrLine(s: TrainStatus): String {
        val arr = s.scheduledArr ?: return "${s.arrStation} 도착"
        val d = s.delayMin ?: 0
        return if (s.phase == Phase.RUNNING && d > 0) "${s.arrStation} ${arr.hm()} → ${s.expectedArr?.hm()} 도착 예상"
        else "${s.arrStation} ${arr.hm()} 도착"
    }
}
