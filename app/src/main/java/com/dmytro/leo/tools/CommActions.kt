package com.dmytro.leo.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import com.dmytro.leo.accessibility.AutoSendService
import com.dmytro.leo.voice.AudioHandoff

/** Calls and SMS. Tests use a fake. */
interface CommActions {
    /** Starts a call ([speaker]: on the loudspeaker). False without CALL_PHONE. */
    fun call(number: String, speaker: Boolean = false): Boolean
    /** Sends an SMS in the background. False without SEND_SMS. */
    fun sendSms(number: String, text: String): Boolean
}

enum class AutoSendResult { Sent, NotEnabled, Failed }

/** Presses "Надіслати" in a messenger once the confirmed text is in its input field. */
fun interface AutoSender {
    suspend fun send(packageName: String, text: String): AutoSendResult
}

class AndroidCommActions(context: Context, private val audio: AudioHandoff) : CommActions {

    private val appContext = context.applicationContext

    /** During a voice session the call is placed once Leo has said "Дзвоню" and released the audio. */
    @SuppressLint("MissingPermission")
    override fun call(number: String, speaker: Boolean): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        audio.runWhenFree {
            try {
                val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, speaker) }
                appContext.getSystemService(TelecomManager::class.java)
                    .placeCall(Uri.fromParts("tel", number, null), extras)
            } catch (e: SecurityException) {
                // Permission revoked in between: nothing to do
            }
        }
        return true
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
