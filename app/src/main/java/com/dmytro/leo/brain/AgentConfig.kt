package com.dmytro.leo.brain

/** Single place for the agent's name and model settings. */
object AgentConfig {
    const val NAME = "Leo"

    /** Fast and with a generous free-tier limit. */
    const val MODEL = "gemini-3.5-flash-lite"

    /** Voice mode: hears and speaks by itself. Free tier: 65k tokens/min, no daily limit. */
    const val LIVE_MODEL = "gemini-3.8-live"

    /** How many messages (user + model) are sent to Gemini as dialog context. */
    const val MAX_HISTORY_MESSAGES = 20
}
