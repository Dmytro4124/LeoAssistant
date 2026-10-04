package com.dmytro.leo

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.ui.ChatScreen
import com.dmytro.leo.ui.ChatViewModel
import com.dmytro.leo.ui.SettingsScreen
import com.dmytro.leo.ui.theme.LeoTheme
import com.dmytro.leo.wake.WakeWordService

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels { ChatViewModel.Factory }

    /** Two screens only, so a flag is enough (no navigation library). Set by the widget too. */
    private var showSettings by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status bar icons over the crimson banner, in light and dark mode alike
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) handleIntent(intent)
        else showSettings = savedInstanceState.getBoolean(KEY_SETTINGS)
        setContent {
            LeoTheme {
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ChatScreen(viewModel, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Android lets us start listening for "Привіт, Leo" only while our screen is visible
        WakeWordService.startIfEnabled(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTINGS, showSettings)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) showSettings = true
        handleShare(intent)
    }

    /** A link shared to Leo from another app: a playlist, an Instagram profile, a Discord message… */
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

    companion object {
        /** Opens Settings (the widget's wake switch, before the wake model is downloaded). */
        const val EXTRA_OPEN_SETTINGS = "com.dmytro.leo.OPEN_SETTINGS"
        private const val KEY_SETTINGS = "settings"
    }
}
