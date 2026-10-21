package com.dmytro.leo.accessibility

/** One element of the screen, copied out of the accessibility tree (pure Kotlin, unit-tested). */
data class UiNode(
    val className: String = "",
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val password: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val children: List<UiNode> = emptyList(),
)

/** An element the model can act on, by its number on the screen description. */
data class UiElement(val id: Int, val role: String, val label: String, val password: Boolean, val path: List<Int>)

/** The screen as text for Gemini plus the numbered elements behind it. */
data class Screen(val app: String, val text: String, val elements: Map<Int, UiElement>)

/**
 * Turns the accessibility tree into a short description the model can reason about:
 *
 *     Програма: YouTube Music
 *     [1] кнопка «Назад»
 *     [2] поле «Пошук» (порожнє)
 *     текст: «Музика»
 *     [3] перемикач «Перемішування» (вимкнено)
 *
 * Only what matters is kept: interactive elements get numbers, plain texts are listed once,
 * passwords are never shown, identifiers are masked ([Redactor]), and the length is capped.
 */
object ScreenFormatter {

    fun format(app: String, root: UiNode, maxLines: Int = 120, maxText: Int = 80): Screen {
        val lines = mutableListOf("Програма: $app")
        val elements = linkedMapOf<Int, UiElement>()
        val seenTexts = mutableSetOf<String>()
        var cut = false

        fun walk(node: UiNode, path: List<Int>, insideInteractive: Boolean) {
            // Past the limit only labelled buttons are still listed: on a long feed the floating
            // ones ("Перейти вниз") come last in the tree and would otherwise get no number
            val full = lines.size >= maxLines
            if (lines.size >= maxLines + EXTRA_BUTTONS) {
                cut = true
                return
            }
            val label = labelOf(node, maxText)
            val interactive = node.enabled && (node.clickable || node.editable || node.checkable || node.scrollable)

            if (full && !(interactive && label != null && !node.scrollable)) {
                if (label != null || interactive) cut = true
            } else if (interactive) {
                val id = elements.size + 1
                val role = roleOf(node)
                // A clickable row often has no label of its own: use the texts inside it (not for lists)
                val shown = label ?: (if (node.scrollable) null else childText(node, maxText)) ?: ""
                elements[id] = UiElement(id, role, shown, node.password, path)
                lines += "[$id] $role${if (shown.isNotEmpty()) " «$shown»" else ""}${stateOf(node)}"
                if (shown.isNotEmpty()) seenTexts += shown
            } else if (label != null && !insideInteractive && seenTexts.add(label)) {
                lines += "текст: «$label»"
            }
            node.children.forEachIndexed { i, child -> walk(child, path + i, insideInteractive || (interactive && !node.scrollable)) }
        }

        walk(root, emptyList(), insideInteractive = false)
        if (cut) lines += "… (екран довший, можна прокрутити)"
        return Screen(app, lines.joinToString("\n"), elements)
    }

    /** How many more labelled buttons are listed after [format]'s maxLines. */
    private const val EXTRA_BUTTONS = 30

    private fun labelOf(node: UiNode, maxText: Int): String? {
        // Never show what is typed into a password field
        val raw = if (node.password) node.description ?: node.hint else node.text ?: node.description
        return raw?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { shorten(Redactor.redact(it), maxText) }
    }

    private fun childText(node: UiNode, maxText: Int): String? {
        val texts = mutableListOf<String>()
        fun collect(n: UiNode) {
            if (!n.password) (n.text ?: n.description)?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            n.children.forEach(::collect)
        }
        node.children.forEach(::collect)
        return texts.joinToString(" · ").takeIf { it.isNotEmpty() }?.let { shorten(Redactor.redact(it), maxText) }
    }

    private fun roleOf(node: UiNode): String {
        val cls = node.className.substringAfterLast('.')
        return when {
            node.editable -> if (node.password) "поле пароля" else "поле"
            node.checkable && (cls.contains("Switch") || cls.contains("Toggle")) -> "перемикач"
            node.checkable -> "прапорець"
            node.scrollable && !node.clickable -> "список"
            cls.contains("Button") || cls.contains("Image") -> "кнопка"
            else -> "елемент"
        }
    }

    private fun stateOf(node: UiNode): String {
        val parts = mutableListOf<String>()
        if (node.checkable) parts += if (node.checked) "увімкнено" else "вимкнено"
        if (node.selected) parts += "вибрано"
        if (node.editable && !node.password) {
            val typed = node.text?.trim().orEmpty()
            parts += if (typed.isEmpty() || typed == node.hint?.trim()) "порожнє" else "текст: «${shorten(Redactor.redact(typed), 60)}»"
        }
        if (node.scrollable) parts += "можна прокрутити"
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    private fun shorten(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"
}

/**
 * Hides identifiers before screen text goes to Gemini: phone numbers, IMEI/EID, card and
 * account numbers (7+ digits), MAC and IP addresses, the name part of e-mails.
 * Dates (03.10.2026), times and prices stay readable.
 */
object Redactor {
    private const val MASK = "•••"
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val IPV6 = Regex("""\b[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{0,4}){2,7}\b""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@(?=[A-Za-z0-9.-]+\.[A-Za-z]{2,})""")
    /** Digits with optional spaces, dashes, brackets or a leading "+" between them. */
    private val DIGIT_RUN = Regex("""\+?\d[\d\s\-()]{5,}\d""")

    fun redact(text: String): String {
        var result = MAC.replace(text, MASK)
        result = IPV4.replace(result, MASK)
        result = IPV6.replace(result) { if (it.value.count { c -> c == ':' } >= 2 && it.value.any(Char::isLetterOrDigit)) MASK else it.value }
        result = EMAIL.replace(result, "$MASK@")
        result = DIGIT_RUN.replace(result) { if (it.value.count(Char::isDigit) >= 7) MASK else it.value }
        return result
    }
}

/** Apps Leo must never look into or touch: banks, wallets, passwords, 2FA. */
object SensitiveApps {
    private val PACKAGES = setOf(
        "com.ftband.mono", "ua.privatbank.ap24", "ua.oschadbank.online", "com.ukrsibbank.client.android",
        "ua.raiffeisen.myraif", "ua.com.abank", "com.pumb.mobile", "ua.sense.mobile", "com.izibank.app",
        "com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile", "com.revolut.revolut",
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid", "com.dashlane",
        "keepass2android.keepass2android", "com.google.android.apps.authenticator2", "com.azure.authenticator",
        "com.authy.authy",
    )
    private val KEYWORDS = listOf("bank", "wallet", "password", "authenticator", "privat24", "monobank", "pay.")

    fun isSensitive(packageName: String?): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        return pkg in PACKAGES || KEYWORDS.any { it in pkg }
    }
}

/** Buttons whose action can't be undone: clicking them needs the user's "так". */
object RiskyActions {
    private val WORDS = listOf(
        "видалити", "видали", "оплатити", "сплатити", "купити", "придбати", "замовити", "підтвердити",
        "надіслати", "відправити", "переказати", "вийти", "скинути", "очистити", "підписатися", "оформити",
        "delete", "remove", "pay", "buy", "purchase", "order", "confirm", "send", "transfer", "sign out",
        "log out", "logout", "reset", "subscribe", "checkout",
    )

    fun isRisky(label: String): Boolean {
        val l = label.lowercase()
        return WORDS.any { word -> Regex("(^|[^\\p{L}])${Regex.escape(word)}").containsMatchIn(l) }
    }
}
