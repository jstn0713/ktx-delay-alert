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
