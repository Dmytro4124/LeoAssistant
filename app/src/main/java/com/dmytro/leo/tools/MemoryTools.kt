package com.dmytro.leo.tools

import com.dmytro.leo.data.MemoryFact
import com.dmytro.leo.data.MemoryStore
import kotlinx.serialization.json.JsonObject

/** "Я не їм м'ясо": a lasting fact about the user, saved when it comes up in conversation. */
class RememberTool(private val memory: MemoryStore, private val now: () -> Long = System::currentTimeMillis) : Tool {

    override val name = "remember"
    override val description =
        "Запам'ятати надовго стійкий факт про користувача, щоб враховувати його в наступних розмовах: уподобання " +
            "(«не їм м'ясо», «люблю джаз»), звички й розклад («працюю з дому по понеділках»), родина й близькі " +
            "(«доньку звати Соня»), робота, захоплення, тривалі плани. Викликай сам, коли такий факт прозвучав, " +
            "або коли просять «запам'ятай». Не запам'ятовуй разове («сьогодні втомився»), жарти, те, що вже є в пам'яті, " +
            "адреси (для них save_place) і контакти (remember_contact). Здоров'я, гроші, паролі й інтимне — лише на пряме прохання."
    override val parameters = objectSchema(
        Param("fact", "Факт одним коротким реченням у третій особі: «Не їсть м'яса», «Доньку звати Соня, їй 7 років»"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!memory.enabled) return ToolResult.Failure("Пам'ять вимкнена в налаштуваннях Leo — нічого не запам'ятовую.")
        val fact = args.string("fact") ?: return ToolResult.Failure("Не вказано, що запам'ятати")
        val saved = memory.add(fact, MemoryFact.Source.Said, now())
            ?: return ToolResult.Success("Це вже є в пам'яті. Не наголошуй на цьому.")
        return ToolResult.Success("Запам'ятав: ${saved.text}. Коротко скажи, що запам'ятав, — без повторення всього факту, якщо він довгий.")
    }
}

/** "Забудь, що я не їм м'ясо": removes facts by their numbers from the prompt. */
class ForgetTool(private val memory: MemoryStore) : Tool {

    override val name = "forget"
    override val description =
        "Забути факти про користувача з пам'яті — коли він каже «забудь, що…», «це вже неправда», або факт змінився " +
            "(тоді забудь старий і запам'ятай новий через remember). Номери фактів — у квадратних дужках у розділі пам'яті. " +
            "Щоб стерти всю пам'ять, скажи користувачу, що це робиться в налаштуваннях Leo."
    override val parameters = objectSchema(
        Param("ids", "Номери фактів через кому: «3» або «3, 7»"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val ids = args.string("ids").orEmpty().split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }.toSet()
        if (ids.isEmpty()) return ToolResult.Failure("Не вказано номерів фактів")
        val found = memory.all().filter { it.id in ids }
        if (found.isEmpty()) return ToolResult.Failure("Таких фактів у пам'яті немає")
        memory.remove(found.map { it.id })
        return ToolResult.Success("Забув: ${found.joinToString("; ") { it.text }}")
    }
}
