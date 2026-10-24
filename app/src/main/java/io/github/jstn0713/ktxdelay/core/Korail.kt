package io.github.jstn0713.ktxdelay.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 코레일 열차 조회 결과의 열차 1대. */
data class KorailTrain(
    /** 앞의 0을 뺀 열차 번호. 예: "352" */
    val trainNo: String,
    /** 예: "KTX-산천" */
    val trainName: String,
    val depStation: String,
    val arrStation: String,
    /** yyyyMMdd */
    val depDate: String,
    /** HHmmss */
    val depTime: String,
    val arrDate: String,
    val arrTime: String,
    /** 코레일이 보내준 예상 지연 값 그대로 (h_expct_dlay_hr) */
    val delayRaw: String?,
    /** 예상 지연(분). 값이 없으면 0으로 본다. */
    val delayMin: Int,
    val cancelled: Boolean,
    /** 디버그용: 응답의 모든 항목 */
    val raw: Map<String, String>,
) {
    val label: String get() = listOf(trainName.ifBlank { "KTX" }, trainNo).filter { it.isNotBlank() }.joinToString(" ")
    val depLocalTime: LocalTime? get() = parseHhmmss(depTime)
    val arrLocalTime: LocalTime? get() = parseHhmmss(arrTime)

    fun arrDateTime(): LocalDateTime? {
        val t = arrLocalTime ?: return null
        val d = parseYmd(arrDate) ?: parseYmd(depDate) ?: return null
        return LocalDateTime.of(d, t)
    }

    /** 지연과 관련 있어 보이는 원본 항목들 (확인용) */
    val delayFields: Map<String, String>
        get() = raw.filterKeys { k -> DELAY_KEY_HINTS.any { k.contains(it) } }
}

private val DELAY_KEY_HINTS = listOf("dlay", "dly", "delay")

sealed class SearchResult {
    data class Success(val trains: List<KorailTrain>, val rawJson: String) : SearchResult()
    data class Failure(val message: String, val code: String? = null, val rawJson: String? = null) : SearchResult()
}

/** 조회 방법을 바꿔 끼울 수 있게(테스트용) 인터페이스로 둔다. */
fun interface Searcher {
    fun search(dep: String, arr: String, date: LocalDate, from: LocalTime, includeSuseo: Boolean): SearchResult
}

object KorailParser {
    /** 결과 없음으로 취급하는 코레일 메시지 코드 */
    private val NO_RESULT_CODES = setOf("P100", "WRG000000", "WRD000061", "WRT300005")
    private val CANCEL_WORDS = listOf("운행취소", "운행 취소", "운휴", "운행중지", "운행 중지", "운행중단")

