package io.github.jstn0713.ktxdelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CodecAndStatsTest {

    @Test
    fun trainRoundTrip() {
        val t = WatchTrain("a", setOf(3, 7), "천안아산", "수서", LocalTime.of(20, 12), "352", "KTX-산천", LocalTime.of(21, 0))
        assertEquals(listOf(t), Codec.trainsFromJson(Codec.trainsToJson(listOf(t))))
        val noArr = t.copy(arrTime = null, trainNo = "", enabled = false)
        assertEquals(listOf(noArr), Codec.trainsFromJson(Codec.trainsToJson(listOf(noArr))))
    }

    @Test
    fun settingsRoundTripAndDefaults() {
        val s = AppSettings(startBeforeMin = 45, alertsEnabled = false, korailVersion = "250101001")
        assertEquals(s, Codec.settingsFromJson(Codec.settingsToJson(s)))
        assertEquals(AppSettings(), Codec.settingsFromJson(null))
        assertEquals(AppSettings(), Codec.settingsFromJson("broken"))
    }

    @Test
    fun statusRoundTrip() {
        val now = LocalDateTime.of(2026, 10, 28, 19, 20)
        val s = TrainStatus(
            key = "k", trainId = "t", historyKey = "h", label = "KTX-산천 352", depStation = "천안아산",
            arrStation = "수서", scheduledDep = now.plusMinutes(52), scheduledArr = now.plusMinutes(100),
            monitorStartedAt = now.minusMinutes(8), delayMin = 4, phase = Phase.RUNNING, lastCheckedAt = now,
            lastDataAt = now, lastAlertedDelay = 4, firstDelayAt = now, maxDelay = 4,
            timeline = listOf(TimelinePoint(now.minusMinutes(8), 0), TimelinePoint(now, 4)), message = null, delayRaw = "0004",
        )
        assertEquals(listOf(s), Codec.statusesFromJson(Codec.statusesToJson(listOf(s))))
        assertEquals(emptyList<TrainStatus>(), Codec.statusesFromJson(""))
    }

    private fun rec(date: Int, final: Int?, first: Int?, atStart: Boolean = false, cancelled: Boolean = false) = HistoryRecord(
        key = "k$date", historyKey = "천안아산>수서@20:12#352", date = LocalDate.of(2026, 10, date), label = "KTX-산천 352",
        depStation = "천안아산", arrStation = "수서", depTime = LocalTime.of(20, 12), finalDelay = final, maxDelay = final,
        firstDelayMinBefore = first, presentAtStart = atStart, monitorStartMinBefore = 60, cancelled = cancelled,
        timeline = listOf(60 to 0),
    )

    @Test
    fun historyRoundTrip() {
        val list = listOf(rec(7, 3, 20), rec(14, null, null))
        assertEquals(list, Codec.historyListFromJson(Codec.historyListToJson(list)))
    }

    @Test
    fun stats() {
        val st = Stats.compute(
            listOf(rec(7, 3, 20), rec(14, 0, null), rec(21, 9, 40), rec(28, 6, 60, atStart = true), rec(30, null, null, cancelled = true)),
        ).single()
        assertEquals(4, st.days)
        assertEquals(3, st.delayedDays)
        assertEquals(2, st.over5Days)
        assertEquals(4.5, st.avgDelay, 0.001)
        assertEquals(9, st.maxDelay)
        assertEquals(30.0, st.avgFirstMinBefore!!, 0.001)
        assertEquals(1, st.presentAtStartDays)
        assertEquals(1, st.cancelledDays)
    }

    @Test
    fun parseHmVariants() {
        assertEquals(LocalTime.of(20, 12), parseHm("20:12"))
        assertEquals(LocalTime.of(9, 5), parseHm("9:05"))
        assertEquals(LocalTime.of(20, 12), parseHm("2012"))
        assertEquals(LocalTime.of(20, 12), parseHm("20시12분"))
        assertNull(parseHm("25:00"))
        assertNull(parseHm("abc"))
    }
}
