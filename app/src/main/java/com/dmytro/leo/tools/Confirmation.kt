package com.dmytro.leo.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Counts what the user says or types, in the text chat and in voice sessions alike. */
class UserTurns {
    private val counter = AtomicLong()
    val count: Long get() = counter.get()
    fun next() {
        counter.incrementAndGet()
    }
}

/**
 * Guards calls and messages. The first call of a guarded tool only returns a question and a
 * one-time token; the action runs when the tool is called again with that token, the *same*
 * arguments, and only after the user has said something in between (their "так").
 * So the model cannot confirm on the user's behalf, and the text can't change after "так".
 */
class ConfirmationGate(
    private val turns: UserTurns,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val key: String, val turn: Long, val createdAt: Long)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Starts a confirmation for [key] (tool name + arguments); returns the token. */
    fun ask(key: String): String {
        val token = UUID.randomUUID().toString().take(8)
        pending[token] = Pending(key, turns.count, now())
        return token
    }

    /** True once: the token exists, matches [key], is fresh, and the user has spoken since. */
    fun confirm(token: String?, key: String): Boolean {
        val request = token?.let { pending[it] } ?: return false
        val ok = request.key == key && turns.count > request.turn && now() - request.createdAt < TTL_MS
        if (ok) pending.remove(token)
        return ok
    }

    private companion object {
        const val TTL_MS = 3 * 60_000L
    }
}
