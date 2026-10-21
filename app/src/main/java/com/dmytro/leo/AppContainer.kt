package com.dmytro.leo

import android.content.Context
import com.dmytro.leo.assistant.ChatLog
import com.dmytro.leo.assistant.ChatMessage
import com.dmytro.leo.assistant.ServiceForegroundKeeper
import com.dmytro.leo.assistant.VoiceSession
import com.dmytro.leo.assistant.VoiceState
import com.dmytro.leo.widget.LeoWidget
import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.AgentReply
import com.dmytro.leo.brain.MemoryCurator
import com.dmytro.leo.brain.GeminiApi
import com.dmytro.leo.brain.GeminiClient
import com.dmytro.leo.brain.live.LiveClient
import com.dmytro.leo.reminders.AndroidPlaceFinder
import com.dmytro.leo.reminders.AndroidReminderAlerts
import com.dmytro.leo.reminders.AndroidReminderScheduler
import com.dmytro.leo.reminders.FileReminderStore
import com.dmytro.leo.reminders.Reminders
import com.dmytro.leo.tools.AddCalendarEventTool
import com.dmytro.leo.tools.AddTodoTool
import com.dmytro.leo.tools.AndroidCalendar
import com.dmytro.leo.tools.CalendarSource
import com.dmytro.leo.tools.DeleteCalendarEventTool
import com.dmytro.leo.tools.ListCalendarEventsTool
import com.dmytro.leo.tools.ListRemindersTool
import com.dmytro.leo.tools.RemoveRemindersTool
import com.dmytro.leo.tools.ScheduleTaskTool
import com.dmytro.leo.tools.SetReminderTool
import com.dmytro.leo.tools.UpdateReminderTool
import com.dmytro.leo.data.AboutMeStore
import com.dmytro.leo.data.AndroidAboutMeStore
import com.dmytro.leo.data.FileMemoryStore
import com.dmytro.leo.data.MemoryStore
import com.dmytro.leo.data.AndroidChatStore
import com.dmytro.leo.data.ChatHistoryStore
import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.data.ContactAliases
import com.dmytro.leo.data.ContactsSource
import com.dmytro.leo.data.FileChatHistoryStore
import com.dmytro.leo.data.AndroidContactAliases
import com.dmytro.leo.data.AndroidContacts
import com.dmytro.leo.data.AndroidInstalledApps
import com.dmytro.leo.data.AndroidOwnNumber
import com.dmytro.leo.data.AndroidPlacesStore
import com.dmytro.leo.data.AndroidIgnoredChatsStore
import com.dmytro.leo.data.AndroidPlaylistStore
import com.dmytro.leo.data.IgnoredChatsStore
import com.dmytro.leo.data.OkHttpGet
import com.dmytro.leo.data.PlacesStore
import com.dmytro.leo.data.PlaylistStore
import com.dmytro.leo.data.YouTubeMusicSearch
import com.dmytro.leo.tools.AccessibilityAutoSender
import com.dmytro.leo.tools.AccessibilityScreenAgent
import com.dmytro.leo.tools.CheckMessagesTool
import com.dmytro.leo.tools.IgnoreChatTool
import com.dmytro.leo.tools.NotificationInbox
import com.dmytro.leo.tools.AndroidCommActions
import com.dmytro.leo.voice.AudioHandoff
import com.dmytro.leo.tools.EndConversationTool
import com.dmytro.leo.tools.ForgetTool
import com.dmytro.leo.tools.RememberTool
import com.dmytro.leo.tools.GoogleSearchTool
import com.dmytro.leo.tools.LookAtScreenTool
import com.dmytro.leo.tools.PixelWeather
import com.dmytro.leo.tools.AndroidMediaActions
import com.dmytro.leo.tools.AndroidPhoneActions
import com.dmytro.leo.tools.CallTool
import com.dmytro.leo.tools.ClaudeAppTool
import com.dmytro.leo.tools.ConfirmationGate
import com.dmytro.leo.tools.FindContactTool
import com.dmytro.leo.tools.MediaActions
import com.dmytro.leo.tools.MediaControlTool
import com.dmytro.leo.tools.NavigateTool
import com.dmytro.leo.tools.OpenAppTool
import com.dmytro.leo.tools.PhoneActions
import com.dmytro.leo.tools.PlayMusicTool
import com.dmytro.leo.tools.RememberContactTool
import com.dmytro.leo.tools.SavePlaceTool
import com.dmytro.leo.tools.SaveChatTool
import com.dmytro.leo.tools.SavePlaylistTool
import com.dmytro.leo.tools.SearchPlaceTool
import com.dmytro.leo.tools.SendMessageTool
import com.dmytro.leo.tools.SendSmsTool
import com.dmytro.leo.tools.SetVolumeTool
import com.dmytro.leo.tools.ToolRegistry
import com.dmytro.leo.tools.UiBackTool
import com.dmytro.leo.tools.UiClickTool
import com.dmytro.leo.tools.UiReadScreenTool
import com.dmytro.leo.tools.UiScrollTool
import com.dmytro.leo.tools.UiStepLimiter
import com.dmytro.leo.tools.UiTypeTool
import com.dmytro.leo.tools.UserTurns
import com.dmytro.leo.tools.WeatherTool
import com.dmytro.leo.voice.AndroidAudioFocus
import com.dmytro.leo.voice.AndroidMicStream
import com.dmytro.leo.voice.AndroidPcmPlayer
import com.dmytro.leo.voice.AndroidSpeaker
import com.dmytro.leo.voice.AndroidSpeechInput
import com.dmytro.leo.voice.AndroidVoiceSettings
import com.dmytro.leo.voice.AudioFocus
import com.dmytro.leo.voice.LiveConversation
import com.dmytro.leo.voice.LiveSpeaker
import com.dmytro.leo.voice.SpeechInput
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings
import com.dmytro.leo.wake.WakeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: every shared object is created here, once per app process.
 * Screens get what they need from [LeoApp.container].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val gemini: GeminiApi = GeminiClient(
        apiKey = BuildConfig.GEMINI_API_KEY,
        model = AgentConfig.MODEL,
        httpClient = httpClient,
    )

    val places: PlacesStore = AndroidPlacesStore(appContext)
    val playlists: PlaylistStore = AndroidPlaylistStore(appContext)
    val ignoredChats: IgnoredChatsStore = AndroidIgnoredChatsStore(appContext)
    private val inbox = NotificationInbox(appContext)
    val aboutMe: AboutMeStore = AndroidAboutMeStore(appContext)
    val memory: MemoryStore = FileMemoryStore(appContext)
    private val installedApps = AndroidInstalledApps(appContext)
    private val media: MediaActions = AndroidMediaActions(appContext)
    private val phone: PhoneActions = AndroidPhoneActions(appContext)

    val contacts: ContactsSource = AndroidContacts(appContext)
    val contactAliases: ContactAliases = AndroidContactAliases(appContext)
    val chats: ChatStore = AndroidChatStore(appContext)
    private val web = OkHttpGet(httpClient)
    private val ownNumber = AndroidOwnNumber(appContext)
    /** Calls wait until a voice session has released the audio. */
    private val audioHandoff = AudioHandoff()
    private val comm = AndroidCommActions(appContext, audioHandoff)
    private val userTurns = UserTurns()
    private val confirmations = ConfirmationGate(userTurns)
    private val screenAgent = AccessibilityScreenAgent()
    private val uiSteps = UiStepLimiter(userTurns)
    private val calendar: CalendarSource = AndroidCalendar(appContext)

    /** Reminders, to-dos and scheduled tasks; the system wakes Leo for them (see ReminderReceiver). */
    val reminders: Reminders = Reminders(
        store = FileReminderStore(appContext),
        scheduler = AndroidReminderScheduler(appContext),
        alerts = AndroidReminderAlerts(
            appContext,
            speaker = { speaker },
            conversationActive = { voiceSession.isActive },
            // In Leo's conversation voice, unless the user chose the classic (TTS) mode
            leoVoice = { text -> voiceSettings.mode.value == VoiceMode.Live && reminderVoice.speak(text) },
        ),
        runTask = { task -> runScheduledTask(task) },
    )

    val tools: ToolRegistry = ToolRegistry(
        listOf(
            OpenAppTool(installedApps, phone),
            NavigateTool(places, phone),
            SearchPlaceTool(phone),
            SavePlaceTool(places),
            PlayMusicTool(YouTubeMusicSearch(httpClient), playlists, installedApps, phone),
            SavePlaylistTool(playlists),
            MediaControlTool(media),
            SetVolumeTool(media),
            FindContactTool(contacts, contactAliases),
            RememberContactTool(contactAliases),
            CallTool(contacts, contactAliases, ownNumber, confirmations, comm),
            SendSmsTool(contacts, contactAliases, ownNumber, confirmations, comm),
            SendMessageTool(contacts, contactAliases, ownNumber, chats, confirmations, phone, AccessibilityAutoSender(appContext)),
            SaveChatTool(chats),
            WeatherTool(web, PixelWeather(phone, screenAgent)),
            GoogleSearchTool(phone, screenAgent),
            ClaudeAppTool(phone, screenAgent),
            LookAtScreenTool(screenAgent, gemini),
            CheckMessagesTool(inbox, ignoredChats),
            IgnoreChatTool(inbox, ignoredChats),
            UiReadScreenTool(screenAgent, uiSteps),
            UiClickTool(screenAgent, uiSteps, confirmations),
            UiTypeTool(screenAgent, uiSteps),
            UiScrollTool(screenAgent, uiSteps),
            UiBackTool(screenAgent, uiSteps),
            RememberTool(memory),
            ForgetTool(memory),
            SetReminderTool(reminders, places, AndroidPlaceFinder(appContext)),
            AddTodoTool(reminders),
            ScheduleTaskTool(reminders, confirmations),
            ListRemindersTool(reminders),
            UpdateReminderTool(reminders),
            RemoveRemindersTool(reminders),
            AddCalendarEventTool(calendar),
            ListCalendarEventsTool(calendar),
            DeleteCalendarEventTool(calendar, confirmations),
            EndConversationTool(),
        )
    )

    val agentLoop: AgentLoop = AgentLoop(
        gemini,
        tools,
        promptNotes = {
            listOf(
                aboutMe.promptNote(), memory.promptNote(), reminders.store.promptNote(ZonedDateTime.now()),
                places.promptNote(), playlists.promptNote(), chats.promptNote(),
            ).filter { it.isNotEmpty() }.joinToString("\n\n")
        },
        userTurns = userTurns,
        onExchange = { user, agent -> memoryCurator.noteExchange(user, agent) },
    )

    val speechInput: SpeechInput = AndroidSpeechInput(appContext)
    val speaker: SpeechOutput by lazy { AndroidSpeaker(appContext) }
    val audioFocus: AudioFocus = AndroidAudioFocus(appContext)

    val voiceSettings: VoiceSettings = AndroidVoiceSettings(appContext)
    private val chatHistory: ChatHistoryStore = FileChatHistoryStore(appContext)
    private val liveConversation = LiveConversation(
        live = LiveClient(BuildConfig.GEMINI_API_KEY, httpClient),
        mic = AndroidMicStream(),
        player = AndroidPcmPlayer(appContext),
    )

    /** Says reminders in Leo's own voice: a short Live session that only speaks. */
    private val reminderVoice = LiveSpeaker(
        live = LiveClient(BuildConfig.GEMINI_API_KEY, httpClient),
        player = AndroidPcmPlayer(appContext, callMode = false),
        voiceName = { voiceSettings.liveVoice.value },
    )

    /** Lives as long as the app process: voice conversations outlive any single screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Distills finished conversations into the long-term memory. */
    val memoryCurator: MemoryCurator = MemoryCurator(gemini, memory, appScope)

    val chatLog: ChatLog = ChatLog(agentLoop, chatHistory, appScope)

    val wakeModel = WakeModel(appContext, httpClient)

    val voiceSession: VoiceSession = VoiceSession(
        agent = agentLoop,
        chatLog = chatLog,
        speechInput = speechInput,
        speaker = speaker,
        audioFocus = audioFocus,
        live = liveConversation,
        voiceSettings = voiceSettings,
        scope = appScope,
        foreground = ServiceForegroundKeeper(appContext),
        audioHandoff = audioHandoff,
    )

    init {
        // The home-screen widget shows a stop button while a conversation runs
        appScope.launch {
            voiceSession.state.map { it != VoiceState.Idle }.distinctUntilChanged().collect { LeoWidget.refresh(appContext) }
        }
        // A finished voice conversation is distilled into the long-term memory
        appScope.launch {
            voiceSession.state.map { it == VoiceState.Idle }.distinctUntilChanged().drop(1).collect { idle ->
                if (idle) memoryCurator.conversationEnded()
            }
        }
        // Alarms don't survive a reboot or an update; setting them again is harmless
        reminders.rescheduleAll()
    }

    /**
     * A scheduled task's time has come: Leo carries it out as if the user had just asked. The user
     * confirmed it when scheduling, so the tools don't ask again. Shows up in the chat like any exchange.
     */
    private suspend fun runScheduledTask(task: String): String {
        chatLog.append(ChatMessage.Author.Tool, "⏰ Заплановане завдання: $task")
        val reply = confirmations.preApproved {
            agentLoop.handle(
                "Заплановане завдання, яке я підтвердив заздалегідь — виконай його зараз сам, нічого не перепитуючи, " +
                    "і коротко скажи результат: $task"
            )
        }
        return when (reply) {
            is AgentReply.Text -> reply.text.also {
                chatLog.addActions(reply.actions)
                chatLog.append(ChatMessage.Author.Agent, it)
            }
            is AgentReply.Error -> "Не вдалося: ${reply.message}".also {
                chatLog.addActions(reply.actions)
                chatLog.append(ChatMessage.Author.Error, it)
            }
        }
    }
}
