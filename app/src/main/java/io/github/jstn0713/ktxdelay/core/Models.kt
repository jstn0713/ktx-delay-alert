package io.github.jstn0713.ktxdelay.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 모든 시간 계산은 휴대폰 시간대와 관계없이 한국 시간 기준. */
val KST: ZoneId = ZoneId.of("Asia/Seoul")

fun nowKst(): LocalDateTime = LocalDateTime.now(KST)

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.hm(): String = format(HM)
fun LocalDateTime.hm(): String = format(HM)

fun parseHm(text: String): LocalTime? {
    val m = Regex("""^\s*(\d{1,2})\s*[:시.]?\s*(\d{2})\s*분?\s*$""").find(text) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    if (h !in 0..23 || min !in 0..59) return null
    return LocalTime.of(h, min)
}

val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

/** DayOfWeek 값(1=월 … 7=일)을 "월" 같은 글자로. */
fun dayName(dayOfWeek: Int): String = DAY_NAMES[(dayOfWeek - 1).coerceIn(0, 6)]

fun LocalDate.shortLabel(): String = "${monthValue}/${dayOfMonth}(${dayName(dayOfWeek.value)})"

/** 사용자가 등록한 "알림 받을 열차". */
data class WatchTrain(
    val id: String,
    /** DayOfWeek 값: 1=월 … 7=일 */
    val days: Set<Int>,
    val depStation: String,
    val arrStation: String,
    val depTime: LocalTime,
    /** 열차 번호(숫자만). 네이버 조회에 꼭 필요하다. */
    val trainNo: String = "",
    /** 예: "KTX-산천" */
    val trainName: String = "",
    val arrTime: LocalTime? = null,
    /** "KORAIL" 또는 "SRT" */
    val railwayCompany: String = "KORAIL",
    val enabled: Boolean = true,
) {
    val label: String
        get() = listOf(trainName.ifBlank { "KTX" }, trainNo).filter { it.isNotBlank() }.joinToString(" ")

    /** 기록(통계)을 묶는 기준. 같은 구간·시각·번호면 수정/재등록해도 같은 통계로 모인다. */
    val historyKey: String
        get() = "$depStation>$arrStation@${depTime.hm()}#$trainNo"

    val daysLabel: String
        get() = days.sorted().joinToString("·") { dayName(it) }
}

data class AppSettings(
    /** 출발 몇 분 전부터 확인을 시작할지 */
    val startBeforeMin: Int = 60,
    /** 열차가 내 역을 떠난 뒤 몇 분 뒤에 끝낼지 */
    val endAfterDepartureMin: Int = 5,
    /** 평소 확인 간격(분) */
    val normalIntervalMin: Int = 2,
    /** 출발이 가까울 때 확인 간격(분) */
    val fastIntervalMin: Int = 1,
    /** 출발 몇 분 전부터 빠른 간격으로 바꿀지 */
    val fastWindowMin: Int = 30,
    /** 소리/진동 알림 사용 */
    val alertsEnabled: Boolean = true,
    /** 처음 지연 알림을 울릴 최소 지연(분) */
    val minDelayToAlert: Int = 1,
    /** 지연이 이만큼(분) 이상 바뀌면 다시 알림 */
    val alertChangeMin: Int = 3,
    /** 코레일 조회 시 보내는 앱 버전 코드(코레일이 바꾸면 여기서 수정) */
    val korailVersion: String = DEFAULT_KORAIL_VERSION,
)

const val DEFAULT_KORAIL_VERSION = "240531001"

/** 특정 날짜에 실제로 타는 열차 1회분. */
data class Occurrence(val train: WatchTrain, val date: LocalDate) {
    val key: String = "${train.id}@$date"
    val scheduledDep: LocalDateTime = LocalDateTime.of(date, train.depTime)
}

enum class Phase {
    /** 아직 조회 결과가 없음 */
    WAITING,
    /** 정상 조회됨 (정시 또는 지연) */
    RUNNING,
    /** 조회 결과에 열차가 없음 */
    NOT_FOUND,
    /** 운행 취소/중지 */
    CANCELLED,
    /** 조회 실패(네트워크 등) */
    ERROR,
}