    fun parseSearch(json: String): SearchResult {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return SearchResult.Failure("코레일 응답을 읽을 수 없어요 (형식 오류)", rawJson = json.take(2000))
        }
        val result = obj.optString("strResult")
        val code = obj.optString("h_msg_cd").ifBlank { null }
        val msg = obj.optString("h_msg_txt").ifBlank { null }
        if (result == "FAIL") {
            if (code in NO_RESULT_CODES) return SearchResult.Success(emptyList(), json)
            return SearchResult.Failure(msg ?: "코레일 조회 실패", code, json)
        }
        val infos = obj.optJSONObject("trn_infos")
        val list: JSONArray? = when (val t = infos?.opt("trn_info")) {
            is JSONArray -> t
            is JSONObject -> JSONArray().put(t)
            else -> null
        }
        val trains = mutableListOf<KorailTrain>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                trains += parseTrain(o)
            }
        }
        return SearchResult.Success(trains, json)
    }

    fun parseTrain(o: JSONObject): KorailTrain {
        val raw = linkedMapOf<String, String>()
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) raw[k] = v.toString()
        }
        fun s(k: String) = raw[k]?.trim().orEmpty()
        val delayRaw = raw["h_expct_dlay_hr"]
        return KorailTrain(
            trainNo = s("h_trn_no").trimStart('0').ifBlank { s("h_trn_no") },
            trainName = s("h_trn_clsf_nm"),
            depStation = s("h_dpt_rs_stn_nm"),
            arrStation = s("h_arv_rs_stn_nm"),
            depDate = s("h_dpt_dt").ifBlank { s("h_run_dt") },
            depTime = s("h_dpt_tm"),
            arrDate = s("h_arv_dt"),
            arrTime = s("h_arv_tm"),
            delayRaw = delayRaw,
            delayMin = parseDelayMinutes(delayRaw) ?: 0,
            cancelled = raw.values.any { v -> CANCEL_WORDS.any { v.contains(it) } },
            raw = raw,
        )
    }

    /**
     * 코레일의 예상 지연 값(h_expct_dlay_hr)을 분으로 바꾼다.
     * 정확한 형식이 공개되어 있지 않아 여러 형식을 모두 받아들인다.
     *  - "7", "07", "007", "7분" → 7분
     *  - "0007"(시분) → 7분, "0107" → 67분
     *  - "000700"(시분초) → 7분
     *  - "0700"처럼 시분으로 읽으면 2시간 이상이 되는 값은 분·초(07분 00초)로 본다.
     */
    fun parseDelayMinutes(raw: String?): Int? {
        if (raw == null) return null
        val text = raw.trim()
        if (text.isEmpty()) return 0
        Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?$""").find(text)?.let {
            return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
        }
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return 0
        return when {
            digits.length <= 3 -> digits.toInt()
            digits.length == 4 || digits.length == 6 -> {
                val a = digits.substring(0, 2).toInt()
                val b = digits.substring(2, 4).toInt()
                val asHourMin = a * 60 + b
                if (asHourMin >= 120 && digits.length == 4) a else asHourMin
            }
            else -> digits.takeLast(3).toInt()
        }
    }
}

private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

fun parseYmd(s: String): LocalDate? = try {
    if (s.length == 8) LocalDate.parse(s, YMD) else null
} catch (e: Exception) {
    null
}

fun parseHhmmss(s: String): LocalTime? {
    val d = s.filter { it.isDigit() }
    if (d.length < 4) return null
    val h = d.substring(0, 2).toInt()
    val m = d.substring(2, 4).toInt()
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime.of(h, m)
}

/**
 * 코레일톡 앱이 쓰는 열차 조회 기능을 호출한다. (로그인 불필요)
 * 공식 공개 API가 아니므로 코레일이 바꾸면 동작하지 않을 수 있다.
 */
class KorailClient(
    private val version: String = DEFAULT_KORAIL_VERSION,
    private val userAgent: String = System.getProperty("http.agent")
        ?: "Dalvik/2.1.0 (Linux; U; Android 14; SM-S912N Build/UP1A.231005.007)",
) : Searcher {

    override fun search(dep: String, arr: String, date: LocalDate, from: LocalTime, includeSuseo: Boolean): SearchResult {
        val params = linkedMapOf(
            "Device" to "AD",
            "Version" to version,
            "Sid" to "",
            "txtMenuId" to "11",
            "radJobId" to "1",
            "selGoTrain" to "109",
            "txtTrnGpCd" to "109",
            "txtGoStart" to dep.trim(),
            "txtGoEnd" to arr.trim(),
            "txtGoAbrdDt" to date.format(YMD),
            "txtGoHour" to from.format(DateTimeFormatter.ofPattern("HHmmss")),
            "txtPsgFlg_1" to "1",
            "txtPsgFlg_2" to "0",
            "txtPsgFlg_3" to "0",
            "txtPsgFlg_4" to "0",
            "txtPsgFlg_5" to "0",
            "txtSeatAttCd_2" to "000",
            "txtSeatAttCd_3" to "000",
            "txtSeatAttCd_4" to "015",
            "ebizCrossCheck" to "N",
            "srtCheckYn" to if (includeSuseo) "Y" else "N",
            "rtYn" to "N",
            "adjStnScdlOfrFlg" to "N",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val body = try {
            httpGet("$SCHEDULE_URL?$query")
        } catch (e: IOException) {
            return SearchResult.Failure("인터넷 연결 또는 코레일 서버 문제: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            return SearchResult.Failure("조회 중 오류: ${e.message ?: e.javaClass.simpleName}")
        }
        return try {
            KorailParser.parseSearch(body)
        } catch (e: Exception) {
            SearchResult.Failure("코레일 응답 처리 오류: ${e.message ?: e.javaClass.simpleName}", rawJson = body.take(2000))
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val SCHEDULE_URL = "https://smart.letskorail.com:443/classes/com.korail.mobile.seatMovie.ScheduleView"
    }
}
