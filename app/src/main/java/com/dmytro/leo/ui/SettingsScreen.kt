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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
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
import com.dmytro.leo.data.MemoryFact
import com.dmytro.leo.data.MemoryStore
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
    state.calibration?.let { CalibrationDialog(it, onRetry = viewModel::startCalibration, onClose = viewModel::closeCalibration) }

    var editingFact by remember { mutableStateOf<MemoryFact?>(null) }
    var confirmForgetAll by remember { mutableStateOf(false) }
    editingFact?.let { fact ->
        FactEditor(fact, onSave = { viewModel.updateFact(fact.id, it); editingFact = null }, onDismiss = { editingFact = null })
    }
    if (confirmForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = { Text("Забути все?") },
            text = { Text("Leo забуде все, що дізнався про тебе з розмов. Розділ «Про мене» лишиться.") },
            confirmButton = { TextButton(onClick = { viewModel.forgetAll(); confirmForgetAll = false }) { Text("Забути") } },
            dismissButton = { TextButton(onClick = { confirmForgetAll = false }) { Text("Скасувати") } },
        )
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

            memorySection(
                state,
                onEnabled = viewModel::setMemoryEnabled,
                onEdit = { editingFact = it },
                onRemove = viewModel::removeFact,
                onForgetAll = { confirmForgetAll = true },
            )

            remindersSection(
                state,
                onSpeak = viewModel::setSpeakReminders,
                onRemove = viewModel::removeReminder,
                onPermissionsChanged = viewModel::permissionsChanged,
            )

            section("Пробудження голосом")
            item(key = "wake") {
                WakeSwitch(
                    state,
                    onToggle = viewModel::setWake,
                    onSensitivity = viewModel::setWakeSensitivity,
                    onCalibrate = viewModel::startCalibration,
                    onClearCalibration = viewModel::clearCalibration,
                )
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

internal fun LazyListScope.section(title: String) {
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

/** What Leo learned about the user over time, with edit, delete, "forget all" and an off switch. */
private fun LazyListScope.memorySection(
    state: SettingsUiState,
    onEnabled: (Boolean) -> Unit,
    onEdit: (MemoryFact) -> Unit,
    onRemove: (Int) -> Unit,
    onForgetAll: () -> Unit,
) {
    section("Що Leo про мене пам'ятає")
    item(key = "memory-switch") {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Запам'ятовувати з розмов", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Leo сам запам'ятовує стійке про тебе (уподобання, звички, близьких) і після розмов зводить це докупи. " +
                        "Зберігається на телефоні, але з кожним запитом іде в Gemini.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.memoryEnabled, onCheckedChange = onEnabled)
        }
    }
    if (state.memory.isEmpty()) {
        item(key = "memory-empty") { Hint("Поки нічого. Розкажи Leo про себе або скажи: «Запам'ятай, що…»") }
    }
    items(state.memory, key = { "memory-${it.id}" }) { fact ->
        ListItem(
            modifier = Modifier.clickable { onEdit(fact) },
            headlineContent = { Text(fact.text) },
            supportingContent = {
                Text(if (fact.source == MemoryFact.Source.Said) "Запам'ятав під час розмови" else "Дізнався, переглядаючи розмови")
            },
            trailingContent = {
                IconButton(onClick = { onRemove(fact.id) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Забути «${fact.text}»")
                }
            },
        )
    }
    if (state.memory.isNotEmpty()) {
        item(key = "memory-forget-all") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onForgetAll) { Text("Забути все") }
            }
        }
    }
    item(key = "divider-memory") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
}

@Composable
private fun FactEditor(fact: MemoryFact, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(fact.id) { mutableStateOf(fact.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Що Leo пам'ятає") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MemoryStore.MAX_FACT_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Порожній текст — забути цей факт") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
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
internal fun Hint(text: String) {
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
    state: SettingsUiState,
    onToggle: (Boolean) -> Unit,
    onSensitivity: (WakeSensitivity) -> Unit,
    onCalibrate: () -> Unit,
    onClearCalibration: () -> Unit,
) {
    val wake = state.wake
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
                val levels = WakeSensitivity.entries.filter { it != WakeSensitivity.Personal || state.wakeCalibrated }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    levels.forEachIndexed { i, level ->
                        SegmentedButton(
                            selected = level == state.wakeSensitivity,
                            onClick = { onSensitivity(level) },
                            shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                        ) { Text(level.label, maxLines = 1) }
                    }
                }
                Text(
                    "Висока чує здалеку, але частіше прокидається від сторонньої розмови. " +
                        "Калібрування підбере чутливість під твій голос і запам'ятає, як Leo тебе чує.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCalibrate) {
                        Text(if (state.wakeCalibrated) "Відкалібрувати ще раз" else "Відкалібрувати під мій голос")
                    }
                    if (state.wakeCalibrated) TextButton(onClick = onClearCalibration) { Text("Скинути") }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun CalibrationDialog(calibration: CalibrationUi, onRetry: () -> Unit, onClose: () -> Unit) {
    val finished = calibration is CalibrationUi.Done || calibration is CalibrationUi.Failed
    AlertDialog(
        onDismissRequest = { if (finished) onClose() },
        title = { Text("Калібрування «Привіт, Leo»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (calibration) {
                    CalibrationUi.Preparing -> {
                        Text("Говори як зазвичай, з місця, звідки кличеш Leo найчастіше. Готуюся…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is CalibrationUi.Recording -> {
                        Text(
                            "Скажи «Привіт, Leo» — ${calibration.sample} з ${calibration.total}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("Гучність", style = MaterialTheme.typography.bodySmall)
                        // Speech is around 0.05–0.3 of full scale: stretch it so the meter moves visibly
                        LinearProgressIndicator(
                            progress = { (calibration.level * 4f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                        )
                        LinearProgressIndicator(
                            progress = { calibration.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    is CalibrationUi.Analyzing -> {
                        Text("Підбираю чутливість під твій голос…")
                        LinearProgressIndicator(progress = { calibration.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is CalibrationUi.Done -> {
                        val r = calibration.result
                        Text("Готово: впізнав ${r.hits} з ${r.samples} разів, підсилення ×${formatGain(r.gain)}.")
                        if (r.hits < r.samples) {
                            Text(
                                "Не всі спроби вдалися — можна повторити, говорячи трохи чіткіше.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Як Leo тебе почув:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            r.heard.joinToString("\n") { "• ${it.ifBlank { "(тиша)" }}" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (r.learned.isNotEmpty()) {
                            Text(
                                "Запам'ятав твої варіанти: ${r.learned.joinToString(", ") { "«$it»" }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is CalibrationUi.Failed -> Text(calibration.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (calibration) {
                is CalibrationUi.Done -> TextButton(onClick = onClose) { Text("Готово") }
                is CalibrationUi.Failed -> TextButton(onClick = onRetry) { Text("Ще раз") }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(if (finished) "Закрити" else "Скасувати") }
        },
    )
}

private fun formatGain(gain: Float) = if (gain % 1f == 0f) gain.toInt().toString() else gain.toString().replace('.', ',')
