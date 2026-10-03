package com.dmytro.leo.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.edit

/** How the user calls people: "мама" → contact 42, "я" → own number. */
interface ContactAliases {
    fun all(): Map<String, String>
    /** [target] is "contact:<id>" or "phone:<number>". */
    fun save(alias: String, target: String)
    fun find(alias: String): String? = all()[normalize(alias)]

    companion object {
        fun normalize(alias: String) = alias.trim().lowercase()
        fun contact(id: Long) = "contact:$id"
        fun phone(number: String) = "phone:$number"
    }
}

class AndroidContactAliases(context: Context) : ContactAliases {
    private val prefs = context.applicationContext.getSharedPreferences("contact_aliases", Context.MODE_PRIVATE)

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun save(alias: String, target: String) {
        prefs.edit { putString(ContactAliases.normalize(alias), target) }
    }
}

/** The phone's own number (for "напиши собі" / Telegram Saved Messages), if the SIM reports it. */
fun interface OwnNumber {
    fun get(): String?
}

class AndroidOwnNumber(context: Context) : OwnNumber {
    private val appContext = context.applicationContext

    // READ_PHONE_NUMBERS is requested by the UI together with the other permissions
    @SuppressLint("MissingPermission", "HardwareIds")
    override fun get(): String? = try {
        val fromSubscription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.getSystemService(SubscriptionManager::class.java)
                .getPhoneNumber(SubscriptionManager.getDefaultSubscriptionId())
        } else null
        @Suppress("DEPRECATION")
        (fromSubscription?.takeIf { it.isNotBlank() } ?: appContext.getSystemService(TelephonyManager::class.java).line1Number)
            ?.takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        null
    }
}
