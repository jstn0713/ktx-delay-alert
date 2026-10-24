package io.github.jstn0713.ktxdelay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jstn0713.ktxdelay.core.HistoryRecord
import io.github.jstn0713.ktxdelay.core.Stats
import io.github.jstn0713.ktxdelay.core.TrainStats
import io.github.jstn0713.ktxdelay.core.Texts
import io.github.jstn0713.ktxdelay.core.hm
import io.github.jstn0713.ktxdelay.core.shortLabel
import io.github.jstn0713.ktxdelay.data.AppRepo
import kotlin.math.roundToInt

@Composable
fun HistoryScreen() {
    val history by AppRepo.history.collectAsState()
    val stats = remember(history) { Stats.compute(history) }
    val recent = remember(history) { history.sortedWith(compareByDescending<HistoryRecord> { it.date }.thenByDescending { it.depTime }).take(60) }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (history.isEmpty()) {
            item {
                SectionCard("아직 기록이 없어요") {
                    Text("열차 확인이 끝날 때마다 그날의 지연 기록이 여기에 쌓여요.")
                    Text(
                        "1~2주 쓰면 '내 열차는 보통 몇 분 늦는지', '지연 정보가 출발 몇 분 전에 처음 뜨는지'를 볼 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            return@LazyColumn
        }
        item { Text("열차별 통계", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(stats, key = { it.historyKey }) { StatCard(it) }
        item { Text("최근 기록 (눌러서 자세히)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(recent, key = { it.key }) { RecordRow(it) }
        item {
            TextButton(onClick = { confirmClear = true }) {
                Text("기록 모두 지우기", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("기록을 모두 지울까요?") },
            text = { Text("지운 기록은 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    AppRepo.clearHistory()
                    confirmClear = false
                }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}

private fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString().removeSuffix(".0")

@Composable
private fun StatCard(s: TrainStats) {
    SectionCard("${s.depTime.hm()} ${s.label}") {
        Text("${s.depStation} → ${s.arrStation}", style = MaterialTheme.typography.bodySmall)
        if (s.days == 0) {
            Text("아직 조회된 기록이 없어요.")
        } else {
            Text(
                "기록 ${s.days}일 · 평균 ${s.avgDelay.oneDecimal()}분 지연 · 최대 ${s.maxDelay}분",
                fontWeight = FontWeight.Bold,
            )
            Text("지연된 날 ${s.delayedDays}일 (5분 이상 ${s.over5Days}일)")
            Text(
                s.avgFirstMinBefore?.let { "지연 정보가 처음 뜬 시점: 평균 출발 ${it.oneDecimal()}분 전" }
                    ?: "지연 정보가 처음 뜬 시점: 아직 자료 부족",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.presentAtStartDays > 0) {
                Text(
                    "확인을 시작했을 때 이미 지연이 떠 있던 날: ${s.presentAtStartDays}일 (확인 시작 시각을 더 앞당겨 보세요)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (s.cancelledDays > 0) Text("운행 취소 ${s.cancelledDays}일", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecordRow(r: HistoryRecord) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.date.shortLabel()} ${r.depTime.hm()} ${r.label}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                val (text, delayed) = when {
                    r.cancelled -> "운행 취소" to true
                    r.finalDelay == null -> "정보 없음" to false
                    else -> Texts.delayText(r.finalDelay) to (r.finalDelay > 0)
                }
                Text(
                    text,
                    color = when {
                        r.finalDelay == null && !r.cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                        delayed -> delayRed()
                        else -> onTimeGreen()
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            val sub = buildList {
                when {
                    r.presentAtStart -> add("확인 시작(출발 ${r.monitorStartMinBefore}분 전)부터 지연 표시")
                    r.firstDelayMinBefore != null -> add("처음 표시: 출발 ${r.firstDelayMinBefore}분 전")
                }
                if ((r.maxDelay ?: 0) > (r.finalDelay ?: 0)) add("최대 ${r.maxDelay}분")
            }
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (open) {
                Text(
                    "${r.depStation} → ${r.arrStation}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    if (r.timeline.isEmpty()) "조회된 값이 없어요."
                    else "변화: " + r.timeline.joinToString(" → ") { (before, d) ->
                        val whenText = if (before >= 0) "${before}분 전" else "${-before}분 후"
                        "$whenText ${if (d == 0) "정시" else "${d}분"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
