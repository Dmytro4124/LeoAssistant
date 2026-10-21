package com.dmytro.leo.data

import android.content.Context
import com.dmytro.leo.brain.GeminiJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/**
 * Long-term memory about the user, built up over time like Claude's: facts the agent saved
 * when the user mentioned them ([MemoryFact.Source.Said]) and facts distilled from finished
 * conversations ([MemoryFact.Source.Learned]). Kept on the phone; sent to Gemini in the prompt.
 */
@Serializable
data class MemoryFact(val id: Int, val text: String, val savedAt: Long, val source: Source) {
    enum class Source { Said, Learned }
}

interface MemoryStore {
    /** The user can turn memory off: nothing is saved or learned, and nothing goes into the prompt. */
    var enabled: Boolean

    fun all(): List<MemoryFact>

    /** Saves a fact unless the same one is already there; returns it, or null if it was a duplicate. */
    fun add(text: String, source: MemoryFact.Source, now: Long): MemoryFact?

    fun update(id: Int, text: String)

    fun remove(ids: Collection<Int>)

    /** After distillation: the new list, keeping ids and dates of facts that stayed the same. */
    fun replaceAll(texts: List<String>, now: Long)

    fun clear()

    /** For the system prompt, or "" when memory is off or empty. Newest facts first if it's too long. */
    fun promptNote(): String {
        if (!enabled) return ""
        val facts = all()
        if (facts.isEmpty()) return ""
        val kept = mutableListOf<MemoryFact>()
        var length = 0
        for (fact in facts.sortedByDescending { it.savedAt }) {
            val lineLength = fact.text.length + 8
            if (length + lineLength > MAX_PROMPT_CHARS) break
            kept += fact
            length += lineLength
        }
        return "Що ти пам'ятаєш про користувача з попередніх розмов (користуйся природно, коли це доречно; " +
            "не перелічуй і не згадуй без потреби; у дужках — номер для forget):\n" +
            kept.sortedBy { it.id }.joinToString("\n") { "- [${it.id}] ${it.text}" }
    }

    companion object {
        /** Keeps the prompt within the free-tier token budget (~500 tokens). */
        const val MAX_PROMPT_CHARS = 1500

        /** One fact is a short sentence, not a story. */
        const val MAX_FACT_CHARS = 200

        fun normalize(text: String) = text.trim().removeSuffix(".").trim().replace(Regex("\\s+"), " ")

        fun same(a: String, b: String) = normalize(a).equals(normalize(b), ignoreCase = true)
    }
}

/** In a JSON file in the app's private storage. */
class FileMemoryStore(context: Context) : MemoryStore {

    private val file = File(context.applicationContext.filesDir, "memory.json")
    private val prefs = context.applicationContext.getSharedPreferences("memory", Context.MODE_PRIVATE)

    @Serializable
    private data class Saved(val nextId: Int = 1, val facts: List<MemoryFact> = emptyList())

    private val lock = Any()
    private var saved: Saved = load()

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    override fun all(): List<MemoryFact> = synchronized(lock) { saved.facts }

    override fun add(text: String, source: MemoryFact.Source, now: Long): MemoryFact? = synchronized(lock) {
        val clean = MemoryStore.normalize(text).take(MemoryStore.MAX_FACT_CHARS)
        if (clean.isEmpty() || saved.facts.any { MemoryStore.same(it.text, clean) }) return null
        val fact = MemoryFact(saved.nextId, clean, now, source)
        write(Saved(saved.nextId + 1, saved.facts + fact))
        fact
    }

    override fun update(id: Int, text: String) = synchronized(lock) {
        val clean = MemoryStore.normalize(text).take(MemoryStore.MAX_FACT_CHARS)
        if (clean.isEmpty()) {
            write(saved.copy(facts = saved.facts.filter { it.id != id }))
        } else {
            write(saved.copy(facts = saved.facts.map { if (it.id == id) it.copy(text = clean) else it }))
        }
    }

    override fun remove(ids: Collection<Int>) = synchronized(lock) {
        write(saved.copy(facts = saved.facts.filter { it.id !in ids }))
    }

    override fun replaceAll(texts: List<String>, now: Long) = synchronized(lock) {
        var nextId = saved.nextId
        val facts = texts.map { MemoryStore.normalize(it).take(MemoryStore.MAX_FACT_CHARS) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .map { text ->
                saved.facts.firstOrNull { MemoryStore.same(it.text, text) }
                    ?: MemoryFact(nextId++, text, now, MemoryFact.Source.Learned)
            }
        write(Saved(nextId, facts))
    }

    override fun clear() = synchronized(lock) { write(Saved(saved.nextId, emptyList())) }

    private fun load(): Saved = try {
        if (file.exists()) GeminiJson.decodeFromString<Saved>(file.readText()) else Saved()
    } catch (e: Exception) {
        Saved()
    }

    private fun write(value: Saved) {
        saved = value
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(GeminiJson.encodeToString(value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // Still in memory for this run; next save tries again
        }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
