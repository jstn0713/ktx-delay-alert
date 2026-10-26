package io.github.jstn0713.ktxdelay.core

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 네이버 지도가 쓰는 기차 조회 기능(pt.map.naver.com)에서 열차 지연 정보를 가져온다.
 * 로그인·API 키 없이, 브라우저처럼 헤더만 붙여 호출한다. 공식 공개 API가 아니므로
 * 네이버가 바꾸면 동작하지 않을 수 있다. 개인용으로만 사용.
 */

private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

/** 역 이름 비교용: 공백 제거 + 끝의 '역' 제거. "천안아산역" == "천안아산" */
fun normStation(name: String): String =
    name.trim().replace(" ", "").removeSuffix("역")

/** 네이버 train-time 응답의 정차역 1개 */
data class NaverStop(
    val code: String,
    val name: String,
    val schedDep: LocalTime?,
    val schedArr: LocalTime?,
    /** 그 역에서의 지연(분). actualArrivalDelayTimeNumber */
    val delayMin: Int,
    val runOrder: Int,
)

/** 열차 1대의 운행 정보 */
data class NaverTrain(
    /** 5자리 0패딩. 예: "00614" */
    val trainNo: String,
    /** 앞의 0을 뗀 번호. 예: "614" */
    val displayNo: String,
    /** 예: "KTX-산천" */
    val trainName: String,
    /** "KORAIL" 또는 "SRT" */
    val company: String,
    val routeName: String,
    val originName: String,
    val termName: String,
    val stops: List<NaverStop>,
    val cancelled: Boolean,
    val raw: String,
) {
    val label: String get() = "${trainName.ifBlank { "열차" }} $displayNo"

    fun stopFor(station: String): NaverStop? {
        val q = normStation(station)
        return stops.firstOrNull { normStation(it.name) == q }
    }
}

sealed class LookupResult {
    data class Success(val train: NaverTrain) : LookupResult()
    /** 그 날짜에 그 번호의 열차가 조회되지 않음 (안 다니는 날/번호 오류/운휴) */
    data class NotFound(val rawJson: String? = null) : LookupResult()
    data class Failure(val message: String, val detail: String? = null) : LookupResult()
}

/** 조회 방법을 바꿔 끼울 수 있게(테스트용) 인터페이스로 둔다. */
fun interface TrainSource {
    fun lookup(trainNo: String, company: String, date: LocalDate): LookupResult
}

/** 사용자가 입력한 번호를 네이버 형식(5자리)으로 */
fun padTrainNo(input: String): String {
    val digits = input.filter { it.isDigit() }.trimStart('0')
    return digits.padStart(5, '0')
}

private fun parseHms(s: String?): LocalTime? {
    if (s == null) return null
    val d = s.filter { it.isDigit() }
    if (d.length < 6 || d == "999999") return null
    val h = d.substring(0, 2).toIntOrNull() ?: return null
    val m = d.substring(2, 4).toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime.of(h, m)
}

object NaverParser {
    /** 지연 문자열("00007")을 분으로. 비어있으면 0. */
    fun parseDelay(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return raw.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    private val CANCEL_WORDS = listOf("운행취소", "운행 취소", "운휴", "운행중지", "운행 중지")

    fun parse(json: String): LookupResult {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            // 페이지(HTML)나 오류 응답이 온 경우
            val snippet = json.take(300)
            return if (snippet.contains("<html", true) || snippet.contains("<!doctype", true)) {
                LookupResult.Failure("네이버가 데이터 대신 페이지를 보냈어요 (차단/변경 가능성)", snippet)
            } else {
                LookupResult.Failure("응답을 읽을 수 없어요 (형식 오류)", snippet)
            }
        }
        val res = root.opt("res")
        if (res == null || res == JSONObject.NULL) {
            // {"res":null} 또는 에러 메시지
            val msg = root.optString("message").ifBlank { root.optString("error").ifBlank { null } }
            return if (msg != null) LookupResult.Failure(msg, json.take(300)) else LookupResult.NotFound(json)
        }
        if (res !is JSONObject) return LookupResult.NotFound(json)

        val stopsArr = res.optJSONArray("stopList")
        if (stopsArr == null || stopsArr.length() == 0) return LookupResult.NotFound(json)

        val stops = ArrayList<NaverStop>(stopsArr.length())
        for (i in 0 until stopsArr.length()) {
            val o = stopsArr.optJSONObject(i) ?: continue
            stops += NaverStop(
                code = o.optString("stopCode"),
                name = o.optString("stopName"),
                schedDep = parseHms(o.optString("departureTime")),
                schedArr = parseHms(o.optString("arrivalTime")),
                delayMin = parseDelay(o.optString("actualArrivalDelayTimeNumber")),
                runOrder = o.optString("runOrder").filter { it.isDigit() }.toIntOrNull() ?: (i + 1),
            )
        }
        stops.sortBy { it.runOrder }

        val trainNo = res.optString("trainNumber")
        val name = res.optJSONObject("stopLaborTrainCfCode")?.optString("name").orEmpty()
        val reasonBlob = res.optString("delayDetailReasonContent") + " " + name
        return LookupResult.Success(
            NaverTrain(
                trainNo = trainNo,
                displayNo = trainNo.trimStart('0').ifBlank { trainNo },
                trainName = name,
                company = res.optString("railwayCompany").ifBlank { "KORAIL" },
                routeName = res.optString("routeName"),
                originName = res.optString("originStopName"),
                termName = res.optString("terminationStopName"),
                stops = stops,
                cancelled = CANCEL_WORDS.any { reasonBlob.contains(it) },
                raw = json,
            ),
        )
    }
}

