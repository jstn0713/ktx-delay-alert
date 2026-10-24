package io.github.jstn0713.ktxdelay.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 저장용 JSON 변환. (휴대폰 안에만 저장된다) */
object Codec {
    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null

    private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

    private fun <T> parseList(json: String?, f: (JSONObject) -> T): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            JSONArray(json).map { o -> runCatching { f(o) }.getOrNull() }.filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- WatchTrain ----
    fun trainToJson(t: WatchTrain) = JSONObject().apply {
        put("id", t.id)
        put("days", JSONArray(t.days.sorted()))
        put("dep", t.depStation)
        put("arr", t.arrStation)
        put("depTime", t.depTime.hm())
        put("no", t.trainNo)
        put("name", t.trainName)
        put("arrTime", t.arrTime?.hm() ?: JSONObject.NULL)
        put("enabled", t.enabled)
    }

    fun trainFromJson(o: JSONObject): WatchTrain {
        val daysArr = o.getJSONArray("days")
        return WatchTrain(
            id = o.getString("id"),
            days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet(),
            depStation = o.getString("dep"),
            arrStation = o.getString("arr"),
            depTime = LocalTime.parse(o.getString("depTime")),
            trainNo = o.optString("no"),
            trainName = o.optString("name"),
            arrTime = o.optStr("arrTime")?.let { LocalTime.parse(it) },
            enabled = o.optBoolean("enabled", true),
        )
    }

    fun trainsToJson(list: List<WatchTrain>): String = JSONArray(list.map { trainToJson(it) }).toString()
    fun trainsFromJson(json: String?): List<WatchTrain> = parseList(json, ::trainFromJson)

    // ---- Settings ----
    fun settingsToJson(s: AppSettings): String = JSONObject().apply {
        put("startBeforeMin", s.startBeforeMin)
        put("endAfterDepartureMin", s.endAfterDepartureMin)
        put("normalIntervalMin", s.normalIntervalMin)
        put("fastIntervalMin", s.fastIntervalMin)
        put("fastWindowMin", s.fastWindowMin)
        put("alertsEnabled", s.alertsEnabled)
        put("minDelayToAlert", s.minDelayToAlert)
        put("alertChangeMin", s.alertChangeMin)
        put("korailVersion", s.korailVersion)
    }.toString()

    fun settingsFromJson(json: String?): AppSettings {
        val d = AppSettings()
        if (json.isNullOrBlank()) return d
        return try {
            val o = JSONObject(json)
            AppSettings(
                startBeforeMin = o.optInt("startBeforeMin", d.startBeforeMin),
                endAfterDepartureMin = o.optInt("endAfterDepartureMin", d.endAfterDepartureMin),
                normalIntervalMin = o.optInt("normalIntervalMin", d.normalIntervalMin),
                fastIntervalMin = o.optInt("fastIntervalMin", d.fastIntervalMin),
                fastWindowMin = o.optInt("fastWindowMin", d.fastWindowMin),
                alertsEnabled = o.optBoolean("alertsEnabled", d.alertsEnabled),
                minDelayToAlert = o.optInt("minDelayToAlert", d.minDelayToAlert),
                alertChangeMin = o.optInt("alertChangeMin", d.alertChangeMin),
                korailVersion = o.optString("korailVersion", d.korailVersion).ifBlank { d.korailVersion },
            )
        } catch (e: Exception) {
            d
        }
    }

    // ---- TrainStatus ----
    private fun dt(o: JSONObject, k: String): LocalDateTime? = o.optStr(k)?.let { LocalDateTime.parse(it) }

    fun statusToJson(s: TrainStatus) = JSONObject().apply {
        put("key", s.key)
        put("trainId", s.trainId)
        put("historyKey", s.historyKey)
        put("label", s.label)
        put("dep", s.depStation)
        put("arr", s.arrStation)
        put("scheduledDep", s.scheduledDep.toString())
        put("scheduledArr", s.scheduledArr?.toString() ?: JSONObject.NULL)
        put("monitorStartedAt", s.monitorStartedAt.toString())
        put("delayMin", s.delayMin ?: JSONObject.NULL)
        put("phase", s.phase.name)
        put("lastCheckedAt", s.lastCheckedAt?.toString() ?: JSONObject.NULL)
        put("lastDataAt", s.lastDataAt?.toString() ?: JSONObject.NULL)
        put("lastAlertedDelay", s.lastAlertedDelay ?: JSONObject.NULL)
        put("firstDelayAt", s.firstDelayAt?.toString() ?: JSONObject.NULL)
        put("maxDelay", s.maxDelay ?: JSONObject.NULL)
        put("timeline", JSONArray(s.timeline.map { JSONObject().put("at", it.at.toString()).put("d", it.delay) }))
        put("message", s.message ?: JSONObject.NULL)
        put("delayRaw", s.delayRaw ?: JSONObject.NULL)
    }

