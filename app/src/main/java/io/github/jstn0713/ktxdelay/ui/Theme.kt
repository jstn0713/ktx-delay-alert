package io.github.jstn0713.ktxdelay.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.jstn0713.ktxdelay.core.Phase
import io.github.jstn0713.ktxdelay.core.TrainStatus

@Composable
fun KtxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9ECAFF), secondary = Color(0xFFB8C8DA))
        else -> lightColorScheme(primary = Color(0xFF0B5CAD), secondary = Color(0xFF535F70))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun delayRed(): Color = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else Color(0xFFD32F2F)

@Composable
fun onTimeGreen(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
fun statusColor(s: TrainStatus): Color = when {
    s.phase == Phase.CANCELLED -> delayRed()
    s.phase == Phase.RUNNING && s.lastDataAt == null -> MaterialTheme.colorScheme.onSurfaceVariant
    s.phase == Phase.RUNNING && (s.delayMin ?: 0) > 0 -> delayRed()
    s.phase == Phase.RUNNING -> onTimeGreen()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
