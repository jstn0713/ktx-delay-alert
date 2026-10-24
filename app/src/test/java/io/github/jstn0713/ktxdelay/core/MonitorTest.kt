package io.github.jstn0713.ktxdelay.core

import org.junit.Assert.assertEquals
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

    private fun watch(id: String, day: Int, time: String, no: String = "") = WatchTrain(
        id = id, days = setOf(day), depStation = "천안아산", arrStation = "수서",
        depTime = LocalTime.parse(time), trainNo = no,
    )

    private val trains = listOf(
        watch("sun324", 7, "12:49", "324"),
        watch("wed1945", 3, "19:45"),
        watch("wed1954", 3, "19:54"),
        watch("wed352", 3, "20:12", "352"),
        watch("thu346", 4, "18:05", "346"),
    )

    private fun kt(no: String, dep: String, delay: Int, arr: String = "2100", cancelled: Boolean = false) = KorailTrain(
        trainNo = no, trainName = "KTX-산천", depStation = "천안아산", arrStation = "수서",
        depDate = "20261028", depTime = dep + "00", arrDate = "20261028", arrTime = arr + "00",
        delayRaw = "%04d".format(delay), delayMin = delay, cancelled = cancelled, raw = emptyMap(),
    )

    /** 조회 시각에 따라 다른 결과를 돌려주는 가짜 코레일 */
    private class FakeKorail(var trains: List<KorailTrain>, val pageSize: Int = 10) : Searcher {
        val calls = mutableListOf<Pair<LocalTime, Boolean>>()
        override fun search(dep: String, arr: String, date: LocalDate, from: LocalTime, includeSuseo: Boolean): SearchResult {
            calls += from to includeSuseo
            val list = trains.filter { it.depLocalTime!! >= from }.sortedBy { it.depLocalTime }.take(pageSize)
            return SearchResult.Success(list, "{}")
        }
    }

    @Test
    fun nothingActiveBeforeWindow() {
        val now = dt(wed, 18, 44)
        val active = Monitor.activeOccurrences(now, trains, settings, null, emptySet(), emptyMap())
        assertTrue(active.isEmpty())
        assertEquals(dt(wed, 18, 45), Monitor.nextStart(now, trains, settings, null, emptySet(), emptyMap()))
    }

    @Test
    fun wednesdayWindowsOverlap() {
        val at = { h: Int, m: Int -> Monitor.activeOccurrences(dt(wed, h, m), trains, settings, null, emptySet(), emptyMap()).map { it.train.id } }
        assertEquals(listOf("wed1945"), at(18, 45))
        assertEquals(listOf("wed1945", "wed1954"), at(18, 54))
        assertEquals(listOf("wed1945", "wed1954", "wed352"), at(19, 12))
        // 상태가 없으면 출발 5분 뒤까지만 감시
        assertEquals(listOf("wed1954", "wed352"), at(19, 50))
    }

    @Test
    fun skipTodayAndNextStart() {
        val now = dt(wed, 19, 0)
        assertTrue(Monitor.activeOccurrences(now, trains, settings, wed, emptySet(), emptyMap()).isEmpty())
        // 다음은 목요일 18:05 열차 → 17:05 시작
        assertEquals(
            dt(wed.plusDays(1), 17, 5),
            Monitor.nextStart(now, trains, settings, wed, emptySet(), emptyMap()),
        )
    }

    @Test
    fun nextStartWrapsWeek() {
        val now = dt(sun, 14, 0) // 일요일 열차 지난 뒤
        val next = Monitor.nextOccurrence(now, trains, settings, null)!!
        assertEquals("wed1945", next.train.id)
        assertEquals(LocalDate.of(2026, 10, 28), next.date)
    }

    @Test
    fun disabledTrainIgnored() {
        val t = trains.map { if (it.id == "sun324") it.copy(enabled = false) else it }
        val active = Monitor.activeOccurrences(dt(sun, 12, 0), t, settings, null, emptySet(), emptyMap())
        assertTrue(active.isEmpty())
    }

    @Test
    fun pollIntervalSpeedsUpNearDeparture() {
        val occ = Occurrence(trains[3], wed)
        val s = TrainStatus.start(occ, dt(wed, 19, 12))
        assertEquals(2L, Monitor.pollInterval(listOf(s), dt(wed, 19, 30), settings).toMinutes())
        assertEquals(1L, Monitor.pollInterval(listOf(s), dt(wed, 19, 42), settings).toMinutes())
    }

    @Test
    fun matchesByNumberThenTime() {
        val results = listOf(kt("350", "1945", 0), kt("1234", "1954", 0), kt("352", "2012", 3))
        assertEquals("352", Monitor.matchTrain(Occurrence(trains[3], wed), results)!!.trainNo)
        assertEquals("350", Monitor.matchTrain(Occurrence(trains[1], wed), results)!!.trainNo)
        // 번호가 틀렸어도 시각이 맞으면 찾는다
        val wrongNo = trains[3].copy(trainNo = "999")
        assertEquals("352", Monitor.matchTrain(Occurrence(wrongNo, wed), results)!!.trainNo)
        // 다른 날짜 결과는 무시
        val other = results.map { it.copy(depDate = "20261029") }
        assertNull(Monitor.matchTrain(Occurrence(trains[3], wed), other))
    }

    @Test
    fun fullWednesdayScenario() {
        val korail = FakeKorail(listOf(kt("350", "1945", 0), kt("1234", "1954", 0), kt("352", "2012", 0)))
        var statuses = emptyMap<String, TrainStatus>()
        val finished = mutableListOf<TrainStatus>()
        val alerts = mutableListOf<AlertEvent>()
        val finishedKeys = mutableSetOf<String>()

        fun step(h: Int, m: Int) {
            val now = dt(wed, h, m)
            val active = Monitor.activeOccurrences(now, trains, settings, null, finishedKeys, statuses)
            val out = Monitor.poll(now, active, statuses, settings, korail)
            statuses = out.statuses.associateBy { it.key }
            finished += out.finished
            finishedKeys += out.finished.map { it.key }
            alerts += out.alerts
        }

        step(19, 12)
        assertEquals(3, statuses.size)
        assertTrue(alerts.isEmpty())
        assertEquals("정시", Texts.delayShort(statuses.values.first()))

        // 20:12 열차에 4분 지연 → 첫 지연 알림
        korail.trains = listOf(kt("350", "1945", 0), kt("1234", "1954", 0), kt("352", "2012", 4))
        step(19, 20)
        assertEquals(1, alerts.size)
        assertTrue(alerts[0] is AlertEvent.FirstDelay)
        assertEquals(4, (alerts[0] as AlertEvent.FirstDelay).delay)

        // 5분: 차이 1분 → 알림 없음
        korail.trains = listOf(kt("350", "1945", 0), kt("1234", "1954", 0), kt("352", "2012", 5))
        step(19, 22)
        assertEquals(1, alerts.size)

        // 8분: 처음 알린 4분에서 4분 차이 → 변화 알림
        korail.trains = listOf(kt("350", "1945", 0), kt("1234", "1954", 0), kt("352", "2012", 8))
        step(19, 24)
        assertEquals(2, alerts.size)
        val changed = alerts[1] as AlertEvent.Changed
        assertEquals(4, changed.from)
        assertEquals(8, changed.to)
        val s352 = statuses["wed352@$wed"]!!
        assertEquals("천안아산 20:12 → 20:20 출발", Texts.depLine(s352))
        assertEquals("수서 21:00 → 21:08 도착 예상", Texts.arrLine(s352))

        // 19:50 → 19:45 열차(정시) 출발 5분 뒤라 종료
        step(19, 50)
        assertEquals(listOf("wed1945@$wed"), finished.map { it.key })

        // 352 열차는 8분 지연이므로 20:12+8+5 = 20:25 까지 감시
        korail.trains = listOf(kt("352", "2012", 8))
        step(20, 24)
        assertTrue(statuses.containsKey("wed352@$wed"))
        step(20, 25)
        assertTrue(finished.any { it.key == "wed352@$wed" })

        val rec = HistoryRecord.from(finished.first { it.key == "wed352@$wed" })
        assertEquals(8, rec.finalDelay)
        assertEquals(8, rec.maxDelay)
        assertEquals(52, rec.firstDelayMinBefore) // 19:20 에 처음 → 20:12 기준 52분 전
        assertEquals(false, rec.presentAtStart)
        assertEquals(listOf(60 to 0, 52 to 4, 50 to 5, 48 to 8), rec.timeline)
    }

    @Test
    fun pagesWhenTrainBeyondFirstPage() {
        // 한 페이지에 2대씩만 오는 경우 20:12 열차를 찾으려 다시 조회
        val korail = FakeKorail(
            listOf(kt("350", "1945", 0), kt("1234", "1954", 0), kt("1236", "2003", 0), kt("352", "2012", 2)),
            pageSize = 2,
        )
        val occs = Monitor.occurrences(trains, wed, 1)
        val (found, err) = Monitor.searchGroup("천안아산", "수서", wed, occs, korail)
        assertNull(err)
        assertEquals(3, found.size)
        assertEquals(2, found["wed352@$wed"]!!.delayMin)
        assertTrue(korail.calls.none { it.second }) // 수서 함께 보기 조회는 필요 없었음
    }

    @Test
    fun retriesWithSuseoFlagWhenNotFound() {
        val searcher = Searcher { _, _, _, _, includeSuseo ->
            if (includeSuseo) SearchResult.Success(listOf(kt("324", "1249", 6)), "{}")
            else SearchResult.Success(emptyList(), "{}")
        }
        val occ = Occurrence(trains[0], sun)
        val (found, err) = Monitor.searchGroup("천안아산", "수서", sun, listOf(occ), searcher)
        assertNull(err)
        // depDate 가 20261028 이라 일요일과 안 맞음 → 못 찾음. 날짜를 맞춰 다시
        assertTrue(found.isEmpty())
        val searcher2 = Searcher { _, _, _, _, includeSuseo ->
            if (includeSuseo) SearchResult.Success(listOf(kt("324", "1249", 6).copy(depDate = "20261025")), "{}")
            else SearchResult.Success(emptyList(), "{}")
        }
        val (found2, _) = Monitor.searchGroup("천안아산", "수서", sun, listOf(occ), searcher2)
        assertEquals(6, found2[occ.key]!!.delayMin)
    }

    @Test
    fun errorKeepsLastKnownDelay() {
        val occ = Occurrence(trains[0], sun)
        val now = dt(sun, 12, 0)
        val (s1, a1) = Monitor.apply(TrainStatus.start(occ, now), kt("324", "1249", 6), null, now, settings)
        assertTrue(a1 is AlertEvent.FirstDelay)
        val (s2, a2) = Monitor.apply(s1, null, "timeout", now.plusMinutes(2), settings)
        assertNull(a2)
        assertEquals(6, s2.delayMin)
        assertEquals(Phase.RUNNING, s2.phase)
        assertTrue(s2.message!!.contains("12:00 정보 기준"))
    }

    @Test
    fun errorWithoutDataIsErrorPhase() {
        val occ = Occurrence(trains[0], sun)
        val now = dt(sun, 12, 0)
        val (s, _) = Monitor.apply(TrainStatus.start(occ, now), null, "timeout", now, settings)
        assertEquals(Phase.ERROR, s.phase)
        assertEquals("조회 실패", Texts.delayShort(s))
    }

    @Test
    fun cancellationAlertsOnce() {
        val occ = Occurrence(trains[4], LocalDate.of(2026, 10, 29))
        val now = LocalDateTime.of(2026, 10, 29, 17, 30)
        val t = kt("346", "1805", 0, cancelled = true)
        val (s1, a1) = Monitor.apply(TrainStatus.start(occ, now), t, null, now, settings)
        assertTrue(a1 is AlertEvent.Cancelled)
        val (_, a2) = Monitor.apply(s1, t, null, now.plusMinutes(1), settings)
        assertNull(a2)
        assertEquals("운행 취소", Texts.delayShort(s1))
    }

    @Test
    fun recoveryToOnTimeAlerts() {
        val occ = Occurrence(trains[0], sun)
        val now = dt(sun, 12, 0)
        val (s1, _) = Monitor.apply(TrainStatus.start(occ, now), kt("324", "1249", 5), null, now, settings)
        val (_, a2) = Monitor.apply(s1, kt("324", "1249", 0), null, now.plusMinutes(2), settings)
        assertTrue(a2 is AlertEvent.Changed && a2.to == 0)
    }

    @Test
    fun alertsDisabled() {
        val occ = Occurrence(trains[0], sun)
        val now = dt(sun, 12, 0)
        val (s, a) = Monitor.apply(TrainStatus.start(occ, now), kt("324", "1249", 9), null, now, settings.copy(alertsEnabled = false))
        assertNull(a)
        assertEquals(9, s.delayMin)
    }
}
