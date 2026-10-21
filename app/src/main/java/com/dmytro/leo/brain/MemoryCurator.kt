package com.dmytro.leo.brain

import com.dmytro.leo.data.MemoryFact
import com.dmytro.leo.data.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds up the long-term memory in the background, the way Claude does: the exchanges of a
 * conversation are collected, and once it's over (a voice session ended, or the text chat has
 * been quiet for [idleMs]) a separate request distills them into the memory — new lasting facts
 * are added, changed ones updated, duplicates merged.
 */
class MemoryCurator(
    private val gemini: GeminiApi,
    private val memory: MemoryStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = 3 * 60_000L,
) {
    private val lock = Any()
    private val transcript = mutableListOf<Pair<String, String>>()
    private var pending: Job? = null
    private val running = Mutex()

    /** One exchange of the conversation (typed or spoken). */
    fun noteExchange(user: String, agent: String) {
        if (!memory.enabled || user.isBlank()) return
        synchronized(lock) { transcript += user.trim() to agent.trim() }
        schedule(idleMs)
    }

    /** A voice conversation is over: distill it soon. */
    fun conversationEnded() {
        if (synchronized(lock) { transcript.isNotEmpty() }) schedule(END_DELAY_MS)
    }

    private fun schedule(delayMs: Long) {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(delayMs)
                distill()
            }
        }
    }

    /** Distills what was collected so far. Returns true when the memory changed. */
    suspend fun distill(): Boolean = running.withLock {
        val exchanges = synchronized(lock) { transcript.toList().also { transcript.clear() } }
        if (!memory.enabled || !MemoryDistillation.worthDistilling(exchanges)) return@withLock false
        val existing = memory.all()
        val request = GenerateContentRequest(
            systemInstruction = Content.system(MemoryDistillation.INSTRUCTIONS),
            contents = listOf(Content.user(MemoryDistillation.input(existing, exchanges))),
            generationConfig = GenerationConfig(temperature = 0.2, maxOutputTokens = 1024),
        )
        when (val result = gemini.generateContent(request)) {
            is GeminiResult.Success -> {
                val facts = MemoryDistillation.parse(result.text, existing) ?: return@withLock false
                memory.replaceAll(facts, now())
                true
            }
            is GeminiResult.Failure -> {
                // Not lost for good: try again with the next conversation
                synchronized(lock) { transcript.addAll(0, exchanges) }
                false
            }
        }
    }

    private companion object {
        const val END_DELAY_MS = 3_000L
    }
}

/** The distillation request and its answer. Pure Kotlin, unit-tested. */
object MemoryDistillation {

    const val NO_CHANGES = "БЕЗ ЗМІН"

    /** The last part of a long conversation is enough (and keeps the request small). */
    private const val MAX_TRANSCRIPT_CHARS = 8_000

    val INSTRUCTIONS = """
        Ти ведеш довгострокову пам'ять голосового асистента Leo про його користувача. Тобі дано поточну пам'ять
        і нову розмову. Онови пам'ять:
        - Додай нові стійкі факти про користувача з того, що він сказав: уподобання, звички й розклад, родина
          й близькі, робота, захоплення, тривалі плани й обставини.
        - Онови факти, що змінилися, і прибери ті, що розмова прямо спростувала. Об'єднай дублікати.
        - Факти, яких розмова не стосується, залиш без змін — дослівно.
        - Не додавай: разове й тимчасове (настрій, «сьогодні втомився»), жарти, самі прохання до асистента
          («попросив увімкнути музику» — не факт про людину), загальні знання, те, що сказав лише асистент.
        - Здоров'я, гроші, паролі, коди, інтимне — лише якщо користувач прямо просив це запам'ятати.
        - Кожен факт — одне коротке речення в третій особі українською, без номерів.
        - Усього не більше 1500 символів; якщо більше — залиш найважливіше.
        Якщо пам'ять змінювати не треба — відповідай рівно: $NO_CHANGES
        Інакше поверни ПОВНИЙ оновлений список фактів, по одному в рядку, кожен рядок починається з «- ».
    """.trimIndent()

    /** Too little was said to learn anything (e.g. just "Привіт, Leo" and "увімкни музику"). */
    fun worthDistilling(exchanges: List<Pair<String, String>>) = exchanges.sumOf { it.first.length } >= 40

    fun input(existing: List<MemoryFact>, exchanges: List<Pair<String, String>>): String = buildString {
        appendLine("Поточна пам'ять:")
        if (existing.isEmpty()) appendLine("(порожня)") else existing.forEach { appendLine("- ${it.text}") }
        appendLine()
        appendLine("Нова розмова:")
        val lines = exchanges.flatMap { (user, agent) ->
            listOfNotNull("Користувач: $user", agent.takeIf { it.isNotBlank() }?.let { "Leo: $it" })
        }
        append(lines.joinToString("\n").takeLast(MAX_TRANSCRIPT_CHARS))
    }

    /**
     * The new list of facts, or null to leave the memory as it is: no changes, an unreadable
     * answer, or one that would wipe out most of the memory at once (more likely a slip than intent).
     */
    fun parse(answer: String, existing: List<MemoryFact>): List<String>? {
        val text = answer.trim()
        if (text.isEmpty() || text.startsWith(NO_CHANGES)) return null
        val facts = text.lines().map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") }
            .map { it.drop(2).trim().removePrefix("[").let { s -> Regex("^\\d+]\\s*").replace(s, "") } }
            .filter { it.isNotEmpty() }
        if (facts.isEmpty()) return null
        val kept = existing.count { old -> facts.any { MemoryStore.same(it, old.text) } }
        if (existing.size >= 4 && kept < existing.size / 2 && facts.size < existing.size / 2) return null
        var length = 0
        return facts.takeWhile { fact -> (length + fact.length <= MemoryStore.MAX_PROMPT_CHARS + 300).also { length += fact.length } }
    }
}
