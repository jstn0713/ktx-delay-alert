package io.github.jstn0713.ktxdelay.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jstn0713.ktxdelay.core.DAY_NAMES
import io.github.jstn0713.ktxdelay.core.KorailClient
import io.github.jstn0713.ktxdelay.core.KorailTrain
import io.github.jstn0713.ktxdelay.core.SearchResult
import io.github.jstn0713.ktxdelay.core.Stations
import io.github.jstn0713.ktxdelay.core.Texts
import io.github.jstn0713.ktxdelay.core.WatchTrain
import io.github.jstn0713.ktxdelay.core.hm
import io.github.jstn0713.ktxdelay.core.nowKst
import io.github.jstn0713.ktxdelay.core.parseHm
import io.github.jstn0713.ktxdelay.core.shortLabel
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.service.MonitorService
import io.github.jstn0713.ktxdelay.service.Scheduling
import io.github.jstn0713.ktxdelay.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

private fun afterChange(ctx: android.content.Context) {
    Scheduling.reschedule(ctx)
    MonitorService.refreshIfRunning(ctx)
    WidgetUpdater.update(ctx)
}

@Composable
fun TrainsScreen(onEdit: (WatchTrain) -> Unit) {
    val ctx = LocalContext.current
    val trains by AppRepo.trains.collectAsState()
    val sorted = trains.sortedWith(compareBy({ it.days.minOrNull() ?: 8 }, { it.depTime }))

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (trains.isEmpty()) {
            item {
                SectionCard("등록된 열차가 없어요") {
                    Text("오른쪽 아래 + 버튼을 눌러 타는 열차를 추가하세요.")
                    Text(
                        "같은 열차를 여러 요일에 타면 한 번에 여러 요일을 고를 수 있어요. " +
                            "같은 날 여러 열차(예: 19:45, 19:54, 20:12)를 모두 보고 싶으면 각각 추가하세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(sorted, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(t) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (t.days.isEmpty()) "요일 없음" else "${t.daysLabel}요일",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${t.depTime.hm()}  ${t.depStation} → ${t.arrStation}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            t.label + (t.arrTime?.let { " · ${it.hm()} 도착" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = t.enabled, onCheckedChange = {
                        AppRepo.upsertTrain(t.copy(enabled = it))
                        afterChange(ctx)
                    })
                }
            }
        }
        item { Spacer(Modifier.size(80.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainEditorScreen(initial: WatchTrain, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppRepo.settings.collectAsState()

    var days by remember { mutableStateOf(initial.days) }
    var dep by remember { mutableStateOf(initial.depStation) }
    var arr by remember { mutableStateOf(initial.arrStation) }
    var timeText by remember { mutableStateOf(if (isNew) "" else initial.depTime.hm()) }
    var trainNo by remember { mutableStateOf(initial.trainNo) }
    var trainName by remember { mutableStateOf(initial.trainName) }
    var arrTime by remember { mutableStateOf(initial.arrTime) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    var searchOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchDate by remember { mutableStateOf(LocalDate.now()) }
    var searchTrains by remember { mutableStateOf<List<KorailTrain>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    fun runSearch(from: LocalTime, append: Boolean) {
        searching = true
        searchError = null
        scope.launch {
            val today = nowKst().toLocalDate()
            // 고른 요일 중 가장 가까운 날로 조회 (요일을 안 골랐으면 오늘)
            val date = if (days.isEmpty()) today
            else (0..7).map { today.plusDays(it.toLong()) }.first { it.dayOfWeek.value in days }
            val res = withContext(Dispatchers.IO) {
                val client = KorailClient(settings.korailVersion)
                val r = client.search(dep, arr, date, from, false)
                if (r is SearchResult.Success && r.trains.isEmpty()) client.search(dep, arr, date, from, true) else r
            }
            searchDate = date
            when (res) {
                is SearchResult.Success -> {
                    val merged = if (append) (searchTrains + res.trains).distinctBy { it.trainNo + it.depTime } else res.trains
                    searchTrains = merged
                    if (merged.isEmpty()) searchError = "조회된 열차가 없어요. 역 이름을 확인하세요."
                }
                is SearchResult.Failure -> searchError = res.message
            }
            searching = false
        }
    }

    fun save() {
        val t = parseHm(timeText)
        error = when {
            days.isEmpty() -> "요일을 하나 이상 고르세요."
            dep.isBlank() || arr.isBlank() -> "타는 역과 내리는 역을 입력하세요."
            dep.trim() == arr.trim() -> "타는 역과 내리는 역이 같아요."
            t == null -> "출발 시각을 20:12 처럼 입력하세요."
            else -> null
        }
        if (error != null || t == null) return
        AppRepo.upsertTrain(
            initial.copy(
                days = days,
                depStation = dep.trim(),
                arrStation = arr.trim(),
                depTime = t,
                trainNo = trainNo.filter { it.isDigit() }.trimStart('0'),
                trainName = trainName,
                arrTime = arrTime,
            ),
        )
        afterChange(ctx)
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "열차 추가" else "열차 수정") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "삭제") }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("① 타는 요일", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DAY_NAMES.forEachIndexed { i, name ->
                    val d = i + 1
                    val selected = d in days
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { days = if (selected) days - d else days + d },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Text("② 구간", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            StationField("타는 역", dep) { dep = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { val x = dep; dep = arr; arr = x }) { Text("⇅ 바꾸기") }
            }
            StationField("내리는 역", arr) { arr = it }

            Text("③ 열차", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "'열차 찾기'로 코레일 시간표에서 고르면 시각과 번호가 자동으로 들어가요. 직접 입력해도 돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = dep.isNotBlank() && arr.isNotBlank() && !searching,
                onClick = {
                    searchOpen = true
                    searchTrains = emptyList()
                    val base = parseHm(timeText)
                    val from = when {
                        base == null -> LocalTime.of(5, 0)
                        base.hour == 0 -> LocalTime.MIN
                        else -> base.minusMinutes(60)
                    }
                    runSearch(from, append = false)
                },
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text("  열차 찾기")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it; arrTime = null },
                    label = { Text("출발 시각 (예: 20:12)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    val t = parseHm(timeText) ?: LocalTime.of(8, 0)
                    TimePickerDialog(ctx, { _, h, m ->
                        timeText = LocalTime.of(h, m).hm()
                        arrTime = null
                    }, t.hour, t.minute, true).show()
                }) { Text("시각 선택") }
            }
            OutlinedTextField(
                value = trainNo,
                onValueChange = { trainNo = it.filter { c -> c.isDigit() } },
                label = { Text("열차 번호 (모르면 비워두세요)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (trainName.isNotBlank() || arrTime != null) {
                Text(
                    listOfNotNull(trainName.ifBlank { null }, arrTime?.let { "$arr ${it.hm()} 도착" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("저장") }
            Spacer(Modifier.size(40.dp))
        }
    }

    if (searchOpen) {
        AlertDialog(
            onDismissRequest = { searchOpen = false },
            title = { Text("$dep → $arr · ${searchDate.shortLabel()}") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    searchTrains.forEach { t ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                t.depLocalTime?.let { timeText = it.hm() }
                                trainNo = t.trainNo
                                trainName = t.trainName
                                arrTime = t.arrLocalTime
                                searchOpen = false
                            }.padding(vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${t.depLocalTime?.hm() ?: "?"} → ${t.arrLocalTime?.hm() ?: "?"}",
                                    Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                )
                                if (t.delayMin > 0) Text(Texts.delayText(t.delayMin), color = delayRed())
                            }
                            Text(t.label, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                    if (searching) CircularProgressIndicator(Modifier.padding(12.dp).size(28.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !searching && searchTrains.isNotEmpty(),
                    onClick = {
                        val last = searchTrains.mapNotNull { it.depLocalTime }.maxOrNull() ?: return@TextButton
                        runSearch(last.plusMinutes(1), append = true)
                    },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text(" 다음 열차 더 보기")
                }
            },
            dismissButton = { TextButton(onClick = { searchOpen = false }) { Text("닫기") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 열차를 삭제할까요?") },
            text = { Text("${initial.daysLabel}요일 ${initial.depTime.hm()} ${initial.depStation} → ${initial.arrStation}\n(지난 기록은 남아 있어요)") },
            confirmButton = {
                TextButton(onClick = {
                    AppRepo.deleteTrain(initial.id)
                    afterChange(ctx)
                    confirmDelete = false
                    onClose()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun StationField(label: String, value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        val suggestions = Stations.suggest(value)
        if (focused && suggestions.isNotEmpty() && value !in Stations.KTX) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { s -> AssistChip(onClick = { onChange(s) }, label = { Text(s) }) }
            }
        }
    }
}
