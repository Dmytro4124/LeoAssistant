package com.dmytro.leo.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The only thing this service does: after the user has confirmed a message by voice and
 * Лео has opened the chat, it makes sure that text is in the message field and presses "Надіслати".
 *
 * Safety: it can see only Telegram, WhatsApp, Viber, Instagram and Discord (res/xml/auto_send_service.xml),
 * does nothing on its own, never touches an existing draft, and presses Send only when the
 * message field holds exactly the confirmed text.
 */
class AutoSendService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Waits up to a few seconds for the chat to open, makes sure the confirmed [text] is in the
     * message field (typing it in if the messenger ignored the link's text, e.g. when that chat
     * was already open), then presses Send. True if the message went out.
     */
    suspend fun pressSend(packageName: String, text: String): Boolean = withContext(Dispatchers.Main) {
        val wanted = text.trim()
        var typed = false
        repeat(ATTEMPTS) {
            val root = rootInActiveWindow
            if (root == null || root.packageName != packageName) Log.d(TAG, "waiting: active window ${root?.packageName}")
            if (root != null && root.packageName == packageName) {
                // The chat's message field is the lowest editable field on screen
                val input = findAll(root) { it.isEditable && it.isVisibleToUser }.lastOrNull()
                val current = input?.text?.toString()?.trim().orEmpty()
                // Messengers show "Надіслати" only when the field has text; an empty field shows
                // a mic/camera button. Telegram even reports its "Повідомлення" hint as the text,
                // so the button is the reliable sign of an empty field.
                val send = findAll(root) { it.isSendButton() }.firstOrNull()
                Log.d(TAG, "input=${input != null} textLength=${current.length} matches=${current == wanted} sendShown=${send != null} typed=$typed")
                when {
                    input == null -> Unit
                    current == wanted && send != null -> {
                        if (click(send)) {
                            // Sent = the field got cleared
                            delay(800)
                            val after = rootInActiveWindow
                            return@withContext after == null ||
                                findAll(after) { it.isEditable && it.text?.toString()?.trim() == wanted }.isEmpty()
                        }
                    }
                    // Empty field: put the confirmed text in, once
                    send == null && !typed -> {
                        typed = input.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, wanted) },
                        )
                        Log.d(TAG, "typed confirmed text: $typed")
                    }
                    // Some other draft is there: never send or overwrite it
                    send != null && current != wanted -> return@withContext false
                }
            }
            delay(DELAY_MS)
        }
        false
    }

    private fun AccessibilityNodeInfo.isSendButton(): Boolean =
        isVisibleToUser && isEnabled && (
            contentDescription?.toString()?.trim() in SEND_LABELS || viewIdResourceName in SEND_IDS ||
                // Instagram's Send is a text button
                (!isEditable && text?.toString()?.trim() in SEND_LABELS)
            )

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun findAll(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            if (predicate(node)) found += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return found
    }

    companion object {
        /** Set while the user has the service turned on in Accessibility settings. */
        @Volatile
        var instance: AutoSendService? = null
            private set

        private const val TAG = "LeoAutoSend"
        private const val ATTEMPTS = 24
        private const val DELAY_MS = 250L

        private val SEND_LABELS = setOf("Надіслати", "Відправити", "Send", "Отправить")
        private val SEND_IDS = setOf("com.whatsapp:id/send", "com.viber.voip:id/send_text")
    }
}
