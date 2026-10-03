package com.dmytro.leo.ui

import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.FakeGemini
import com.dmytro.leo.brain.FakeGemini.Companion.answer
import com.dmytro.leo.brain.GeminiError
import com.dmytro.leo.brain.GeminiResult
import com.dmytro.leo.ui.ChatMessage.Author
import com.dmytro.leo.voice.AudioFocus
import com.dmytro.leo.voice.SpeakerStatus
import com.dmytro.leo.voice.SpeechError
import com.dmytro.leo.voice.SpeechEvent
import com.dmytro.leo.voice.SpeechInput
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.TtsVoice
import com.dmytro.leo.voice.FakeLiveApi
import com.dmytro.leo.voice.FakeMic
import com.dmytro.leo.voice.FakePlayer
import com.dmytro.leo.voice.FakeVoiceSettings
import com.dmytro.leo.voice.LiveConversation
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.brain.live.LiveEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    /** Each listen() plays the next scripted session; when the script ends, it is silence. */
    private class FakeSpeechInput(vararg sessions: List<SpeechEvent>) : SpeechInput {
        private val queue = ArrayDeque(sessions.toList())
        var listenCount = 0

        override fun listen(): Flow<SpeechEvent> {
            listenCount++
            val events = queue.removeFirstOrNull() ?: listOf(SpeechEvent.Error(SpeechError.NoSpeech))
            return flowOf(*events.toTypedArray())
        }
    }

    private class FakeSpeaker : SpeechOutput {
        override val status: StateFlow<SpeakerStatus> = MutableStateFlow(SpeakerStatus.Ready)
        override val voices: StateFlow<List<TtsVoice>> = MutableStateFlow(emptyList())
        override val currentVoice = MutableStateFlow<TtsVoice?>(null)
        override fun selectVoice(voice: TtsVoice) { currentVoice.value = voice }
        val spoken = mutableListOf<String>()
        /** When set, speak() hangs until it completes (or the caller is cancelled). */
        var gate: CompletableDeferred<Unit>? = null
        var wasInterrupted = false

        override suspend fun speak(text: String) {
            spoken += text
            try {
                gate?.await()
            } catch (e: CancellationException) {
                wasInterrupted = true
                throw e
            }
        }
    }

    private class FakeAudioFocus : AudioFocus {
        var held = 0
        var acquired = 0
        override fun acquire() { held++; acquired++ }
        override fun release() { held-- }
    }

    private val speaker = FakeSpeaker()
    private val focus = FakeAudioFocus()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val liveApi = FakeLiveApi()
    private val settings = FakeVoiceSettings(VoiceMode.Classic)

    private fun TestScope.viewModel(speech: SpeechInput, gemini: FakeGemini, agent: AgentLoop = AgentLoop(gemini)) =
        ChatViewModel(
            agent = agent,
            speechInput = speech,
            speaker = speaker,
            audioFocus = focus,
            live = LiveConversation(liveApi, FakeMic(), FakePlayer(), now = { testScheduler.currentTime }),
            voiceSettings = settings,
        )

    private fun ChatViewModel.transcript() = state.value.messages.map { it.author to it.text }

    @Test
    fun `voice exchange is heard, answered, spoken and shown`() = runTest {
        val speech = FakeSpeechInput(listOf(SpeechEvent.Partial("При"), SpeechEvent.Final("Привіт")))
        val vm = viewModel(speech, FakeGemini(answer("Привіт! Радий чути.")))

        vm.onMicClick()

        assertEquals(listOf(Author.User to "Привіт", Author.Agent to "Привіт! Радий чути."), vm.transcript())
        assertEquals(listOf("Привіт! Радий чути."), speaker.spoken)
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals(1, speech.listenCount)
        assertEquals("focus is released after the exchange", 0, focus.held)
    }

    @Test
    fun `a question from the agent opens the mic again`() = runTest {
        val speech = FakeSpeechInput(
            listOf(SpeechEvent.Final("Подзвони Олегу")),
            listOf(SpeechEvent.Final("Петренку")),
        )
        val vm = viewModel(speech, FakeGemini(answer("Олегу Петренку чи Коваленку?"), answer("Дзвоню Олегу Петренку.")))

        vm.onMicClick()

        assertEquals(2, speech.listenCount)
        assertEquals(listOf("Олегу Петренку чи Коваленку?", "Дзвоню Олегу Петренку."), speaker.spoken)
        assertEquals("one focus request for the whole exchange", 1, focus.acquired)
        assertEquals(0, focus.held)
    }

    @Test
    fun `silence after a follow-up question ends quietly`() = runTest {
        val speech = FakeSpeechInput(listOf(SpeechEvent.Final("Яка погода?")))
        val vm = viewModel(speech, FakeGemini(answer("В якому місті?")))

        vm.onMicClick()

        assertEquals(2, speech.listenCount)
        assertNull(vm.state.value.hint)
        assertEquals(VoiceState.Idle, vm.state.value.voice)
    }

    @Test
    fun `nothing heard at first shows a hint and does not call Gemini`() = runTest {
        val gemini = FakeGemini()
        val vm = viewModel(FakeSpeechInput(), gemini)

        vm.onMicClick()

        assertEquals("Я нічого не почув.", vm.state.value.hint)
        assertTrue(gemini.requests.isEmpty())
        assertTrue(vm.transcript().isEmpty())
    }

    @Test
    fun `agent error is spoken and does not reopen the mic`() = runTest {
        val speech = FakeSpeechInput(listOf(SpeechEvent.Final("Привіт")))
        val vm = viewModel(speech, FakeGemini(GeminiResult.Failure(GeminiError.NoNetwork)))

        vm.onMicClick()

        assertEquals(listOf("Немає з'єднання з інтернетом."), speaker.spoken)
        assertEquals(Author.Error, vm.state.value.messages.last().author)
        assertEquals(1, speech.listenCount)
    }

    @Test
    fun `tapping the button while speaking stops everything`() = runTest {
        speaker.gate = CompletableDeferred()
        val speech = FakeSpeechInput(listOf(SpeechEvent.Final("Розкажи казку")))
        val vm = viewModel(speech, FakeGemini(answer("Жили-були дід та баба.")))

        vm.onMicClick()
        assertEquals(VoiceState.Speaking("Жили-були дід та баба."), vm.state.value.voice)

        vm.onMicClick()

        assertTrue(speaker.wasInterrupted)
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals(0, focus.held)
    }

    @Test
    fun `choosing a voice selects it and plays a sample`() = runTest {
        val vm = viewModel(FakeSpeechInput(), FakeGemini())
        val anatol = TtsVoice("rhvoice", "Anatol", "RHVoice — Anatol")

        vm.previewVoice(anatol)

        assertEquals(anatol, vm.state.value.currentVoice)
        assertEquals(1, speaker.spoken.size)
        assertEquals(VoiceState.Idle, vm.state.value.voice)
    }

    @Test
    fun `live session shows growing transcripts and shares history with text mode`() = runTest {
        settings.useLive("Kore")
        val agent = AgentLoop(FakeGemini())
        val vm = viewModel(FakeSpeechInput(), FakeGemini(), agent)
        val server = liveApi.connection

        vm.onMicClick()
        server.push(LiveEvent.Ready, LiveEvent.InputTranscript("Привіт,"))
        runCurrent()
        assertEquals(listOf(Author.User to "Привіт,"), vm.transcript())

        server.push(
            LiveEvent.InputTranscript(" Лео"),
            LiveEvent.Audio(ByteArray(4)),
            LiveEvent.OutputTranscript("Привіт!"),
            LiveEvent.TurnComplete,
        )
        runCurrent()
        // The user's bubble was updated in place, not duplicated
        assertEquals(listOf(Author.User to "Привіт, Лео", Author.Agent to "Привіт!"), vm.transcript())

        vm.stopVoice()
        runCurrent()
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals(0, focus.held)
        assertEquals(listOf("Привіт, Лео", "Привіт!"), agent.history().map { it.parts.single().text })
    }

    @Test
    fun `when live is unavailable the classic mode answers instead`() = runTest {
        settings.useLive("Charon")
        liveApi.connection.serverClose(LiveEvent.Closed(GeminiError.RateLimited(null)))
        val speech = FakeSpeechInput(listOf(SpeechEvent.Final("Привіт")))
        val vm = viewModel(speech, FakeGemini(answer("Привіт!")))

        vm.onMicClick()
        runCurrent()

        assertEquals(1, speech.listenCount)
        assertEquals(listOf("Привіт!"), speaker.spoken)
        assertTrue(vm.state.value.hint!!.startsWith("Live недоступний"))
    }

    @Test
    fun `typed messages are answered without speaking`() = runTest {
        val vm = viewModel(FakeSpeechInput(), FakeGemini(answer("Привіт!")))

        vm.send("Привіт")

        assertEquals(listOf(Author.User to "Привіт", Author.Agent to "Привіт!"), vm.transcript())
        assertTrue(speaker.spoken.isEmpty())
        assertEquals(0, focus.acquired)
    }
}
