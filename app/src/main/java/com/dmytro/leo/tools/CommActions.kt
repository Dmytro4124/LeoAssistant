package com.dmytro.leo.tools

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.dmytro.leo.accessibility.AutoSendService

/** Calls and SMS. Tests use a fake. */
interface CommActions {
    /** Starts a call right away (ACTION_CALL). False without CALL_PHONE. */
    fun call(number: String): Boolean
    /** Sends an SMS in the background. False without SEND_SMS. */
    fun sendSms(number: String, text: String): Boolean
}

enum class AutoSendResult { Sent, NotEnabled, Failed }

/** Presses "Надіслати" in a messenger once the confirmed text is in its input field. */
fun interface AutoSender {
    suspend fun send(packageName: String, text: String): AutoSendResult
}

class AndroidCommActions(context: Context) : CommActions {

    private val appContext = context.applicationContext

    override fun call(number: String): Boolean = try {
        appContext.startActivity(
            Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: SecurityException) {
        false
    }

    @SuppressLint("MissingPermission")
    override fun sendSms(number: String, text: String): Boolean = try {
        val sms = appContext.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}

/**
 * Uses the accessibility service, if the user has turned it on. When it can't send, the text
 * goes to the clipboard so the user only has to paste it.
 */
class AccessibilityAutoSender(context: Context) : AutoSender {

    private val appContext = context.applicationContext

    override suspend fun send(packageName: String, text: String): AutoSendResult {
        val service = AutoSendService.instance
        val result = when {
            service == null -> AutoSendResult.NotEnabled
            service.pressSend(packageName, text) -> AutoSendResult.Sent
            else -> AutoSendResult.Failed
        }
        if (result != AutoSendResult.Sent) {
            appContext.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Лео", text))
        }
        return result
    }
}