// ---- 노선 열차 목록 (네이버 길찾기 transit) ----

/** 노선 조회로 찾은 열차 1대 */
data class RouteTrain(
    val trainNo: String,
    val displayNo: String,
    val trainName: String,
    val company: String,
    val depTime: LocalTime,
    val arrTime: LocalTime,
    val depStation: String,
    val arrStation: String,
)

sealed class RouteResult {
    data class Success(val trains: List<RouteTrain>) : RouteResult()
    data class Failure(val message: String, val detail: String? = null) : RouteResult()
}

private fun parseOffsetTime(s: String?): LocalTime? {
    if (s.isNullOrBlank()) return null
    return try {
        java.time.OffsetDateTime.parse(s).toLocalTime()
    } catch (e: Exception) {
        null
    }
}

object NaverRouteParser {
    fun parse(json: String): RouteResult {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            val snippet = json.take(300)
            return if (snippet.contains("<html", true) || snippet.contains("<!doctype", true)) {
                RouteResult.Failure("네이버가 데이터 대신 페이지를 보냈어요 (차단/변경 가능성)", snippet)
            } else {
                RouteResult.Failure("응답을 읽을 수 없어요 (형식 오류)", snippet)
            }
        }
        val paths = root.optJSONArray("paths")
            ?: return RouteResult.Failure(root.optString("message").ifBlank { "열차 목록을 받지 못했어요" }, json.take(300))

        val trains = LinkedHashMap<String, RouteTrain>() // 번호별 중복 제거
        for (i in 0 until paths.length()) {
            val path = paths.optJSONObject(i) ?: continue
            if (path.optString("type") != "INTERCITY") continue
            val legs = path.optJSONArray("legs") ?: continue
            for (li in 0 until legs.length()) {
                val steps = legs.optJSONObject(li)?.optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.optJSONObject(si) ?: continue
                    if (step.optString("type") != "TRAIN") continue
                    val route = step.optJSONArray("routes")?.optJSONObject(0) ?: continue
                    val trip = route.optJSONObject("tripInfo") ?: continue
                    val className = trip.optString("className")
                    if (!className.contains("KTX")) continue // KTX만
                    val no = trip.optString("displayVehicleNo").ifBlank { trip.optString("vehicleNo") }
                    if (no.isBlank()) continue
                    val stopsArr = step.optJSONArray("stops")
                    val first = stopsArr?.optJSONObject(0)
                    val last = stopsArr?.optJSONObject(stopsArr.length() - 1)
                    val depTime = parseOffsetTime(first?.optString("departureTime"))
                        ?: parseOffsetTime(step.optString("departureTime")) ?: continue
                    val arrTime = parseOffsetTime(last?.optString("arrivalTime"))
                        ?: parseOffsetTime(step.optString("arrivalTime")) ?: continue
                    val typeName = route.optJSONObject("type")?.optString("name").orEmpty()
                    val company = if (className.contains("SRT", true)) "SRT" else "KORAIL"
                    trains.putIfAbsent(
                        no,
                        RouteTrain(
                            trainNo = no.padStart(5, '0'),
                            displayNo = no.trimStart('0').ifBlank { no },
                            trainName = typeName.ifBlank { className },
                            company = company,
                            depTime = depTime,
                            arrTime = arrTime,
                            depStation = first?.optString("name").orEmpty(),
                            arrStation = last?.optString("name").orEmpty(),
                        ),
                    )
                }
            }
        }
        return RouteResult.Success(trains.values.sortedBy { it.depTime })
    }
}

