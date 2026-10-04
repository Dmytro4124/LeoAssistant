package com.dmytro.leo.tools

import kotlinx.serialization.json.JsonObject

/** "Бувай", "все, дякую": the voice session closes after the agent says goodbye. */
class EndConversationTool : Tool {

    override val name = "end_conversation"
    override val description =
        "Завершити голосову розмову. Викликай, лише коли користувач явно її закінчує: прощається " +
            "(«бувай», «до зв'язку»), каже «все, дякую», «більше нічого», «можеш відпочивати», «стоп». " +
            "Не викликай після звичайного «дякую» посеред розмови. Після виклику коротко попрощайся."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.Success("Розмову буде завершено після прощання", endsConversation = true)
}
