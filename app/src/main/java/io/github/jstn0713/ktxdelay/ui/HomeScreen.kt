package io.github.jstn0713.ktxdelay.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import io.github.jstn0713.ktxdelay.core.LookupResult
import io.github.jstn0713.ktxdelay.core.Monitor
import io.github.jstn0713.ktxdelay.core.NaverClient
import io.github.jstn0713.ktxdelay.core.NaverStop
import io.github.jstn0713.ktxdelay.core.NaverTrain
import io.github.jstn0713.ktxdelay.core.Texts
import io.github.jstn0713.ktxdelay.core.TrainStatus
import io.github.jstn0713.ktxdelay.core.hm
import io.github.jstn0713.ktxdelay.core.nowKst
import io.github.jstn0713.ktxdelay.core.shortLabel
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.data.UpdateInfo
import io.github.jstn0713.ktxdelay.service.MonitorService
import io.github.jstn0713.ktxdelay.service.Scheduling
import io.github.jstn0713.ktxdelay.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HomeScreen(onAddTrain: () -> Unit, onOpenTrains: () -> Unit) {
    val trains by AppRepo.trains.collectAsState()
    val statuses by AppRepo.statuses.collectAsState()
    val settings by AppRepo.settings.collectAsState()
    val skipDate by AppRepo.skipDate.collectAsState()
    val monitoring by AppRepo.monitoring.collectAsState()
    val lastPoll by AppRepo.lastPollAt.collectAsState()
    val interval by AppRepo.pollIntervalMin.collectAsState()
    val history by AppRepo.history.collectAsState()
    val update by AppRepo.update.collectAsState()
    var now by remember { mutableStateOf(nowKst()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = nowKst()
        }
    }
    val live = statuses.filter { !Monitor.isFinished(it, now, settings) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        update?.let { u -> if (u.isNewer) item { UpdateCard(u) } }
        item { SetupCard() }
        if (trains.isEmpty()) {
            item {
                SectionCard("시작하기") {
                    Text("알림 받을 열차를 먼저 등록하세요. 요일, 타는 역, 내리는 역, 출발 시각을 넣으면 돼요.")
                    Button(onClick = onAddTrain) { Text("열차 추가하기") }
                }
            }
        }
        if (live.isNotEmpty()) item { LiveCard(live, monitoring, lastPoll, interval) }
        if (trains.isNotEmpty()) {
            item { TodayCard(now, skipDate, live, history.filter { it.date == now.toLocalDate() }.map { it.key to it }.toMap(), onOpenTrains) }
        }
        item { TestCard() }
        item { Spacer(Modifier.size(8.dp)) }
    }
}

@Composable
private fun UpdateCard(u: UpdateInfo) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("새 버전이 있어요 (v${u.versionName})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("다운로드한 파일을 열어 설치하면 설정과 기록은 그대로 유지돼요.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { PhoneSetup.openUrl(ctx, u.apkUrl) }) { Text("새 버전 다운로드") }
        }
    }
}

/** 알림 권한·배터리 설정이 안 돼 있으면 보여주는 안내 */
@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    val tick by AppRepo.resumeTick.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    // tick/refresh 가 바뀔 때마다 다시 계산
    val notifOk = remember(tick, refresh) { PhoneSetup.notificationsEnabled(ctx) }
    val batteryOk = remember(tick, refresh) { PhoneSetup.batteryUnrestricted(ctx) }
    val alarmOk = remember(tick, refresh) { PhoneSetup.exactAlarmsAllowed(ctx) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !PhoneSetup.notificationsEnabled(ctx)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (notifOk && batteryOk && alarmOk) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text("  휴대폰 설정이 필요해요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (!notifOk) {
                Text("① 알림 허용: 상단바에 지연 정보를 띄우려면 꼭 필요해요.")
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else PhoneSetup.openNotificationSettings(ctx)
                }) { Text("알림 허용하기") }
                TextButton(onClick = { PhoneSetup.openNotificationSettings(ctx) }) { Text("허용 창이 안 뜨면: 알림 설정 열기") }
            }
            if (!batteryOk) {
                Text("② 배터리 '제한 없음': 삼성폰이 앱을 잠재우면 알림이 늦거나 안 와요. 다음 창에서 '허용'을 누르세요.")
                Button(onClick = { PhoneSetup.requestBatteryUnrestricted(ctx) }) { Text("배터리 제한 없음으로") }
            }
            if (!alarmOk) {
                Text("③ 알람 및 리마인더 허용: 정확한 시각에 확인을 시작하려면 필요해요.")
                Button(onClick = { PhoneSetup.openExactAlarmSettings(ctx) }) { Text("알람 권한 허용") }
            }
        }
    }
}

