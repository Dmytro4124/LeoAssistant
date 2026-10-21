package com.dmytro.leo.brain

import com.dmytro.leo.data.MemoryFact
import com.dmytro.leo.data.MemoryStore
import com.dmytro.leo.tools.ForgetTool
import com.dmytro.leo.tools.RememberTool
import com.dmytro.leo.tools.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same rules as the file store, in memory. */
class FakeMemory : MemoryStore {
    override var enabled = true
    private var nextId = 1
    var facts = listOf<MemoryFact>()
    override fun all() = facts
    override fun add(text: String, source: MemoryFact.Source, now: Long): MemoryFact? {
        if (facts.any { MemoryStore.same(it.text, text) }) return null
        return MemoryFact(nextId++, MemoryStore.normalize(text), now, source).also { facts = facts + it }
    }
    override fun update(id: Int, text: String) { facts = facts.map { if (it.id == id) it.copy(text = text) else it } }
    override fun remove(ids: Collection<Int>) { facts = facts.filter { it.id !in ids } }
    override fun replaceAll(texts: List<String>, now: Long) {
        facts = texts.map { t -> facts.firstOrNull { MemoryStore.same(it.text, t) } ?: MemoryFact(nextId++, t, now, MemoryFact.Source.Learned) }
    }
    override fun clear() { facts = emptyList() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryTest {

    @Test
    fun `remember saves a fact once, forget removes it by number`() = runTest {
        val memory = FakeMemory()
        val saved = RememberTool(memory) { 1L }.execute(buildJsonObject { put("fact", "Не їсть м'яса.") }) as ToolResult.Success
        assertTrue(saved.message.startsWith("Запам'ятав: Не їсть м'яса"))
        val again = RememberTool(memory) { 2L }.execute(buildJsonObject { put("fact", "не їсть м'яса") }) as ToolResult.Success
        assertTrue(again.message.contains("вже є"))
        assertEquals(1, memory.all().size)

        assertTrue(memory.promptNote().contains("- [1] Не їсть м'яса"))

        val forgot = ForgetTool(memory).execute(buildJsonObject { put("ids", "1") }) as ToolResult.Success
        assertTrue(forgot.message.contains("Не їсть м'яса"))
        assertTrue(memory.all().isEmpty())
        assertTrue(ForgetTool(memory).execute(buildJsonObject { put("ids", "9") }) is ToolResult.Failure)
    }

    @Test
    fun `nothing is saved or shown when memory is off`() = runTest {
        val memory = FakeMemory()
        memory.add("Любить джаз", MemoryFact.Source.Said, 0)
        memory.enabled = false
        assertEquals("", memory.promptNote())
        assertTrue(RememberTool(memory).execute(buildJsonObject { put("fact", "Має кота") }) is ToolResult.Failure)
    }

    @Test
    fun `the prompt keeps the newest facts when memory is long`() {
        val memory = FakeMemory()
        repeat(20) { memory.add("Факт номер $it " + "x".repeat(100), MemoryFact.Source.Learned, it.toLong()) }
        val note = memory.promptNote()
        assertTrue(note.length < MemoryStore.MAX_PROMPT_CHARS + 300)
        assertTrue("newest", note.contains("Факт номер 19"))
        assertFalse("oldest dropped", note.contains("Факт номер 0 "))
    }

    @Test
    fun `the distilled answer becomes the new list, unless it's no change or a wipe-out`() {
        val existing = (1..4).map { MemoryFact(it, "Факт $it", 0, MemoryFact.Source.Said) }
        assertNull(MemoryDistillation.parse("БЕЗ ЗМІН", existing))
        assertNull(MemoryDistillation.parse("Не знаю", existing))
        assertEquals(
            listOf("Факт 1", "Факт 2", "Факт 3", "Факт 4", "Має доньку Соню"),
            MemoryDistillation.parse("Ось:\n- Факт 1\n- Факт 2\n- Факт 3\n- Факт 4\n- Має доньку Соню", existing),
        )
        assertNull("would drop most of the memory at once", MemoryDistillation.parse("- Новий факт", existing))
        assertFalse(MemoryDistillation.worthDistilling(listOf("Привіт, Leo!" to "Привіт!")))
    }

    @Test
    fun `a finished conversation is distilled into the memory`() = runTest {
        val memory = FakeMemory()
        memory.add("Любить джаз", MemoryFact.Source.Said, 0)
        val gemini = FakeGemini(FakeGemini.answer("- Любить джаз\n- Має доньку Соню, їй 7 років"))
        val curator = MemoryCurator(gemini, memory, backgroundScope, now = { 5L })

        curator.noteExchange("Моїй доньці Соні вчора виповнилося сім років, треба подарунок", "Вітаю! Що їй подобається?")
        curator.conversationEnded()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(listOf("Любить джаз", "Має доньку Соню, їй 7 років"), memory.all().map { it.text })
        assertEquals("the saved fact keeps its origin", MemoryFact.Source.Said, memory.all().first().source)
        val sent = gemini.requests.single().contents.single().parts.single().text!!
        assertTrue(sent.contains("- Любить джаз") && sent.contains("Користувач: Моїй доньці Соні"))
    }

    @Test
    fun `nothing is sent when memory is off`() = runTest {
        val memory = FakeMemory().apply { enabled = false }
        val gemini = FakeGemini()
        val curator = MemoryCurator(gemini, memory, backgroundScope)
        curator.noteExchange("Я працюю вчителем математики в ліцеї", "Цікаво!")
        curator.conversationEnded()
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertTrue(gemini.requests.isEmpty())
    }
}
