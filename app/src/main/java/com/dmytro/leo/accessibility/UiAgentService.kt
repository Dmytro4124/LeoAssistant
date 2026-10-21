package com.dmytro.leo.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** What happened when the agent looked at or acted on the screen. */
sealed interface ScreenResult {
    data class Ok(val screen: Screen) : ScreenResult
    data object NotEnabled : ScreenResult
    data object NoWindow : ScreenResult
    /** A bank, wallet or password app is on screen: hands off. */
    data class Blocked(val app: String) : ScreenResult
    data class Failed(val message: String) : ScreenResult
}

/** A screenshot for "Leo, подивись…": JPEG bytes, or why there is none. */
sealed interface Snapshot {
    class Image(val jpeg: ByteArray) : Snapshot
    data object NotEnabled : Snapshot
    data class Blocked(val app: String) : Snapshot
    data class Failed(val message: String) : Snapshot
}

/**
 * "Leo: керування екраном". Reads the screen of the app the user is in and performs
 * clicks, typing, scrolling and Back for the UI agent tools. Does nothing on its own:
 * every action comes from a tool call in a conversation the user started.
 */
class UiAgentService : AccessibilityService() {

    /** Nodes of the last screen read, by element number, so clicks hit what the model saw. */
    private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private var lastScreen: Screen? = null
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun element(id: Int): UiElement? = lastScreen?.elements?.get(id)
    val currentPackage: String? get() = lastPackage

    /** [detailed]: more and longer texts, for reading content (search results) rather than acting. */
    suspend fun read(detailed: Boolean = false): ScreenResult = withContext(Dispatchers.Main) {
        // A bank anywhere on screen blocks everything — also when a system dialog (fingerprint,
        // PIN) sits on top of it and is technically the topmost window
        sensitiveAppOnScreen()?.let { pkg ->
            nodes = emptyMap()
            lastScreen = null
            return@withContext ScreenResult.Blocked(appLabel(pkg))
        }
        val root = targetRoot() ?: return@withContext ScreenResult.NoWindow
        val pkg = root.packageName?.toString()
        val app = appLabel(pkg)

        val byPath = HashMap<List<Int>, AccessibilityNodeInfo>()
        var count = 0
        fun copy(node: AccessibilityNodeInfo, path: List<Int>, depth: Int): UiNode {
            byPath[path] = node
            count++
            val children = if (depth >= MAX_DEPTH || count >= MAX_NODES) emptyList() else
                (0 until node.childCount).mapNotNull { i -> node.getChild(i)?.let { copy(it, path + i, depth + 1) } }
            return UiNode(
                className = node.className?.toString().orEmpty(),
                text = node.text?.toString(),
                description = node.contentDescription?.toString(),
                hint = node.hintText?.toString(),
                viewId = node.viewIdResourceName,
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                password = node.isPassword,
                enabled = node.isEnabled,
                selected = node.isSelected,
                children = children,
            )
        }

        val screen = if (detailed) ScreenFormatter.format(app, copy(root, emptyList(), 0), maxLines = 250, maxText = 1500)
            else ScreenFormatter.format(app, copy(root, emptyList(), 0))
        nodes = screen.elements.mapNotNull { (id, element) -> byPath[element.path]?.let { id to it } }.toMap()
        lastScreen = screen
        lastPackage = pkg
        ScreenResult.Ok(screen)
    }

    /** The whole screen as a JPEG (longest side up to [MAX_SHOT_SIDE]), never with a bank or password app on it. */
    suspend fun screenshot(): Snapshot {
        val blocked = withContext(Dispatchers.Main) { sensitiveAppOnScreen() }
        if (blocked != null) return Snapshot.Blocked(appLabel(blocked))
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    // A hardware bitmap can't be scaled or compressed: copy it into memory first
                    val copy = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    cont.resume(copy)
                }

                override fun onFailure(errorCode: Int) = cont.resume(null)
            })
        } ?: return Snapshot.Failed("Android не дав зробити знімок екрана")
        return withContext(Dispatchers.Default) {
            val scale = MAX_SHOT_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val jpeg = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            Snapshot.Image(jpeg)
        }
    }

    suspend fun click(id: Int): ScreenResult = act(id) { node ->
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return@act current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        false
    }

    suspend fun type(id: Int, text: String): ScreenResult = act(id) { node ->
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
    }

    /** Scrolls the given list, or the first one on screen; falls back to a swipe gesture. */
    suspend fun scroll(id: Int?, forward: Boolean): ScreenResult = withContext(Dispatchers.Main) {
        val list = id?.let { nodes[it] } ?: nodes.values.firstOrNull { it.isScrollable }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (list?.performAction(action) != true) swipe(forward)
        delay(SETTLE_MS)
        read()
    }

    suspend fun back(): ScreenResult = withContext(Dispatchers.Main) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(SETTLE_MS)
        read()
    }

    private suspend fun act(id: Int, action: (AccessibilityNodeInfo) -> Boolean): ScreenResult = withContext(Dispatchers.Main) {
        sensitiveAppOnScreen()?.let { return@withContext ScreenResult.Blocked(appLabel(it)) }
        val node = nodes[id] ?: return@withContext ScreenResult.Failed(
            "Елемента [$id] немає: на останньому прочитаному екрані номери лише від 1 до ${nodes.keys.maxOrNull() ?: 0}. " +
                "Не вгадуй номер — бери його з ui_read_screen; якщо потрібної кнопки там немає, скажи про це користувачу"
        )
        if (!action(node)) return@withContext ScreenResult.Failed("Не вдалося виконати дію з [$id] — екран міг змінитися, прочитай його ще раз")
        delay(SETTLE_MS)
        read()
    }

    /**
     * The app the user is in: the topmost application window that is neither Leo itself
     * (the assistant panel may be on top) nor a system dialog.
     */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return appWindows.firstNotNullOfOrNull { window -> window.root?.takeIf { isTarget(it.packageName) } }
            ?: rootInActiveWindow?.takeIf { isTarget(it.packageName) }
    }

    private fun isTarget(pkg: CharSequence?) = pkg != null && pkg != packageName && pkg != SYSTEM_UI

    /** The package of a bank / wallet / password app with any window on screen, or null. */
    private fun sensitiveAppOnScreen(): String? =
        (windows.mapNotNull { it.root?.packageName?.toString() } + listOfNotNull(rootInActiveWindow?.packageName?.toString()))
            .firstOrNull { SensitiveApps.isSensitive(it) }

    private fun appLabel(pkg: String?): String = try {
        pkg?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        pkg ?: "?"
    }

    private fun swipe(forward: Boolean) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (from, to) = if (forward) metrics.heightPixels * 0.75f to metrics.heightPixels * 0.3f
        else metrics.heightPixels * 0.3f to metrics.heightPixels * 0.75f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
    }

    companion object {
        @Volatile
        var instance: UiAgentService? = null
            private set

        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 800
        private const val SETTLE_MS = 900L
        private const val MAX_SHOT_SIDE = 1600
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