    fun statusFromJson(o: JSONObject): TrainStatus {
        val tl = o.optJSONArray("timeline") ?: JSONArray()
        return TrainStatus(
            key = o.getString("key"),
            trainId = o.getString("trainId"),
            historyKey = o.getString("historyKey"),
            label = o.getString("label"),
            depStation = o.getString("dep"),
            arrStation = o.getString("arr"),
            scheduledDep = LocalDateTime.parse(o.getString("scheduledDep")),
            scheduledArr = dt(o, "scheduledArr"),
            monitorStartedAt = LocalDateTime.parse(o.getString("monitorStartedAt")),
            delayMin = o.optIntOrNull("delayMin"),
            phase = runCatching { Phase.valueOf(o.getString("phase")) }.getOrDefault(Phase.WAITING),
            lastCheckedAt = dt(o, "lastCheckedAt"),
            lastDataAt = dt(o, "lastDataAt"),
            lastAlertedDelay = o.optIntOrNull("lastAlertedDelay"),
            firstDelayAt = dt(o, "firstDelayAt"),
            maxDelay = o.optIntOrNull("maxDelay"),
            timeline = tl.map { TimelinePoint(LocalDateTime.parse(it.getString("at")), it.getInt("d")) },
            message = o.optStr("message"),
            delayRaw = o.optStr("delayRaw"),
        )
    }

    fun statusesToJson(list: List<TrainStatus>): String = JSONArray(list.map { statusToJson(it) }).toString()
    fun statusesFromJson(json: String?): List<TrainStatus> = parseList(json, ::statusFromJson)

    // ---- HistoryRecord ----
    fun historyToJson(r: HistoryRecord) = JSONObject().apply {
        put("key", r.key)
        put("historyKey", r.historyKey)
        put("date", r.date.toString())
        put("label", r.label)
        put("dep", r.depStation)
        put("arr", r.arrStation)
        put("depTime", r.depTime.hm())
        put("finalDelay", r.finalDelay ?: JSONObject.NULL)
        put("maxDelay", r.maxDelay ?: JSONObject.NULL)
        put("firstDelayMinBefore", r.firstDelayMinBefore ?: JSONObject.NULL)
        put("presentAtStart", r.presentAtStart)
        put("monitorStartMinBefore", r.monitorStartMinBefore)
        put("cancelled", r.cancelled)
        put("timeline", JSONArray(r.timeline.map { JSONArray().put(it.first).put(it.second) }))
    }

    fun historyFromJson(o: JSONObject): HistoryRecord {
        val tl = o.optJSONArray("timeline") ?: JSONArray()
        return HistoryRecord(
            key = o.getString("key"),
            historyKey = o.getString("historyKey"),
            date = LocalDate.parse(o.getString("date")),
            label = o.getString("label"),
            depStation = o.getString("dep"),
            arrStation = o.getString("arr"),
            depTime = LocalTime.parse(o.getString("depTime")),
            finalDelay = o.optIntOrNull("finalDelay"),
            maxDelay = o.optIntOrNull("maxDelay"),
            firstDelayMinBefore = o.optIntOrNull("firstDelayMinBefore"),
            presentAtStart = o.optBoolean("presentAtStart"),
            monitorStartMinBefore = o.optInt("monitorStartMinBefore"),
            cancelled = o.optBoolean("cancelled"),
            timeline = (0 until tl.length()).map { val p = tl.getJSONArray(it); p.getInt(0) to p.getInt(1) },
        )
    }

    fun historyListToJson(list: List<HistoryRecord>): String = JSONArray(list.map { historyToJson(it) }).toString()
    fun historyListFromJson(json: String?): List<HistoryRecord> = parseList(json, ::historyFromJson)
}
