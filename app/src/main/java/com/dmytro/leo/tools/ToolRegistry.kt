package com.dmytro.leo.tools

import com.dmytro.leo.brain.FunctionDeclaration
import com.dmytro.leo.brain.ToolDeclarations
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** All tools the agent has, in a form Gemini understands. */
class ToolRegistry(private val tools: List<Tool>) {

    private val byName = tools.associateBy { it.name }

    val declarations: List<ToolDeclarations> =
        if (tools.isEmpty()) emptyList()
        else listOf(ToolDeclarations(tools.map { FunctionDeclaration(it.name, it.description, it.parameters) }))

    /** Runs a tool by name. Never throws: problems become a [ToolResult.Failure] for Gemini to explain. */
    suspend fun execute(name: String, args: JsonObject): ToolResult {
        val tool = byName[name] ?: return ToolResult.Failure("Невідомий інструмент: $name")
        return try {
            tool.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure("Не вдалося виконати: ${e.message ?: e::class.simpleName}")
        }
    }
}
