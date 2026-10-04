package com.dmytro.leo.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dmytro.leo.LeoApp
import com.dmytro.leo.MainActivity
import com.dmytro.leo.ui.VoicePanel
import com.dmytro.leo.ui.theme.LeoTheme
import com.dmytro.leo.ui.theme.LionColors
import kotlinx.coroutines.delay

/**
 * The quick-access assistant: a panel at the bottom over whatever app is open, like Google
 * Assistant. Opened from the widget or the Quick Settings tile; starts listening right away
 * and closes itself when the conversation is over.
 */
class AssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LeoTheme {
                AssistantOverlay(
                    onClose = ::finish,
                    onOpenChat = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                )
            }
        }
    }
}

private val VOICE_PERMISSIONS = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.hasMic() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun AssistantOverlay(onClose: () -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as LeoApp).container
    val session = container.voiceSession
    val voice by session.state.collectAsStateWithLifecycle()
    val hint by session.hint.collectAsStateWithLifecycle()
    val messages by container.chatLog.messages.collectAsStateWithLifecycle()

    // Only this conversation is shown here, not the whole chat history
    val firstMessage = rememberSaveable { mutableIntStateOf(container.chatLog.messages.value.size) }
    var wasActive by rememberSaveable { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) session.start() else onOpenChat()
    }

    LaunchedEffect(Unit) {
        when {
            session.isActive -> Unit // opened while a conversation runs: just show it
            context.hasMic() -> session.start()
            else -> permissions.launch(VOICE_PERMISSIONS)
        }
    }

    // Close a moment after the conversation ends, so the last answer can be read
    LaunchedEffect(voice) {
        if (voice != VoiceState.Idle) wasActive = true
        if (wasActive && voice == VoiceState.Idle && !session.isActive) {
            delay(CLOSE_DELAY_MS)
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            // Tap above the panel: stop and close
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                session.stop()
                onClose()
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, LionColors.Gold.copy(alpha = 0.6f)),
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(top = 16.dp, start = 20.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                messages.drop(firstMessage.intValue).takeLast(4).forEach { message ->
                    val (style, color) = when (message.author) {
                        ChatMessage.Author.User -> MaterialTheme.typography.bodyLarge to MaterialTheme.colorScheme.primary
                        ChatMessage.Author.Agent -> MaterialTheme.typography.bodyLarge to MaterialTheme.colorScheme.onSurface
                        ChatMessage.Author.Error -> MaterialTheme.typography.bodyMedium to MaterialTheme.colorScheme.error
                        ChatMessage.Author.Tool -> MaterialTheme.typography.bodySmall to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(message.text, style = style, color = color, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

                if (voice != VoiceState.Idle) {
                    VoicePanel(voice = voice, onStop = session::stop)
                } else {
                    Text("Готово", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 24.dp))
                }
                TextButton(onClick = onOpenChat, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Відкрити чат")
                }
            }
        }
    }
}

private const val CLOSE_DELAY_MS = 2_500L
