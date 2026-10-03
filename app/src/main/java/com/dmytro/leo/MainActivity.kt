package com.dmytro.leo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.ui.ChatScreen
import com.dmytro.leo.ui.ChatViewModel
import com.dmytro.leo.ui.SettingsScreen
import com.dmytro.leo.ui.theme.LeoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels { ChatViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            LeoTheme {
                // Two screens only, so a flag is enough (no navigation library)
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ChatScreen(viewModel, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A link shared to Лео from another app: a playlist, an Instagram profile, a Discord message… */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        val draft = when {
            "list=" in text -> "Запам'ятай плейлист: $text"
            ChatStore.chatFromLink(text) != null -> "Запам'ятай чат: $text"
            else -> text
        }
        viewModel.prefillInput(draft)
    }
}