@Composable
private fun LiveCard(live: List<TrainStatus>, monitoring: Boolean, lastPoll: java.time.LocalDateTime?, interval: Int?) {
    val ctx = LocalContext.current
    SectionCard(if (monitoring) "실시간 지연 · 감시 중" else "실시간 지연") {
        live.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            StatusBlock(s)
        }
        Text(
            buildString {
                append(lastPoll?.let { "마지막 확인 ${it.hm()}" } ?: "확인 준비 중")
                if (interval != null) append(" · ${interval}분마다 확인")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (monitoring) {
            OutlinedButton(onClick = { MonitorService.refreshIfRunning(ctx) }) { Text("지금 다시 확인") }
        }
    }
}

@Composable
fun StatusBlock(s: TrainStatus) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.scheduledDep.hm()} ${s.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${s.depStation} → ${s.arrStation}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                Texts.delayShort(s),
                color = statusColor(s),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Text(Texts.depLine(s), style = MaterialTheme.typography.bodyMedium)
        Text(Texts.arrLine(s), style = MaterialTheme.typography.bodyMedium)
        if (s.message != null) {
            Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TodayCard(
    now: java.time.LocalDateTime,
    skipDate: LocalDate?,
    live: List<TrainStatus>,
    doneToday: Map<String, io.github.jstn0713.ktxdelay.core.HistoryRecord>,
    onOpenTrains: () -> Unit,
) {
    val ctx = LocalContext.current
    val trains by AppRepo.trains.collectAsState()
    val settings by AppRepo.settings.collectAsState()
    val today = now.toLocalDate()
    val skipped = skipDate == today
    val todays = Monitor.occurrences(trains, today, 1).sortedBy { it.scheduledDep }
    val liveKeys = live.map { it.key }.toSet()
    val finishedKeys = AppRepo.finishedKeys()

    SectionCard("오늘 ${today.shortLabel()}") {
        if (todays.isEmpty()) {
            Text("오늘은 알림 받을 열차가 없어요.")
        } else {
            todays.forEach { occ ->
                val start = Monitor.windowStart(occ, settings)
                val done = doneToday[occ.key]
                val liveStatus = live.firstOrNull { it.key == occ.key }
                val state = when {
                    liveStatus != null -> Texts.delayShort(liveStatus) // 조회 중…/정시/N분 지연
                    done != null -> "끝남 · " + when {
                        done.cancelled -> "운행 취소"
                        done.finalDelay == null -> "정보 없음"
                        else -> Texts.delayText(done.finalDelay)
                    }
                    skipped -> "오늘은 꺼짐"
                    now.isBefore(start) -> "${start.hm()}부터 확인"
                    // 네이버로 확인해 보니 이미 떠난 열차
                    occ.key in finishedKeys -> "출발함"
                    // 예정 시각은 지났어도 지연 중일 수 있어 확인하는 중
                    now.isBefore(occ.scheduledDep.plusMinutes(Monitor.LATE_JOIN_GRACE_MIN)) -> "출발 여부 확인 중"
                    else -> "지나감"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${occ.train.depTime.hm()} ${occ.train.label}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (skipped) {
                Button(onClick = {
                    AppRepo.setSkipToday(false)
                    Scheduling.reschedule(ctx)
                    WidgetUpdater.update(ctx)
                }) { Text("오늘 다시 켜기") }
            } else {
                OutlinedButton(onClick = {
                    AppRepo.setSkipToday(true)
                    MonitorService.refreshIfRunning(ctx)
                    Scheduling.reschedule(ctx)
                    WidgetUpdater.update(ctx)
                }) { Text("오늘은 끄기") }
            }
        }
        val next = Monitor.nextOccurrence(now, trains, settings, skipDate, AppRepo.finishedKeys())
        if (next != null && next.date != today) {
            Text(
                "다음 알림: ${next.date.shortLabel()} ${next.train.depTime.hm()} ${next.train.label} " +
                    "(${Monitor.windowStart(next, settings).hm()}부터 확인)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenTrains) { Text("열차 목록 보기") }
    }
}

/** 네이버 조회가 잘 되는지 바로 확인하는 카드 */
@Composable
private fun TestCard() {
    val ctx = LocalContext.current
    val trains by AppRepo.trains.collectAsState()
    val scope = rememberCoroutineScope()
    var number by remember(trains) { mutableStateOf(trains.firstOrNull { it.trainNo.isNotBlank() }?.trainNo ?: "") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestResult?>(null) }

    SectionCard("네이버 조회 테스트") {
        Text(
            "열차 번호로 지금 네이버에서 지연 정보를 가져오는지 확인해요. 결과를 네이버 지도와 비교해 보세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.filter { c -> c.isDigit() } },
                label = { Text("열차 번호 (예: 352)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = !loading && number.isNotBlank(),
                onClick = {
                    loading = true
                    scope.launch {
                        result = withContext(Dispatchers.IO) { runTest(number) }
                        loading = false
                    }
                },
            ) { Text("지금 조회") }
        }
        if (loading) CircularProgressIndicator(Modifier.size(24.dp))
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(r.title) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.error != null) Text(r.error, color = MaterialTheme.colorScheme.error)
                    if (r.train != null) {
                        Text("${r.train.routeName} · ${r.train.company}", style = MaterialTheme.typography.bodySmall)
                        r.train.stops.forEach { s -> TestStopRow(s) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("닫기") } },
            dismissButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("naver", r.raw))
                    Toast.makeText(ctx, "원본 데이터를 복사했어요", Toast.LENGTH_SHORT).show()
                }) { Text("원본 복사") }
            },
        )
    }
}

@Composable
private fun TestStopRow(s: NaverStop) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(s.schedArr?.let { "도착 ${it.hm()}" }, s.schedDep?.let { "출발 ${it.hm()}" }).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            Texts.delayText(s.delayMin),
            color = if (s.delayMin > 0) delayRed() else onTimeGreen(),
            fontWeight = FontWeight.Bold,
        )
    }
}

private data class TestResult(
    val title: String,
    val train: NaverTrain?,
    val error: String?,
    val raw: String,
)

private fun runTest(number: String): TestResult {
    val date = nowKst().toLocalDate()
    val title = "$number 번 (${date.monthValue}/${date.dayOfMonth})"
    return when (val res = NaverClient().lookup(number, "KORAIL", date)) {
        is LookupResult.Success -> TestResult(title, res.train, null, res.train.raw)
        is LookupResult.NotFound -> TestResult(
            title, null,
            "이 번호의 열차가 오늘 조회되지 않아요. 번호·운행일(요일)을 확인하세요.",
            res.rawJson ?: "(없음)",
        )
        is LookupResult.Failure -> TestResult(title, null, "조회 실패: ${res.message}", res.detail ?: res.message)
    }
}
