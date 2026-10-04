package com.dmytro.leo.data

import android.content.Context
import androidx.core.content.edit

/** What the user wrote about themselves in Settings: given to the agent with every request. */
interface AboutMeStore {
    fun get(): String
    fun set(text: String)

    /** For the system prompt, or "" if empty. */
    fun promptNote(): String {
        val text = get().trim()
        if (text.isEmpty()) return ""
        return "Про користувача (з його слів — враховуй, але не повторюй без потреби):\n${text.take(MAX_LENGTH)}"
    }

    companion object {
        /** Keeps the prompt (and the free-tier token budget) reasonable. */
        const val MAX_LENGTH = 1500
    }
}

class AndroidAboutMeStore(context: Context) : AboutMeStore {
    private val prefs = context.applicationContext.getSharedPreferences("about_me", Context.MODE_PRIVATE)

    override fun get(): String = prefs.getString(KEY, null).orEmpty()

    override fun set(text: String) {
        prefs.edit { putString(KEY, text.trim().take(AboutMeStore.MAX_LENGTH)) }
    }

    private companion object {
        const val KEY = "text"
    }
}
