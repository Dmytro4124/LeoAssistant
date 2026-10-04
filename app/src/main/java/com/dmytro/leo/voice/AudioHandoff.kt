package com.dmytro.leo.voice

/**
 * Lets an action that needs the phone's audio (a call) wait until a voice session has let go
 * of it. Otherwise Leo's call-mode loudspeaker routing, released when the session closes,
 * fights the phone call's own routing (the call's loudspeaker went on and right back off).
 * Pure Kotlin, unit-tested.
 */
class AudioHandoff {

    private var busy = false
    private val pending = mutableListOf<() -> Unit>()

    /** Runs [action] now, or once the voice session has released the audio. */
    @Synchronized
    fun runWhenFree(action: () -> Unit) {
        if (busy) pending += action else action()
    }

    @Synchronized
    fun acquire() {
        busy = true
    }

    fun release() {
        val actions = synchronized(this) {
            busy = false
            pending.toList().also { pending.clear() }
        }
        actions.forEach { it() }
    }
}
