package com.dmytro.leo.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What happened when the agent looked at or acted on the screen. */
sealed interface ScreenResult {
    data class Ok(val screen: Screen) : ScreenResult
    data object NotEnabled : ScreenResult
    data object NoWindow : ScreenResult
    /** A bank, wallet or password app is on screen: hands off. */
    data class Blocked(val app: String) : ScreenResult
    data class Failed(val message: String) : ScreenResult
}

/**
 * "Лео: керування екраном". Reads the screen of the app the user is in and performs
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

    suspend fun read(): ScreenResult = withContext(Dispatchers.Main) {
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

        val screen = ScreenFormatter.format(app, copy(root, emptyList(), 0))
        nodes = screen.elements.mapNotNull { (id, element) -> byPath[element.path]?.let { id to it } }.toMap()
        lastScreen = screen
        lastPackage = pkg
        ScreenResult.Ok(screen)
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
        val node = nodes[id] ?: return@withContext ScreenResult.Failed("Елемента [$id] немає — спершу прочитай екран ще раз")
        if (!action(node)) return@withContext ScreenResult.Failed("Не вдалося виконати дію з [$id] — екран міг змінитися, прочитай його ще раз")
        delay(SETTLE_MS)
        read()
    }

    /**
     * The app the user is in: the topmost application window that is neither Лео itself
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
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