data class TimelinePoint(val at: LocalDateTime, val delay: Int)

/** 감시 중인 열차 1회분의 현재 상태. */
data class TrainStatus(
    val key: String,
    val trainId: String,
    val historyKey: String,
    val label: String,
    val depStation: String,
    val arrStation: String,
    val scheduledDep: LocalDateTime,
    val scheduledArr: LocalDateTime? = null,
    val monitorStartedAt: LocalDateTime,
    val delayMin: Int? = null,
    /** 내릴 역 도착 지연(분). 출발 지연과 다를 수 있다(회복 운행 등). */
    val arrDelayMin: Int? = null,
    val phase: Phase = Phase.WAITING,
    val lastCheckedAt: LocalDateTime? = null,
    val lastDataAt: LocalDateTime? = null,
    /** 마지막으로 소리 알림을 울렸을 때의 지연(분). 한 번도 안 울렸으면 null */
    val lastAlertedDelay: Int? = null,
    val firstDelayAt: LocalDateTime? = null,
    val maxDelay: Int? = null,
    val timeline: List<TimelinePoint> = emptyList(),
    val message: String? = null,
    val delayRaw: String? = null,
) {
    val expectedDep: LocalDateTime get() = scheduledDep.plusMinutes((delayMin ?: 0).toLong())
    val expectedArr: LocalDateTime? get() = scheduledArr?.plusMinutes((arrDelayMin ?: delayMin ?: 0).toLong())

    companion object {
        fun start(occ: Occurrence, now: LocalDateTime) = TrainStatus(
            key = occ.key,
            trainId = occ.train.id,
            historyKey = occ.train.historyKey,
            label = occ.train.label,
            depStation = occ.train.depStation,
            arrStation = occ.train.arrStation,
            scheduledDep = occ.scheduledDep,
            scheduledArr = occ.train.arrTime?.let { t ->
                LocalDateTime.of(if (t < occ.train.depTime) occ.date.plusDays(1) else occ.date, t)
            },
            monitorStartedAt = now,
        )
    }
}

/** 감시가 끝난 열차 1회분의 기록. */
data class HistoryRecord(
    val key: String,
    val historyKey: String,
    val date: LocalDate,
    val label: String,
    val depStation: String,
    val arrStation: String,
    val depTime: LocalTime,
    /** 출발 직전 마지막으로 확인된 지연(분). 한 번도 조회 못 했으면 null */
    val finalDelay: Int?,
    val maxDelay: Int?,
    /** 지연이 처음 보인 시점이 출발 몇 분 전이었는지 (지연이 없었으면 null) */
    val firstDelayMinBefore: Int?,
    /** 확인을 시작했을 때 이미 지연이 표시되어 있었는지 */
    val presentAtStart: Boolean,
    /** 확인을 시작한 시점이 출발 몇 분 전이었는지 */
    val monitorStartMinBefore: Int,
    val cancelled: Boolean,
    /** (출발 몇 분 전, 지연 분) — 지연 값이 바뀐 지점만 */
    val timeline: List<Pair<Int, Int>>,
) {
    companion object {
        fun from(s: TrainStatus): HistoryRecord {
            val minBefore = { t: LocalDateTime -> Duration.between(t, s.scheduledDep).toMinutes().toInt() }
            val first = s.timeline.firstOrNull()
            return HistoryRecord(
                key = s.key,
                historyKey = s.historyKey,
                date = s.scheduledDep.toLocalDate(),
                label = s.label,
                depStation = s.depStation,
                arrStation = s.arrStation,
                depTime = s.scheduledDep.toLocalTime(),
                finalDelay = if (s.lastDataAt != null) s.delayMin else null,
                maxDelay = s.maxDelay,
                firstDelayMinBefore = s.firstDelayAt?.let(minBefore),
                presentAtStart = first != null && first.delay > 0,
                monitorStartMinBefore = minBefore(s.monitorStartedAt),
                cancelled = s.phase == Phase.CANCELLED,
                timeline = s.timeline.map { minBefore(it.at) to it.delay },
            )
        }
    }
}
