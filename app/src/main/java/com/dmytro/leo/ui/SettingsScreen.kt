package com.dmytro.leo.ui

import android.os.Build
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.dmytro.leo.ui.theme.BannerBar
import com.dmytro.leo.widget.LeoWidget
import com.dmytro.leo.ui.theme.LionEmblem
import com.dmytro.leo.ui.theme.SectionTitle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dmytro.leo.R
import com.dmytro.leo.accessibility.UiAgentService
import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.data.AboutMeStore
import com.dmytro.leo.messages.MessageNotificationService
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings
import com.dmytro.leo.wake.WakeSensitivity

/** Voice, everything Leo remembered (with delete), permissions, and model info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var uiAgentOn by remember { mutableStateOf(context.isAccessibilityServiceOn(UiAgentService::class.java)) }
    var messagesOn by remember { mutableStateOf(MessageNotificationService.isAllowed(context)) }
    BackHandler(onBack = onBack)
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        uiAgentOn = context.isAccessibilityServiceOn(UiAgentService::class.java)
        messagesOn = MessageNotificationService.isAllowed(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            BannerBar(
                title = "Налаштування",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SetupBanners() }

            section("Про мене")
            item(key = "about-me") { AboutMeEditor(saved = state.aboutMe, onSave = viewModel::saveAboutMe) }

            section("Пробудження голосом")
            item(key = "wake") {
                WakeSwitch(state.wake, state.wakeSensitivity, onToggle = viewModel::setWake, onSensitivity = viewModel::setWakeSensitivity)
            }

            section("Голос розмови (Gemini Live)")
            items(VoiceSettings.LIVE_VOICES, key = { "live-${it.name}" }) { voice ->
                Choice(
                    title = voice.name,
                    detail = voice.description,
                    selected = state.voiceMode == VoiceMode.Live && state.liveVoice == voice.name,
                    onClick = { viewModel.useLive(voice.name) },
                )
            }

            section("Класичний режим (запасний)")
            if (state.ttsVoices.isEmpty()) item { Hint("Шукаю українські голоси…") }
            items(state.ttsVoices, key = { "tts-${it.engine}-${it.name}" }) { voice ->
                val current = state.currentTtsVoice
                Choice(
                    title = voice.label,
                    detail = null,
                    selected = state.voiceMode == VoiceMode.Classic &&
                        current != null && current.engine == voice.engine && current.name == voice.name,
                    onClick = { viewModel.useClassic(voice) },
                )
            }

            savedSection("Місця", state.places, "Скажи: «Запам'ятай, що мій дім — …»", viewModel::removePlace)
            savedSection("Плейлисти", state.playlists, "У YouTube Music: «Поділитися» плейлистом → Leo", viewModel::removePlaylist)
            savedSection("Чати Instagram, Discord, Slack, Telegram", state.chats, "У месенджері: «Поділитися» профілем чи повідомленням → Leo", viewModel::removeChat)
            savedSection("Як ти називаєш людей", state.contactAliases, "Скажи: «Запам'ятай, що мама — це …»", viewModel::removeAlias)

            section("Керування екраном")
            item(key = "ui-agent") {
                ListItem(
                    headlineContent = { Text(if (uiAgentOn) "Leo: керування екраном — увімкнено" else "Leo: керування екраном — вимкнено") },
                    supportingContent = {
                        Text(
                            "На прохання читає екран програми й натискає в ній кнопки. Банки, гаманці й паролі — заборонені; " +
                                "незворотні дії — лише після твого «так». Прочитаний текст іде в Gemini."
                        )
                    },
                    modifier = Modifier.clickable { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }

            section("Повідомлення")
            item(key = "messages") {
                ListItem(
                    headlineContent = { Text(if (messagesOn) "Доступ до сповіщень — увімкнено" else "Доступ до сповіщень — вимкнено") },
                    supportingContent = {
                        Text(
                            "Щоб на «чи мені ніхто не писав?» Leo перевіряв нові повідомлення в Telegram, WhatsApp, Viber, " +
                                "Instagram, Discord і Slack. Читає сповіщення лише на прохання; їхній текст іде в Gemini."
                        )
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                ComponentName(context, MessageNotificationService::class.java).flattenToString(),
                            )
                        )
                    },
                )
            }

            savedSection(
                "Чати, які Leo пропускає",
                state.ignoredChats.map { if (it.detail.isEmpty()) it.copy(detail = "Месенджер") else it },
                "Боти й розсилки: скажи «це бот, не зважай на нього»",
                viewModel::removeIgnoredChat,
            )

            section("Швидкий виклик")
            item(key = "widget") {
                ListItem(
                    headlineContent = { Text("Додати віджет на головний екран") },
                    supportingContent = { Text("Почати чи зупинити розмову і перемикач «Привіт, Leo»") },
                    modifier = Modifier.clickable { context.requestLeoWidget() },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item(key = "widget-style") {
                    var systemStyle by remember { mutableStateOf(LeoWidget.isSystemStyle(context)) }
                    val toggle = { on: Boolean ->
                        systemStyle = on
                        LeoWidget.setSystemStyle(context, on)
                    }
                    ListItem(
                        headlineContent = { Text("Віджет у стилі системи") },
                        supportingContent = {
                            Text(
                                "Кольори як у тематичних іконок (Material You) замість малинового із золотом. " +
                                    "Android не дає програмам дізнатися, чи ввімкнені тематичні іконки, тож це перемикач тут."
                            )
                        },
                        trailingContent = { Switch(checked = systemStyle, onCheckedChange = toggle) },
                        modifier = Modifier.clickable { toggle(!systemStyle) },
                    )
                }
            }
            item(key = "tile") {
                ListItem(
                    headlineContent = { Text("Додати плитку у швидкі налаштування") },
                    supportingContent = { Text("Шторка → «Leo» — з будь-якого екрана") },
                    modifier = Modifier.clickable { context.requestLeoTile() },
                )
            }

            section("Про Leo")
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LionEmblem(96.dp)
                    Text("Почуй мій рик", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                }
                Hint("Текстовий чат: ${AgentConfig.MODEL}\nГолосова розмова: ${AgentConfig.LIVE_MODEL}")
            }
        }
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "section-$title") {
        SectionTitle(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 6.dp))
    }
}

private fun LazyListScope.savedSection(title: String, items: List<SavedItem>, howTo: String, onRemove: (String) -> Unit) {
    section(title)
    if (items.isEmpty()) {
        item(key = "empty-$title") { Hint("Поки нічого. $howTo") }
    }
    items(items, key = { "$title-${it.name}" }) { item ->
        ListItem(
            headlineContent = { Text(item.name) },
            supportingContent = { Text(item.detail) },
            trailingContent = {
                IconButton(onClick = { onRemove(item.name) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Видалити «${item.name}»")
                }
            },
        )
    }
    item(key = "divider-$title") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
}

@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = detail?.let { { Text(it) } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Free text about the user that goes into every request to the agent. */
@Composable
private fun AboutMeEditor(saved: String, onSave: (String) -> Unit) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= AboutMeStore.MAX_LENGTH) text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            placeholder = {
                Text("Напр.: Мене звати Дмитро, живу в Кам'янці-Подільському. Відповідай коротко, без офіціозу. Працюю програмістом.")
            },
            supportingText = { Text("${text.length} / ${AboutMeStore.MAX_LENGTH} · Leo враховує це в кожній відповіді; текст іде в Gemini.") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onSave(text) }, enabled = text.trim() != saved.trim()) { Text("Зберегти") }
        }
    }
}