class NaverClient(
    private val userAgent: String =
        "Mozilla/5.0 (Linux; Android 14; SM-F741N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
) : TrainSource {

    /** host 안 쓰고 단순하게: name=value 누적 */
    private val cookies = linkedMapOf<String, String>()

    private fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun storeCookies(conn: HttpURLConnection) {
        val list = conn.headerFields["Set-Cookie"] ?: conn.headerFields["set-cookie"] ?: return
        for (line in list) {
            val pair = line.substringBefore(";").trim()
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=", "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
        }
    }

    /** 먼저 네이버 페이지를 한 번 열어 익명 쿠키(NNB 등)를 받는다. */
    private fun warmUp() {
        try {
            val conn = (URL(BRIDGE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
                if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookieHeader())
            }
            conn.inputStream.use { it.readBytes() }
            storeCookies(conn)
            conn.disconnect()
        } catch (e: Exception) {
            // 쿠키 못 받아도 일단 호출은 시도
        }
    }

    override fun lookup(trainNo: String, company: String, date: LocalDate): LookupResult {
        val padded = padTrainNo(trainNo)
        if (padded.all { it == '0' }) return LookupResult.Failure("열차 번호가 비어 있어요")
        if (cookies.isEmpty()) warmUp()
        var result = post(padded, company, date)
        // 인증/차단으로 보이면 쿠키를 새로 받아 한 번 더
        if (result is LookupResult.Failure && cookies.isNotEmpty()) {
            cookies.clear()
            warmUp()
            result = post(padded, company, date)
        }
        return result
    }

    private fun post(trainNo: String, company: String, date: LocalDate): LookupResult {
        val body = JSONObject()
            .put("runDate", date.format(YMD))
            .put("trainNumber", trainNo)
            .put("railwayCompany", if (company.isBlank()) "KORAIL" else company)
            .toString()
        return try {
            val conn = (URL(TRAIN_TIME_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", ORIGIN)
                setRequestProperty("Referer", BRIDGE_URL)
                if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookieHeader())
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            storeCookies(conn)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                LookupResult.Failure("네이버 응답 오류 (HTTP $code)", text.take(300))
            } else {
                NaverParser.parse(text)
            }
        } catch (e: IOException) {
            LookupResult.Failure("인터넷 연결 또는 네이버 서버 문제: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            LookupResult.Failure("조회 중 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 노선(출발역→도착역)의 그 시간대 KTX 목록을 네이버 길찾기로 가져온다. */
    fun searchRoute(dep: Station, arr: Station, dateTime: LocalDateTime): RouteResult {
        if (cookies.isEmpty()) warmUp()
        val iso = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "+09:00"
        val url = "$TRANSIT_URL?start=${dep.lng},${dep.lat}&goal=${arr.lng},${arr.lat}" +
            "&mode=TIME&departureTime=${URLEncoder.encode(iso, "UTF-8")}"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
                setRequestProperty("Referer", "https://map.naver.com/")
                if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookieHeader())
            }
            val code = conn.responseCode
            storeCookies(conn)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) RouteResult.Failure("네이버 응답 오류 (HTTP $code)", text.take(300))
            else NaverRouteParser.parse(text)
        } catch (e: IOException) {
            RouteResult.Failure("인터넷 연결 또는 네이버 서버 문제: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            RouteResult.Failure("조회 중 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val ORIGIN = "https://pt.map.naver.com"
        const val TRANSIT_URL = "https://map.naver.com/p/api/directions/transit"
        const val TRAIN_TIME_URL = "$ORIGIN/end-train/api/train-time"
        const val BRIDGE_URL =
            "$ORIGIN/end-train/bridges/train/web/home?menu=schedule&lang=ko&svcName=map_pcv5"
    }
}
