package com.dmytro.leo

import android.content.Context
import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.GeminiApi
import com.dmytro.leo.brain.GeminiClient
import com.dmytro.leo.brain.live.LiveClient
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
import com.dmytro.leo.data.AndroidPlaylistStore
import com.dmytro.leo.data.OkHttpGet
import com.dmytro.leo.data.PlacesStore
import com.dmytro.leo.data.PlaylistStore
import com.dmytro.leo.data.YouTubeMusicSearch
import com.dmytro.leo.tools.AccessibilityAutoSender
import com.dmytro.leo.tools.AndroidCommActions
import com.dmytro.leo.tools.AndroidMediaActions
import com.dmytro.leo.tools.AndroidPhoneActions
import com.dmytro.leo.tools.CallTool
import com.dmytro.leo.tools.ExchangeRateTool
import com.dmytro.leo.tools.ConfirmationGate
import com.dmytro.leo.tools.FindContactTool
import com.dmytro.leo.tools.MediaActions
import com.dmytro.leo.tools.MediaControlTool
import com.dmytro.leo.tools.NewsTool
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
import com.dmytro.leo.tools.UserTurns
import com.dmytro.leo.tools.WeatherTool
import com.dmytro.leo.tools.WikipediaTool
import com.dmytro.leo.voice.AndroidAudioFocus
import com.dmytro.leo.voice.AndroidMicStream
import com.dmytro.leo.voice.AndroidPcmPlayer
import com.dmytro.leo.voice.AndroidSpeaker
import com.dmytro.leo.voice.AndroidSpeechInput
import com.dmytro.leo.voice.AndroidVoiceSettings
import com.dmytro.leo.voice.AudioFocus
import com.dmytro.leo.voice.LiveConversation
import com.dmytro.leo.voice.SpeechInput
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.VoiceSettings
import okhttp3.OkHttpClient
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
    private val installedApps = AndroidInstalledApps(appContext)
    private val media: MediaActions = AndroidMediaActions(appContext)
    private val phone: PhoneActions = AndroidPhoneActions(appContext)

    val contacts: ContactsSource = AndroidContacts(appContext)
    val contactAliases: ContactAliases = AndroidContactAliases(appContext)
    val chats: ChatStore = AndroidChatStore(appContext)
    private val web = OkHttpGet(httpClient)
    private val ownNumber = AndroidOwnNumber(appContext)
    private val comm = AndroidCommActions(appContext)
    private val userTurns = UserTurns()
    private val confirmations = ConfirmationGate(userTurns)

    val tools = ToolRegistry(
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
            WeatherTool(web),
            ExchangeRateTool(web),
            NewsTool(web),
            WikipediaTool(web),
        )
    )

    val agentLoop = AgentLoop(
        gemini,
        tools,
        promptNotes = { listOf(places.promptNote(), playlists.promptNote(), chats.promptNote()).filter { it.isNotEmpty() }.joinToString("\n\n") },
        userTurns = userTurns,
    )

    val speechInput: SpeechInput = AndroidSpeechInput(appContext)
    val speaker: SpeechOutput by lazy { AndroidSpeaker(appContext) }
    val audioFocus: AudioFocus = AndroidAudioFocus(appContext)

    val voiceSettings: VoiceSettings = AndroidVoiceSettings(appContext)
    val chatHistory: ChatHistoryStore = FileChatHistoryStore(appContext)
    val liveConversation = LiveConversation(
        live = LiveClient(BuildConfig.GEMINI_API_KEY, httpClient),
        mic = AndroidMicStream(),
        player = AndroidPcmPlayer(appContext),
    )
}
