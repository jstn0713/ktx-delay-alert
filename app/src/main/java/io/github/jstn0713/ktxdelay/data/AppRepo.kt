package io.github.jstn0713.ktxdelay.data

import android.content.Context
import android.content.SharedPreferences
import io.github.jstn0713.ktxdelay.core.AppSettings
import io.github.jstn0713.ktxdelay.core.Codec
import io.github.jstn0713.ktxdelay.core.HistoryRecord
import io.github.jstn0713.ktxdelay.core.TrainStatus
import io.github.jstn0713.ktxdelay.core.WatchTrain
import io.github.jstn0713.ktxdelay.core.nowKst
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime

/** 앱 전체가 함께 쓰는 데이터. 휴대폰 안(SharedPreferences)에만 저장된다. */
object AppRepo {
    private const val K_TRAINS = "trains"
    private const val K_SETTINGS = "settings"
    private const val K_STATUSES = "statuses"
    private const val K_HISTORY = "history"
    private const val K_SKIP = "skipDate"
    private const val K_FINISHED = "finishedKeys"
    private const val K_UPDATE_CHECKED = "updateCheckedAt"
    private const val MAX_HISTORY = 1000

    private lateinit var prefs: SharedPreferences

    val trains = MutableStateFlow<List<WatchTrain>>(emptyList())
    val settings = MutableStateFlow(AppSettings())
    /** 지금 감시 중인 열차들 */
    val statuses = MutableStateFlow<List<TrainStatus>>(emptyList())
    val history = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val skipDate = MutableStateFlow<LocalDate?>(null)
    val monitoring = MutableStateFlow(false)
    val lastPollAt = MutableStateFlow<LocalDateTime?>(null)
    val pollIntervalMin = MutableStateFlow<Int?>(null)
    /** 화면이 다시 보일 때마다 1씩 증가 (권한 상태 다시 읽기용) */
    val resumeTick = MutableStateFlow(0)
    val update = MutableStateFlow<UpdateInfo?>(null)

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("ktx", Context.MODE_PRIVATE)
        trains.value = Codec.trainsFromJson(prefs.getString(K_TRAINS, null))
        settings.value = Codec.settingsFromJson(prefs.getString(K_SETTINGS, null))
        statuses.value = Codec.statusesFromJson(prefs.getString(K_STATUSES, null))
        history.value = Codec.historyListFromJson(prefs.getString(K_HISTORY, null))
        skipDate.value = prefs.getString(K_SKIP, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    @Synchronized
    fun saveTrains(list: List<WatchTrain>) {
        trains.value = list
        prefs.edit().putString(K_TRAINS, Codec.trainsToJson(list)).apply()
    }

    fun upsertTrain(t: WatchTrain) {
        val list = trains.value
        saveTrains(if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t)
    }

    fun deleteTrain(id: String) = saveTrains(trains.value.filter { it.id != id })

    @Synchronized
    fun saveSettings(s: AppSettings) {
        settings.value = s
        prefs.edit().putString(K_SETTINGS, Codec.settingsToJson(s)).apply()
    }

    @Synchronized
    fun setStatuses(list: List<TrainStatus>) {
        statuses.value = list
        prefs.edit().putString(K_STATUSES, Codec.statusesToJson(list)).apply()
    }

    fun statusMap(): Map<String, TrainStatus> = statuses.value.associateBy { it.key }

    @Synchronized
    fun addHistory(records: List<HistoryRecord>) {
        if (records.isEmpty()) return
        val keys = records.map { it.key }.toSet()
        val list = (history.value.filter { it.key !in keys } + records)
            .sortedWith(compareBy({ it.date }, { it.depTime }))
            .takeLast(MAX_HISTORY)
        history.value = list
        prefs.edit().putString(K_HISTORY, Codec.historyListToJson(list)).apply()
    }

    @Synchronized
    fun clearHistory() {
        history.value = emptyList()
        prefs.edit().remove(K_HISTORY).apply()
    }

    /** 감시가 끝난 열차 키 ("열차id@날짜") — 같은 날 다시 감시하지 않도록 */
    @Synchronized
    fun finishedKeys(): Set<String> {
        val json = prefs.getString(K_FINISHED, null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    @Synchronized
    fun addFinished(keys: List<String>) {
        if (keys.isEmpty()) return
        val cutoff = nowKst().toLocalDate().minusDays(3)
        val all = (finishedKeys() + keys).filter { k ->
            val d = runCatching { LocalDate.parse(k.substringAfterLast('@')) }.getOrNull()
            d == null || !d.isBefore(cutoff)
        }
        prefs.edit().putString(K_FINISHED, JSONArray(all).toString()).apply()
    }

    @Synchronized
    fun setSkipToday(skip: Boolean) {
        val d = if (skip) nowKst().toLocalDate() else null
        skipDate.value = d
        prefs.edit().putString(K_SKIP, d?.toString()).apply()
    }

    fun isSkippedToday(): Boolean = skipDate.value == nowKst().toLocalDate()

    var updateCheckedAt: Long
        get() = prefs.getLong(K_UPDATE_CHECKED, 0L)
        set(v) = prefs.edit().putLong(K_UPDATE_CHECKED, v).apply()
}
