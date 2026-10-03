package com.dmytro.leo.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dmytro.leo.R
import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.ui.theme.LeoTheme
import com.dmytro.leo.voice.AndroidSpeaker
import com.dmytro.leo.voice.SpeakerStatus
import com.dmytro.leo.voice.TtsVoice
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings

/** Main screen: chat history, text input (debug mode) and the mic button for voice. */
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onMicClick() else viewModel.onMicPermissionDenied()
    }

    ChatContent(
        state = state,
        onSend = viewModel::send,
        onClear = viewModel::clear,
        onMic = {
            if (context.hasMicPermission()) viewModel.onMicClick()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStopVoice = viewModel::stopVoice,
        onInstallVoice = { context.openTtsVoiceInstall() },
        onSelectVoice = viewModel::previewVoice,
        onSelectLiveVoice = viewModel::selectLiveVoice,
        onPrefillConsumed = viewModel::prefillConsumed,
        banners = { SetupBanners() },
    )
}

private fun Context.hasMicPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Opens the Google TTS screen where voice data (the Ukrainian voice) can be downloaded. */
private fun Context.openTtsVoiceInstall() {
    val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(AndroidSpeaker.GOOGLE_TTS)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onMic: () -> Unit,
    onStopVoice: () -> Unit,
    onInstallVoice: () -> Unit,
    onSelectVoice: (TtsVoice) -> Unit,
    onSelectLiveVoice: (String) -> Unit,
    onPrefillConsumed: () -> Unit,
    banners: @Composable () -> Unit = {},
) {
    var input by rememberSaveable { mutableStateOf("") }

    // A link shared from another app lands in the input field, ready to edit and send
    LaunchedEffect(state.prefill) {
        state.prefill?.let {
            input = it
            onPrefillConsumed()
        }
    }
    val listState = rememberLazyListState()

    // Keep the newest message in view
    LaunchedEffect(state.messages.size, state.isThinking) {
        val count = state.messages.size + if (state.isThinking) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit() {
        if (input.isNotBlank() && !state.isBusy) {
            onSend(input)
            input = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AgentConfig.NAME) },
                actions = {
                    VoiceMenu(
                        state = state,
                        enabled = !state.isBusy,
                        onSelectLive = onSelectLiveVoice,
                        onSelectTts = onSelectVoice,
                    )
                    TextButton(onClick = onClear, enabled = state.messages.isNotEmpty() && !state.isBusy) {
                        Text("Очистити")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                banners()
                when (state.speakerStatus) {
                    SpeakerStatus.MissingUkrainianVoice -> SpeakerBanner(
                        text = "Український голос для озвучення не встановлено.",
                        action = "Встановити",
                        onAction = onInstallVoice,
                    )
                    SpeakerStatus.Failed -> SpeakerBanner(
                        text = "Синтез мови недоступний: відповіді будуть лише текстом.",
                        action = null,
                        onAction = {},
                    )
                    else -> Unit
                }
                state.hint?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                if (state.voice != VoiceState.Idle) {
                    VoicePanel(voice = state.voice, onStop = onStopVoice)
                } else {
                    InputBar(
                        input = input,
                        onInputChange = { input = it },
                        enabled = !state.isThinking,
                        onSubmit = ::submit,
                        onMic = onMic,
                    )
                }
            }
        },
    ) { innerPadding ->
        if (state.messages.isEmpty() && !state.isThinking) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Натисни мікрофон і скажи, наприклад:\n«Привіт, що ти вмієш?»",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                if (state.isThinking) {
                    item(key = "thinking") {
                        MessageBubble(ChatMessage(-1, ChatMessage.Author.Agent, "${AgentConfig.NAME} думає…"))
                    }
                }
            }
        }
    }
}

/** Text field + one round button: mic when the field is empty, send when there is text. */
@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
    onMic: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Повідомлення…") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        )
        if (input.isBlank()) {
            FilledIconButton(onClick = onMic, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Говорити")
            }
        } else {
            FilledIconButton(onClick = onSubmit, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = "Надіслати")
            }
        }
    }
}

/**
 * Voice picker in the top bar. A Live voice switches to the Live mode; a TTS voice
 * switches to the classic mode and plays a sample.
 */
@Composable
private fun VoiceMenu(
    state: ChatUiState,
    enabled: Boolean,
    onSelectLive: (String) -> Unit,
    onSelectTts: (TtsVoice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_voice), contentDescription = "Голос")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuHeader("Розмова (Gemini Live)")
            VoiceSettings.LIVE_VOICES.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("${voice.name} — ${voice.description}") },
                    leadingIcon = {
                        RadioButton(selected = state.voiceMode == VoiceMode.Live && state.liveVoice == voice.name, onClick = null)
                    },
                    onClick = {
                        expanded = false
                        onSelectLive(voice.name)
                    },
                )
            }

            HorizontalDivider()
            MenuHeader("Класичний режим")
            if (state.voices.isEmpty()) {
                DropdownMenuItem(text = { Text("Шукаю українські голоси…") }, onClick = {}, enabled = false)
            }
            val current = state.currentVoice
            state.voices.forEach { voice ->
                val selected = state.voiceMode == VoiceMode.Classic &&
                    current != null && voice.engine == current.engine && voice.name == current.name
                DropdownMenuItem(
                    text = { Text(voice.label) },
                    leadingIcon = { RadioButton(selected = selected, onClick = null) },
                    onClick = {
                        expanded = false
                        onSelectTts(voice)
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SpeakerBanner(text: String, action: String?, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.author == ChatMessage.Author.Tool) {
        // Service line: small and grey, not a bubble
        Text(
            message.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val isUser = message.author == ChatMessage.Author.User
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (message.author) {
        ChatMessage.Author.User -> colors.primaryContainer to colors.onPrimaryContainer
        ChatMessage.Author.Error -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceContainerHigh to colors.onSurface
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = background,
            contentColor = foreground,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            // Selectable, so replies can be copied while debugging
            SelectionContainer {
                Text(message.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    LeoTheme(dynamicColor = false) {
        ChatContent(
            state = ChatUiState(
                messages = listOf(
                    ChatMessage(0, ChatMessage.Author.User, "Привіт, що ти вмієш?"),
                    ChatMessage(1, ChatMessage.Author.Agent, "Привіт! Я Лео. Поки що відповідаю на запитання, а скоро навчуся керувати телефоном."),
                ),
                voice = VoiceState.Listening(partial = "яка завтра погода", level = 5f),
                speakerStatus = SpeakerStatus.MissingUkrainianVoice,
            ),
            onSend = {}, onClear = {}, onMic = {}, onStopVoice = {}, onInstallVoice = {}, onSelectVoice = {}, onSelectLiveVoice = {}, onPrefillConsumed = {},
        )
    }
}
