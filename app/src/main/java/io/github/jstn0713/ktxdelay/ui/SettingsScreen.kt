package io.github.jstn0713.ktxdelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jstn0713.ktxdelay.BuildConfig
import io.github.jstn0713.ktxdelay.core.AppSettings
import io.github.jstn0713.ktxdelay.core.DEFAULT_KORAIL_VERSION
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.data.UpdateChecker
import io.github.jstn0713.ktxdelay.service.MonitorService
import io.github.jstn0713.ktxdelay.service.Scheduling
import io.github.jstn0713.ktxdelay.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s by AppRepo.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var updateMsg by remember { mutableStateOf<String?>(null) }

    fun save(n: AppSettings) {
        AppRepo.saveSettings(n)
        Scheduling.reschedule(ctx)
        MonitorService.refreshIfRunning(ctx)
        WidgetUpdater.update(ctx)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("확인 시간") {
            StepperRow("출발 몇 분 전부터 확인", s.startBeforeMin, 10..180, 10, "분 전") { save(s.copy(startBeforeMin = it)) }
            StepperRow("내 역 출발 후 종료", s.endAfterDepartureMin, 0..30, 1, "분 뒤") { save(s.copy(endAfterDepartureMin = it)) }
            HorizontalDivider()
            StepperRow("평소 확인 간격", s.normalIntervalMin, 1..10, 1, "분") { save(s.copy(normalIntervalMin = it)) }
            StepperRow("빠르게 확인 시작", s.fastWindowMin, 0..120, 5, "분 전") { save(s.copy(fastWindowMin = it)) }
            StepperRow("빠른 확인 간격", s.fastIntervalMin, 1..10, 1, "분") { save(s.copy(fastIntervalMin = it)) }
            Text(
                "예: 출발 60분 전부터 2분마다 확인하다가, 출발 30분 전부터는 1분마다 확인해요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("소리·진동 알림") {
            SwitchRow(
                "소리·진동 알림 사용",
                s.alertsEnabled,
                "끄면 상단바 표시만 하고 소리는 안 내요.",
            ) { save(s.copy(alertsEnabled = it)) }
            StepperRow("처음 알림: 지연이", s.minDelayToAlert, 1..30, 1, "분 이상") { save(s.copy(minDelayToAlert = it)) }
            StepperRow("다시 알림: 지연이", s.alertChangeMin, 1..30, 1, "분 바뀌면") { save(s.copy(alertChangeMin = it)) }
            Text("운행 취소·중지는 항상 알려드려요.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { PhoneSetup.openAlertChannelSettings(ctx) }) { Text("알림 소리·진동 방식 바꾸기") }
        }

        SectionCard("휴대폰 설정") {
            Text(
                "삼성폰은 배터리 절약 때문에 앱이 잠들 수 있어요. 아래를 눌러 '제한 없음'으로 해주세요.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { PhoneSetup.requestBatteryUnrestricted(ctx) }) { Text("배터리 제한 없음으로") }
            OutlinedButton(onClick = { PhoneSetup.openNotificationSettings(ctx) }) { Text("알림 설정 열기") }
            OutlinedButton(onClick = { PhoneSetup.openAppDetails(ctx) }) { Text("앱 정보(배터리·권한) 열기") }
        }

        SectionCard("앱 정보") {
            Text("KTX 지연 알림 v${BuildConfig.VERSION_NAME}")
            Text(
                "지연 정보는 네이버 지도의 기차 조회에서 가져와요(공식 공개 기능이 아닌 개인용). " +
                    "실제 운행 정보는 코레일·네이버 안내를 우선해 주세요.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                updateMsg = "확인 중…"
                scope.launch {
                    val info = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                    if (info != null) {
                        AppRepo.update.value = info
                        AppRepo.updateCheckedAt = System.currentTimeMillis()
                    }
                    updateMsg = when {
                        info == null -> "확인하지 못했어요. 인터넷 연결을 확인하세요."
                        info.isNewer -> "새 버전 v${info.versionName}이 있어요. 홈 화면 맨 위에서 다운로드하세요."
                        else -> "최신 버전이에요."
                    }
                }
            }) { Text("업데이트 확인") }
            updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { PhoneSetup.openUrl(ctx, UpdateChecker.releasesPage) }) { Text("GitHub 다운로드 페이지 열기") }
        }
        Spacer(Modifier.size(24.dp))
    }
}
