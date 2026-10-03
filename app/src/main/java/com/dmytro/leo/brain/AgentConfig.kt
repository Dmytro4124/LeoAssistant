package com.dmytro.leo.brain

/** Single place for the agent's name and model settings. */
object AgentConfig {
    const val NAME = "Лео"

    /** Fast and with a generous free-tier limit. */
    const val MODEL = "gemini-3.5-flash-lite"

    /** How many messages (user + model) are sent to Gemini as dialog context. */
    const val MAX_HISTORY_MESSAGES = 20
}
