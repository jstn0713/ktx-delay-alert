package io.github.jstn0713.ktxdelay.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.jstn0713.ktxdelay.core.WatchTrain
import io.github.jstn0713.ktxdelay.data.AppRepo
import io.github.jstn0713.ktxdelay.data.UpdateChecker
import io.github.jstn0713.ktxdelay.service.Scheduling
import io.github.jstn0713.ktxdelay.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRepo.init(this)
        enableEdgeToEdge()
        setContent { KtxTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        AppRepo.resumeTick.value = AppRepo.resumeTick.value + 1
        Scheduling.reschedule(this)
        WidgetUpdater.update(this)
        lifecycleScope.launch(Dispatchers.IO) { UpdateChecker.checkIfDue() }
    }
}

private data class Tab(val label: String, val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("홈", "KTX 지연 알림", Icons.Filled.Home),
    Tab("열차", "알림 받을 열차", Icons.AutoMirrored.Filled.List),
    Tab("기록", "지연 기록", Icons.Filled.DateRange),
    Tab("설정", "설정", Icons.Filled.Settings),
)

/** 새 열차 추가용 기본값 — 마지막으로 등록한 구간을 미리 채워 둔다 */
fun newTrainTemplate(): WatchTrain {
    val last = AppRepo.trains.value.lastOrNull()
    return WatchTrain(
        id = UUID.randomUUID().toString(),
        days = emptySet(),
        depStation = last?.depStation ?: "",
        arrStation = last?.arrStation ?: "",
        depTime = LocalTime.of(8, 0),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<WatchTrain?>(null) }
    var isNew by remember { mutableStateOf(false) }

    val startNew = {
        isNew = true
        editing = newTrainTemplate()
    }

    var introSeen by remember { mutableStateOf(AppRepo.introSeen()) }
    if (!introSeen) {
        IntroDialog(onAccept = { AppRepo.setIntroSeen(); introSeen = true })
    }

    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        TrainEditorScreen(initial = current, isNew = isNew, onClose = { editing = null })
    } else {
        Scaffold(
            topBar = { TopAppBar(title = { Text(TABS[tab].title) }) },
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == 1) {
                    FloatingActionButton(onClick = startNew) { Icon(Icons.Filled.Add, contentDescription = "열차 추가") }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> HomeScreen(onAddTrain = startNew, onOpenTrains = { tab = 1 })
                    1 -> TrainsScreen(onEdit = { isNew = false; editing = it })
                    2 -> HistoryScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun IntroDialog(onAccept: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("먼저 읽어주세요") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                Text("이 앱은 코레일·네이버의 공식 앱이 아니라, 개인이 만든 도구예요.")
                Text(
                    "코레일이 지연 정보를 받아올 공개 수단을 주지 않고 자동 접근도 막아서, " +
                        "대신 네이버 지도의 기차 정보를 받아 보여줍니다. 그 과정에서 실제와 차이가 있을 수 있어요. " +
                        "실제 운행은 코레일·네이버 공식 안내를 우선하세요.",
                )
                Text(
                    "개인적으로만 사용하고, 다른 사람에게 퍼뜨리지 마세요. " +
                        "널리 퍼지면 막혀서 모두 못 쓰게 될 수 있어요.",
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onAccept) { Text("이해했어요") }
        },
    )
}
