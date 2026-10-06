package com.dmytro.leo.data

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind

/** [dataMimeType]: the phone-book row a messenger adds to contacts it knows; null if it has none. */
enum class Messenger(val packageName: String, internal val dataMimeType: String?) {
    Telegram("org.telegram.messenger", "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile"),
    WhatsApp("com.whatsapp", "vnd.android.cursor.item/vnd.com.whatsapp.profile"),
    Viber("com.viber.voip", "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_message"),
    Instagram("com.instagram.android", null),
    Discord("com.discord", null),
    Slack("com.Slack", null),
}

data class PhoneNumber(val number: String, val label: String)

data class Contact(
    val id: Long,
    val name: String,
    val phones: List<PhoneNumber>,
    /** Messengers that registered this contact in the phone book (their sync adapters add a row). */
    val messengers: Set<Messenger> = emptySet(),
) {
    /** The number to call or text: a mobile one if there is one. */
    val mainPhone: String? get() = (phones.firstOrNull { it.label == MOBILE } ?: phones.firstOrNull())?.number

    companion object {
        const val MOBILE = "мобільний"
    }
}

/** The phone book. Throws SecurityException without READ_CONTACTS. */
fun interface ContactsSource {
    fun all(): List<Contact>
}

class AndroidContacts(context: Context) : ContactsSource {

    private val resolver = context.applicationContext.contentResolver

    override fun all(): List<Contact> {
        val mimeTypes = listOf(PhoneKind.CONTENT_ITEM_TYPE) + Messenger.entries.mapNotNull { it.dataMimeType }
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            PhoneKind.NUMBER,
            PhoneKind.TYPE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (${mimeTypes.joinToString { "?" }})"

        class Builder(val name: String) {
            val phones = mutableListOf<PhoneNumber>()
            val messengers = mutableSetOf<Messenger>()
        }
        val byId = linkedMapOf<Long, Builder>()

        resolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, mimeTypes.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val builder = byId.getOrPut(id) { Builder(name) }
                when (val mime = c.getString(2)) {
                    PhoneKind.CONTENT_ITEM_TYPE -> {
                        val number = c.getString(3) ?: continue
                        val label = if (c.getInt(4) == PhoneKind.TYPE_MOBILE) Contact.MOBILE else "інший"
                        if (builder.phones.none { PhoneNumbers.normalize(it.number) == PhoneNumbers.normalize(number) }) {
                            builder.phones += PhoneNumber(number, label)
                        }
                    }
                    else -> Messenger.entries.firstOrNull { it.dataMimeType == mime }?.let { builder.messengers += it }
                }
            }
        }
        return byId.map { (id, b) -> Contact(id, b.name, b.phones, b.messengers) }.filter { it.phones.isNotEmpty() }
    }
}

object PhoneNumbers {
    /** Digits in international form without "+": "067 123-45-67" → "380671234567". */
    fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            number.trim().startsWith("+") -> digits
            digits.length == 10 && digits.startsWith("0") -> "38$digits" // Ukrainian local format
            else -> digits
        }
    }

    /** "…4567": enough to tell numbers apart without sending the whole number to Gemini. */
    fun masked(number: String): String = "…" + number.filter { it.isDigit() }.takeLast(4)
}
