package io.github.jstn0713.ktxdelay.core

import java.time.LocalTime

data class TrainStats(
    val historyKey: String,
    val label: String,
    val depStation: String,
    val arrStation: String,
    val depTime: LocalTime,
    /** 조회에 성공한 날 수 */
    val days: Int,
    /** 1분 이상 지연된 날 수 */
    val delayedDays: Int,
    /** 5분 이상 지연된 날 수 */
    val over5Days: Int,
    val avgDelay: Double,
    val maxDelay: Int,
    /** 지연이 처음 표시된 시점의 평균 (출발 몇 분 전). 확인 시작 때 이미 표시돼 있던 날은 제외 */
    val avgFirstMinBefore: Double?,
    /** 확인을 시작했을 때 이미 지연이 표시돼 있던 날 수 */
    val presentAtStartDays: Int,
    val cancelledDays: Int,
)

object Stats {
    fun compute(history: List<HistoryRecord>): List<TrainStats> =
        history.groupBy { it.historyKey }.map { (key, recs) ->
            val latest = recs.maxBy { it.date }
            val withData = recs.filter { it.finalDelay != null && !it.cancelled }
            val finals = withData.map { it.finalDelay!! }
            val firsts = withData.filter { it.firstDelayMinBefore != null && !it.presentAtStart }
                .map { it.firstDelayMinBefore!! }
            TrainStats(
                historyKey = key,
                label = latest.label,
                depStation = latest.depStation,
                arrStation = latest.arrStation,
                depTime = latest.depTime,
                days = withData.size,
                delayedDays = finals.count { it >= 1 },
                over5Days = finals.count { it >= 5 },
                avgDelay = if (finals.isEmpty()) 0.0 else finals.average(),
                maxDelay = withData.maxOfOrNull { it.maxDelay ?: it.finalDelay ?: 0 } ?: 0,
                avgFirstMinBefore = if (firsts.isEmpty()) null else firsts.average(),
                presentAtStartDays = withData.count { it.presentAtStart },
                cancelledDays = recs.count { it.cancelled },
            )
        }.sortedWith(compareBy({ it.depTime }, { it.label }))
}
