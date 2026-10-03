package com.dmytro.leo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dmytro.leo.assistant.VoiceState
import com.dmytro.leo.R

/** Replaces the input bar during a voice exchange: animated button (tap = stop) and what is happening. */
@Composable
fun VoicePanel(voice: VoiceState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val (status, detail) = when (voice) {
        is VoiceState.Listening -> "Слухаю…" to voice.partial
        is VoiceState.Thinking -> "Думаю…" to voice.heard
        is VoiceState.Speaking -> "Говорю…" to ""
        VoiceState.Idle -> "" to ""
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
            // Halo behind the button: follows voice loudness while listening, pulses otherwise
            Box(
                Modifier
                    .size(80.dp)
                    .scale(haloScale(voice))
                    .alpha(0.25f)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            FilledIconButton(
                onClick = onStop,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    painterResource(if (voice is VoiceState.Listening) R.drawable.ic_mic else R.drawable.ic_stop),
                    contentDescription = "Зупинити",
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun haloScale(voice: VoiceState): Float {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(if (voice is VoiceState.Thinking) 500 else 900), RepeatMode.Reverse),
        label = "pulse",
    )
    // rmsDb is roughly -2 (silence) .. 10 (loud speech)
    val loudness = if (voice is VoiceState.Listening) ((voice.level + 2f) / 12f).coerceIn(0f, 1f) else 0f
    val listeningScale by animateFloatAsState(1f + loudness * 0.4f, label = "level")
    return if (voice is VoiceState.Listening) listeningScale else pulse
}
