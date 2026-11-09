package com.mouninaveen.collector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Visualizes WebRTC's own audio stats; does not open the microphone or record audio.
 * A flat meter means the stats report silence/unavailable levels, not proof of failure.
 */
@Composable
internal fun AudioLevelMeter(label: String, level: Float, active: Boolean) {
    val bounded = if (active) level.coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(
            progress = { bounded },
            modifier = Modifier.fillMaxWidth().height(14.dp),
            color = when {
                !active -> Color(0xFF929DAD)
                bounded >= 0.72f -> Color(0xFFE27A28)
                bounded >= 0.28f -> Color(0xFF1AB39C)
                else -> Color(0xFF3974C3)
            },
            trackColor = Color(0xFFE2EAF5)
        )
        Text(
            if (!active) "Not connected"
            else if (bounded < 0.01f) "Silent or level data unavailable"
            else "Audio activity ${(bounded * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
