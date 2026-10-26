package io.github.jstn0713.ktxdelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverRouteParserTest {

    // 실제 transit 응답을 줄인 형태 (KTX 206, KTX 028, 그리고 건너뛸 지하철 1개)
    private val json = """
    {"paths":[
     {"type":"INTERCITY","transportTypes":["TRAIN"],"legs":[{"steps":[{"type":"TRAIN",
        "departureTime":"2026-10-26T12:07:00+09:00","arrivalTime":"2026-10-26T12:25:00+09:00",
        "routes":[{"type":{"name":"KTX-산천"},"tripInfo":{"className":"KTX","vehicleNo":"00206","displayVehicleNo":"206"}}],
        "stops":[{"name":"광명","departureTime":"2026-10-26T12:07:00+09:00","arrivalTime":"2026-10-26T12:04:00+09:00"},
                 {"name":"서울","arrivalTime":"2026-10-26T12:25:00+09:00","departureTime":"2026-10-26T12:25:00+09:00"}]}]}]},
     {"type":"INTERCITY","transportTypes":["TRAIN"],"legs":[{"steps":[{"type":"TRAIN",
        "departureTime":"2026-10-26T12:15:00+09:00","arrivalTime":"2026-10-26T12:33:00+09:00",
        "routes":[{"type":{"name":"KTX"},"tripInfo":{"className":"KTX","vehicleNo":"00028","displayVehicleNo":"028"}},
                  {"type":{"name":"KTX-산천"},"tripInfo":{"className":"KTX","vehicleNo":"00028","displayVehicleNo":"028"}}],
        "stops":[{"name":"광명","departureTime":"2026-10-26T12:15:00+09:00"},
                 {"name":"용산","arrivalTime":"2026-10-26T12:30:00+09:00"},
                 {"name":"서울","arrivalTime":"2026-10-26T12:33:00+09:00"}]}]}]},
     {"type":"SUBWAY","transportTypes":["SUBWAY"],"legs":[{"steps":[{"type":"SUBWAY",
        "routes":[{"tripInfo":{"className":"일반"}}]}]}]}
    ]}
    """.trimIndent()

    @Test
    fun parsesKtxListSkippingSubway() {
        val r = NaverRouteParser.parse(json) as RouteResult.Success
        assertEquals(2, r.trains.size)
        val t0 = r.trains[0] // 더 이른 출발
        assertEquals("206", t0.displayNo)
        assertEquals("00206", t0.trainNo)
        assertEquals("KTX-산천", t0.trainName)
        assertEquals("KORAIL", t0.company)
        assertEquals("12:07", t0.depTime.hm())
        assertEquals("12:25", t0.arrTime.hm())
        assertEquals("광명", t0.depStation)
        assertEquals("서울", t0.arrStation)

        val t1 = r.trains[1]
        assertEquals("28", t1.displayNo) // 앞의 0은 떼어 표시(앱 전체 통일)
        assertEquals("12:15", t1.depTime.hm())
        assertEquals("12:33", t1.arrTime.hm()) // 마지막 정차역(서울) 도착
        assertEquals("서울", t1.arrStation)
    }

    @Test
    fun emptyPathsFails() {
        assertTrue(NaverRouteParser.parse("""{"message":"경로 없음"}""") is RouteResult.Failure)
    }

    @Test
    fun htmlFails() {
        assertTrue(NaverRouteParser.parse("<!doctype html><html></html>") is RouteResult.Failure)
    }

    @Test
    fun stationLookup() {
        assertEquals(37.4855438, Stations.find("수서")!!.lat, 1e-6)
        assertEquals(37.4855438, Stations.find("수서역")!!.lat, 1e-6)
        assertEquals(null, Stations.find("없는역"))
    }
}
