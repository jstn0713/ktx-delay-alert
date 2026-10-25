package io.github.jstn0713.ktxdelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MonitorTest {
    private val settings = AppSettings()

    private fun dt(d: LocalDate, h: Int, m: Int): LocalDateTime = LocalDateTime.of(d, LocalTime.of(h, m))

    // 2026-10-28 은 수요일, 2026-10-25 는 일요일
    private val wed = LocalDate.of(2026, 10, 28)
    private val sun = LocalDate.of(2026, 10, 25)

    private fun watch(id: String, day: Int, time: String, no: String) = WatchTrain(
        id = id, days = setOf(day), depStation = "천안아산", arrStation = "수서",
        depTime = LocalTime.parse(time), trainNo = no,
    )

    private val trains = listOf(
        watch("sun324", 7, "12:49", "324"),
        watch("wed1945", 3, "19:45", "350"),
        watch("wed1954", 3, "19:54", "352"),
        watch("wed2012", 3, "20:12", "614"),
        watch("thu346", 4, "18:05", "346"),
    )

    /** 천안아산→수서 열차 하나를 만든다. */
    private fun train(no: String, depDelay: Int, arrDelay: Int = depDelay, cancelled: Boolean = false): NaverTrain {
        val stops = listOf(
            NaverStop("0041", "목포", LocalTime.of(10, 0), null, 0, 1),
            NaverStop("0502", "천안아산", LocalTime.of(20, 12), LocalTime.of(20, 10), depDelay, 6),
            NaverStop("0551", "수서", null, LocalTime.of(21, 0), arrDelay, 8),
        )
        return NaverTrain(no.padStart(5, '0'), no, "KTX-산천", "KORAIL", "r", "목포", "수서", stops, cancelled, "{}")
    }

    /** 번호별로 응답을 바꿔 돌려주는 가짜 네이버 */
    private class FakeSource : TrainSource {
        var byNo: (String) -> LookupResult = { LookupResult.NotFound() }
        val calls = mutableListOf<String>()
        override fun lookup(trainNo: String, company: String, date: LocalDate): LookupResult {
            calls += trainNo
            return byNo(padTrainNo(trainNo))
        }
    }

    // ---- 일정 (데이터 소스와 무관) ----

    @Test
    fun nothingActiveBeforeWindow() {
        val now = dt(wed, 18, 44)
        assertTrue(Monitor.activeOccurrences(now, trains, settings, null, emptySet(), emptyMap()).isEmpty())
        assertEquals(dt(wed, 18, 45), Monitor.nextStart(now, trains, settings, null, emptySet(), emptyMap()))
    }

    @Test
    fun wednesdayWindowsOverlap() {
        val at = { h: Int, m: Int ->
            Monitor.activeOccurrences(dt(wed, h, m), trains, settings, null, emptySet(), emptyMap()).map { it.train.id }
        }
        assertEquals(listOf("wed1945"), at(18, 45))
        assertEquals(listOf("wed1945", "wed1954"), at(18, 54))
        assertEquals(listOf("wed1945", "wed1954", "wed2012"), at(19, 12))
        // 19:45 열차를 아직 한 번도 확인 안 했다면, 예정 시각이 지나도 지연 여부를 확인하러 포함
        assertEquals(listOf("wed1945", "wed1954", "wed2012"), at(19, 50))
    }

    // ---- 예정 시각이 지난 뒤 처음 확인하는 열차 (지연 중일 수 있음) ----

    private val sun262 = watch("sun262", 7, "13:18", "262")

    @Test
    fun lateJoinDelayedTrainIsStillMonitored() {
        // 13:18 예정 열차를 13:24 에 처음 확인. 실제로는 13분 지연 → 13:31 출발
        val src = FakeSource().apply { byNo = { LookupResult.Success(train("262", 13)) } }
        val now = dt(sun, 13, 24)
        val active = Monitor.activeOccurrences(now, listOf(sun262), settings, null, emptySet(), emptyMap())
        assertEquals(listOf("sun262"), active.map { it.train.id })

        val out = Monitor.poll(now, active, emptyMap(), settings, src)
        val s = out.statuses.single()
        assertEquals(13, s.delayMin)
        assertEquals(dt(sun, 13, 31), s.expectedDep)
        assertTrue(out.finished.isEmpty())
        assertTrue(Monitor.shouldRecord(s)) // 떠나기 전에 확인했으니 기록 대상

        // 13:31 출발 + 5분 = 13:36 에 종료
        val prev = out.statuses.associateBy { it.key }
        assertEquals(1, Monitor.activeOccurrences(dt(sun, 13, 35), listOf(sun262), settings, null, emptySet(), prev).size)
        assertTrue(Monitor.activeOccurrences(dt(sun, 13, 36), listOf(sun262), settings, null, emptySet(), prev).isEmpty())
    }

    @Test
    fun lateJoinAlreadyDepartedFinishesWithoutRecord() {
        // 13:24 에 처음 확인했는데 정시 → 이미 13:18 에 떠났음
        val src = FakeSource().apply { byNo = { LookupResult.Success(train("262", 0)) } }
        val now = dt(sun, 13, 24)
        val active = Monitor.activeOccurrences(now, listOf(sun262), settings, null, emptySet(), emptyMap())
        val out = Monitor.poll(now, active, emptyMap(), settings, src)
        assertTrue(out.statuses.isEmpty())
        assertFalse(Monitor.shouldRecord(out.finished.single()))
    }

    @Test
    fun lateJoinBeyondGraceIgnored() {
        // 예정 13:18 + 2시간 = 15:18 이후로는 확인하지 않음
        assertEquals(1, Monitor.activeOccurrences(dt(sun, 15, 17), listOf(sun262), settings, null, emptySet(), emptyMap()).size)
        assertTrue(Monitor.activeOccurrences(dt(sun, 15, 18), listOf(sun262), settings, null, emptySet(), emptyMap()).isEmpty())
    }

    @Test
    fun lateJoinAlreadyCheckedIsNotRequeried() {
        // 한 번 확인해서 끝난 열차(finishedKeys)는 다시 조회하지 않음
        val key = Occurrence(sun262, sun).key
        assertTrue(Monitor.activeOccurrences(dt(sun, 13, 30), listOf(sun262), settings, null, setOf(key), emptyMap()).isEmpty())
    }

    @Test
    fun skipTodayAndNextStart() {
        val now = dt(wed, 19, 0)
        assertTrue(Monitor.activeOccurrences(now, trains, settings, wed, emptySet(), emptyMap()).isEmpty())
        assertEquals(dt(wed.plusDays(1), 17, 5), Monitor.nextStart(now, trains, settings, wed, emptySet(), emptyMap()))
    }

    @Test
    fun nextStartWrapsWeek() {
        val next = Monitor.nextOccurrence(dt(sun, 14, 0), trains, settings, null)!!
        assertEquals("wed1945", next.train.id)
    }

    @Test
    fun disabledTrainIgnored() {
        val t = trains.map { if (it.id == "sun324") it.copy(enabled = false) else it }
        assertTrue(Monitor.activeOccurrences(dt(sun, 12, 0), t, settings, null, emptySet(), emptyMap()).isEmpty())
    }

    @Test
    fun pollIntervalSpeedsUpNearDeparture() {
        val s = TrainStatus.start(Occurrence(trains[3], wed), dt(wed, 19, 12))
        assertEquals(2L, Monitor.pollInterval(listOf(s), dt(wed, 19, 30), settings).toMinutes())
        assertEquals(1L, Monitor.pollInterval(listOf(s), dt(wed, 19, 42), settings).toMinutes())
    }

    // ---- apply() ----

    private fun start(id: String, date: LocalDate, now: LocalDateTime): TrainStatus =
        TrainStatus.start(Occurrence(trains.first { it.id == id }, date), now)

    private fun found(depDelay: Int, arrDelay: Int = depDelay, cancelled: Boolean = false) =
        Monitor.readingFrom(LookupResult.Success(train("614", depDelay, arrDelay, cancelled)), "천안아산", "수서")

    @Test
    fun firstDelayAlertsAndFillsArrival() {
        val now = dt(wed, 19, 20)
        val (s, a) = Monitor.apply(start("wed2012", wed, now), found(4), now, settings)
        assertTrue(a is AlertEvent.FirstDelay && a.delay == 4)
        assertEquals(4, s.delayMin)
        assertEquals(4, s.arrDelayMin)
        assertEquals(dt(wed, 21, 0), s.scheduledArr)
        assertEquals("천안아산 20:12 → 20:16 출발", Texts.depLine(s))
        assertEquals("수서 21:00 → 21:04 도착 예상", Texts.arrLine(s))
    }

    @Test
    fun smallChangeNoAlertBigChangeAlerts() {
        val now = dt(wed, 19, 20)
        val (s1, _) = Monitor.apply(start("wed2012", wed, now), found(4), now, settings)
        val (s2, a2) = Monitor.apply(s1, found(5), now.plusMinutes(2), settings)
        assertNull(a2) // 1분 차이
        val (_, a3) = Monitor.apply(s2, found(8), now.plusMinutes(4), settings)
        assertTrue(a3 is AlertEvent.Changed && a3.from == 4 && a3.to == 8)
    }

    @Test
    fun separateArrivalDelay() {
        val now = dt(wed, 19, 20)
        // 출발은 7분 지연이지만 도착 때 4분으로 회복
        val (s, _) = Monitor.apply(start("wed2012", wed, now), found(7, arrDelay = 4), now, settings)
        assertEquals(7, s.delayMin)
        assertEquals("수서 21:00 → 21:04 도착 예상", Texts.arrLine(s))
    }

    @Test
    fun errorKeepsLastKnownDelay() {
        val now = dt(sun, 12, 0)
        val (s1, _) = Monitor.apply(start("sun324", sun, now), found(6), now, settings)
        val (s2, a2) = Monitor.apply(s1, Reading(found = false, error = "timeout"), now.plusMinutes(2), settings)
        assertNull(a2)
        assertEquals(6, s2.delayMin)
        assertEquals(Phase.RUNNING, s2.phase)
        assertTrue(s2.message!!.contains("12:00 정보 기준"))
    }

    @Test
    fun errorWithoutDataIsErrorPhase() {
        val now = dt(sun, 12, 0)
        val (s, _) = Monitor.apply(start("sun324", sun, now), Reading(found = false, error = "timeout"), now, settings)
        assertEquals(Phase.ERROR, s.phase)
        assertEquals("조회 실패", Texts.delayShort(s))
    }

    @Test
    fun notFoundWithoutDataIsNotFoundPhase() {
        val now = dt(sun, 12, 0)
        val (s, _) = Monitor.apply(start("sun324", sun, now), Reading(found = false, notFound = true), now, settings)
        assertEquals(Phase.NOT_FOUND, s.phase)
    }

    @Test
    fun cancellationAlertsOnce() {
        val now = dt(LocalDate.of(2026, 10, 29), 17, 30)
        val (s1, a1) = Monitor.apply(start("thu346", LocalDate.of(2026, 10, 29), now), found(0, cancelled = true), now, settings)
        assertTrue(a1 is AlertEvent.Cancelled)
        val (_, a2) = Monitor.apply(s1, found(0, cancelled = true), now.plusMinutes(1), settings)
        assertNull(a2)
        assertEquals("운행 취소", Texts.delayShort(s1))
    }

    @Test
    fun recoveryToOnTimeAlerts() {
        val now = dt(sun, 12, 0)
        val (s1, _) = Monitor.apply(start("sun324", sun, now), found(5), now, settings)
        val (_, a2) = Monitor.apply(s1, found(0), now.plusMinutes(2), settings)
        assertTrue(a2 is AlertEvent.Changed && a2.to == 0)
    }

    @Test
    fun alertsDisabled() {
        val now = dt(sun, 12, 0)
        val (s, a) = Monitor.apply(start("sun324", sun, now), found(9), now, settings.copy(alertsEnabled = false))
        assertNull(a)
        assertEquals(9, s.delayMin)
    }

    // ---- poll() 전체 흐름 ----

    @Test
    fun fullWednesdayScenario() {
        val src = FakeSource()
        var statuses = emptyMap<String, TrainStatus>()
        val finished = mutableListOf<TrainStatus>()
        val alerts = mutableListOf<AlertEvent>()
        val finishedKeys = mutableSetOf<String>()

        fun step(h: Int, m: Int) {
            val now = dt(wed, h, m)
            val active = Monitor.activeOccurrences(now, trains, settings, null, finishedKeys, statuses)
            val out = Monitor.poll(now, active, statuses, settings, src)
            statuses = out.statuses.associateBy { it.key }
            finished += out.finished
            finishedKeys += out.finished.map { it.key }
            alerts += out.alerts
        }

        // 모두 정시
        src.byNo = { LookupResult.Success(train(it.trimStart('0'), 0)) }
        step(19, 12)
        assertEquals(3, statuses.size)
        assertTrue(alerts.isEmpty())
        assertEquals("정시", Texts.delayShort(statuses.values.first()))

        // 20:12 열차(번호 614)만 7분 지연 → 첫 지연 알림 1건
        src.byNo = { no -> LookupResult.Success(train(no.trimStart('0'), if (no == "00614") 7 else 0)) }
        step(19, 20)
        assertEquals(1, alerts.size)
        assertTrue(alerts[0] is AlertEvent.FirstDelay)
        val s614 = statuses["wed2012@$wed"]!!
        assertEquals(7, s614.delayMin)
        assertEquals("천안아산 20:12 → 20:19 출발", Texts.depLine(s614))

        // 19:45 열차는 출발 5분 뒤(19:50) 종료
        step(19, 50)
        assertTrue(finished.any { it.key == "wed1945@$wed" })

        // 614 열차는 7분 지연 → 20:12+7+5 = 20:24 까지 감시
        step(20, 23)
        assertTrue(statuses.containsKey("wed2012@$wed"))
        step(20, 24)
        val done614 = finished.first { it.key == "wed2012@$wed" }
        assertTrue(Monitor.shouldRecord(done614)) // 출발 60분 전부터 본 열차는 기록
        val rec = HistoryRecord.from(done614)
        assertEquals(7, rec.finalDelay)
        assertEquals(52, rec.firstDelayMinBefore) // 19:20 → 20:12 기준 52분 전
    }

    @Test
    fun pollRecordsExpiredWithoutQuery() {
        // 감시 중이던 열차가 다음 조회 대상에서 빠지고 시간이 지나면 기록으로 간다
        val src = FakeSource().apply { byNo = { LookupResult.Success(train(it.trimStart('0'), 3)) } }
        val now1 = dt(sun, 12, 0)
        val occ = Occurrence(trains.first { it.id == "sun324" }, sun)
        val out1 = Monitor.poll(now1, listOf(occ), emptyMap(), settings, src)
        val prev = out1.statuses.associateBy { it.key }
        assertTrue(prev.isNotEmpty())
        // 한참 뒤, active 가 비어도 기존 상태는 종료 처리되어 기록으로
        val out2 = Monitor.poll(dt(sun, 13, 30), emptyList(), prev, settings, src)
        assertTrue(out2.statuses.isEmpty())
        assertTrue(out2.finished.any { it.key == occ.key })
    }
}
