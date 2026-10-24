package io.github.jstn0713.ktxdelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KorailParserTest {

    private fun train(no: String, dep: String, arr: String, delay: String?, extra: String = "") = """
        {"h_trn_no":"$no","h_trn_clsf_nm":"KTX-산천","h_trn_clsf_cd":"100",
         "h_dpt_rs_stn_nm":"천안아산","h_arv_rs_stn_nm":"수서",
         "h_dpt_dt":"20261028","h_dpt_tm":"$dep","h_arv_dt":"20261028","h_arv_tm":"$arr",
         ${if (delay != null) "\"h_expct_dlay_hr\":\"$delay\"," else ""}
         "h_rsv_psb_flg":"Y","h_rsv_psb_nm":"예약가능"$extra}
    """.trimIndent()

    @Test
    fun parsesTrainList() {
        val json = """{"strResult":"SUCC","h_msg_cd":"IRG000000","h_msg_txt":"정상",
            "trn_infos":{"trn_info":[${train("00352", "201200", "210000", "0007")},${train("00354", "203000", "211800", "0000")}]}}"""
        val r = KorailParser.parseSearch(json) as SearchResult.Success
        assertEquals(2, r.trains.size)
        val t = r.trains[0]
        assertEquals("352", t.trainNo)
        assertEquals("KTX-산천 352", t.label)
        assertEquals(7, t.delayMin)
        assertEquals("0007", t.delayRaw)
        assertEquals("20:12", t.depLocalTime!!.hm())
        assertEquals("2026-10-28T21:00", t.arrDateTime().toString())
        assertFalse(t.cancelled)
        assertEquals(0, r.trains[1].delayMin)
        assertEquals(mapOf("h_expct_dlay_hr" to "0007"), t.delayFields)
    }

    @Test
    fun parsesSingleObjectAndMissingDelay() {
        val json = """{"strResult":"SUCC","trn_infos":{"trn_info":${train("324", "124900", "131800", null)}}}"""
        val r = KorailParser.parseSearch(json) as SearchResult.Success
        assertEquals(1, r.trains.size)
        assertEquals(0, r.trains[0].delayMin)
        assertNull(r.trains[0].delayRaw)
    }

    @Test
    fun noResultCodeIsEmptySuccess() {
        val r = KorailParser.parseSearch("""{"strResult":"FAIL","h_msg_cd":"P100","h_msg_txt":"조회 결과가 없습니다."}""")
        assertTrue(r is SearchResult.Success && r.trains.isEmpty())
    }

    @Test
    fun failureKeepsMessage() {
        val r = KorailParser.parseSearch("""{"strResult":"FAIL","h_msg_cd":"X999","h_msg_txt":"앱을 업데이트 해주세요"}""")
        assertTrue(r is SearchResult.Failure)
        assertEquals("앱을 업데이트 해주세요", (r as SearchResult.Failure).message)
        assertEquals("X999", r.code)
    }

    @Test
    fun invalidJsonIsFailure() {
        assertTrue(KorailParser.parseSearch("<html>error</html>") is SearchResult.Failure)
    }

    @Test
    fun detectsCancellation() {
        val json = """{"strResult":"SUCC","trn_infos":{"trn_info":[${train("346", "180500", "183400", "0000", ",\"h_trn_stt\":\"운행취소\"")}]}}"""
        val r = KorailParser.parseSearch(json) as SearchResult.Success
        assertTrue(r.trains[0].cancelled)
    }

    @Test
    fun delayFormats() {
        val p = KorailParser::parseDelayMinutes
        assertNull(p(null))
        assertEquals(0, p(""))
        assertEquals(0, p("0"))
        assertEquals(7, p("7"))
        assertEquals(7, p("07"))
        assertEquals(7, p("007"))
        assertEquals(7, p("7분"))
        assertEquals(7, p("0007"))
        assertEquals(67, p("0107"))
        assertEquals(7, p("000700"))
        assertEquals(7, p("0700"))
        assertEquals(7, p("00:07"))
        assertEquals(0, p("0000"))
    }
}
