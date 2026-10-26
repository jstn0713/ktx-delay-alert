package io.github.jstn0713.ktxdelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverParserTest {

    // 실제 네이버 train-time 응답(일부 역만, 지연 1~2분 포함)을 그대로 사용
    private val realJson = """
        {"res":{"trainNumber":"00614","runDate":"20261025","stopLaborTrainCfCode":{"id":"0A","name":"KTX-산천"},
        "routeName":"수서아산목포_SR교차","originStopName":"목포","terminationStopName":"수서","railwayCompany":"KORAIL",
        "delayDetailReasonContent":null,
        "stopList":[
        {"stopCode":"0041","stopName":"목포","departureTime":"100000","arrivalTime":"999999","actualArrivalDelayTimeNumber":"00000","runOrder":"000001"},
        {"stopCode":"0036","stopName":"광주송정","departureTime":"103800","arrivalTime":"103600","actualArrivalDelayTimeNumber":"00001","runOrder":"000003"},
        {"stopCode":"0033","stopName":"정읍","departureTime":"105700","arrivalTime":"105500","actualArrivalDelayTimeNumber":"00002","runOrder":"000004"},
        {"stopCode":"0502","stopName":"천안아산","departureTime":"120000","arrivalTime":"115800","actualArrivalDelayTimeNumber":"00000","runOrder":"000006"},
        {"stopCode":"0551","stopName":"수서","departureTime":"999999","arrivalTime":"123500","actualArrivalDelayTimeNumber":"00000","runOrder":"000008"}
        ]}}
    """.trimIndent()

    @Test
    fun parsesRealResponse() {
        val r = NaverParser.parse(realJson) as LookupResult.Success
        val t = r.train
        assertEquals("00614", t.trainNo)
        assertEquals("614", t.displayNo)
        assertEquals("KTX-산천 614", t.label)
        assertEquals("KORAIL", t.company)
        assertFalse(t.cancelled)
        assertEquals(5, t.stops.size)
        assertEquals(listOf("목포", "광주송정", "정읍", "천안아산", "수서"), t.stops.map { it.name })

        val cheonan = t.stopFor("천안아산")!!
        assertEquals("12:00", cheonan.schedDep!!.hm())
        assertEquals("11:58", cheonan.schedArr!!.hm())
        assertEquals(0, cheonan.delayMin)

        val jeongeup = t.stopFor("정읍")!!
        assertEquals(2, jeongeup.delayMin)

        // 끝 역은 출발 없음(999999 -> null)
        assertNull(t.stopFor("수서")!!.schedDep)
        assertEquals("12:35", t.stopFor("수서")!!.schedArr!!.hm())
    }

    @Test
    fun stopForIgnoresYeokSuffixAndSpaces() {
        val t = (NaverParser.parse(realJson) as LookupResult.Success).train
        assertEquals("천안아산", t.stopFor("천안아산역")!!.name)
        assertEquals("천안아산", t.stopFor(" 천안아산 ")!!.name)
        assertNull(t.stopFor("서울"))
    }

    // 2026-10-25 12:30 — 천안아산(12:00 예정)은 이미 지남, 모두 정시였음
    private val at = java.time.LocalDateTime.of(2026, 10, 25, 12, 30)
    private val day = java.time.LocalDate.of(2026, 10, 25)

    @Test
    fun readingForRoute() {
        val result = NaverParser.parse(realJson)
        val reading = Monitor.readingFrom(result, "천안아산", "수서", day, at)
        assertTrue(reading.found)
        assertEquals(0, reading.departDelayMin)
        assertEquals(0, reading.arrDelayMin)
        assertEquals("12:00", reading.schedDep!!.hm())
        assertEquals("12:35", reading.schedArr!!.hm())
    }

    @Test
    fun readingNotFoundWhenStationMissing() {
        val result = NaverParser.parse(realJson)
        val reading = Monitor.readingFrom(result, "서울", "수서", day, at)
        assertFalse(reading.found)
        assertTrue(reading.notFound)
    }

    @Test
    fun delayParsing() {
        assertEquals(0, NaverParser.parseDelay(null))
        assertEquals(0, NaverParser.parseDelay(""))
        assertEquals(0, NaverParser.parseDelay("00000"))
        assertEquals(7, NaverParser.parseDelay("00007"))
        assertEquals(63, NaverParser.parseDelay("00063"))
    }

    @Test
    fun padTrainNumber() {
        assertEquals("00352", padTrainNo("352"))
        assertEquals("00614", padTrainNo("00614"))
        assertEquals("00324", padTrainNo("KTX 324"))
    }

    @Test
    fun emptyResIsNotFound() {
        assertTrue(NaverParser.parse("""{"res":null}""") is LookupResult.NotFound)
        assertTrue(NaverParser.parse("""{"res":{"stopList":[]}}""") is LookupResult.NotFound)
    }

    @Test
    fun htmlResponseIsFailure() {
        val r = NaverParser.parse("<!doctype html><html><body>blocked</body></html>")
        assertTrue(r is LookupResult.Failure)
    }

    @Test
    fun detectsCancellation() {
        val json = """{"res":{"trainNumber":"00352","railwayCompany":"KORAIL","stopLaborTrainCfCode":{"name":"KTX"},
            "delayDetailReasonContent":"운행취소",
            "stopList":[{"stopCode":"0502","stopName":"천안아산","departureTime":"201200","arrivalTime":"201000","actualArrivalDelayTimeNumber":"00000","runOrder":"000001"}]}}"""
        val t = (NaverParser.parse(json) as LookupResult.Success).train
        assertTrue(t.cancelled)
    }
}