@Composable
private fun WakeSwitch(
    wake: WakeUi,
    sensitivity: WakeSensitivity,
    onToggle: (Boolean) -> Unit,
    onSensitivity: (WakeSensitivity) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("«Привіт, Leo» / «Хей, Leo»", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Leo постійно слухає ключову фразу офлайн: нічого не надсилається, поки ти її не скажеш. " +
                        "Трохи витрачає батарею. Після перезавантаження телефона відкрий Leo один раз.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = wake == WakeUi.On || wake is WakeUi.Downloading,
                onCheckedChange = onToggle,
                enabled = wake !is WakeUi.Downloading,
            )
        }
        when (wake) {
            is WakeUi.Downloading -> {
                LinearProgressIndicator(progress = { wake.progress }, modifier = Modifier.fillMaxWidth())
                Text("Завантажую офлайн-модель української: ${(wake.progress * 100).toInt()}% (~78 МБ, один раз)", style = MaterialTheme.typography.bodySmall)
            }
            is WakeUi.Failed -> Text(wake.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            WakeUi.On -> {
                Text("Чутливість мікрофона", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    WakeSensitivity.entries.forEachIndexed { i, level ->
                        SegmentedButton(
                            selected = level == sensitivity,
                            onClick = { onSensitivity(level) },
                            shape = SegmentedButtonDefaults.itemShape(i, WakeSensitivity.entries.size),
                        ) { Text(level.label) }
                    }
                }
                Text(
                    "Висока чує здалеку, але частіше прокидається від сторонньої розмови.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Unit
        }
    }
}
