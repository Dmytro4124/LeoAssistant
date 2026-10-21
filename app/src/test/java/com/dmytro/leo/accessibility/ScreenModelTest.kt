package com.dmytro.leo.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {

    private val playlistScreen = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(className = "android.widget.ImageButton", description = "Назад", clickable = true),
            UiNode(className = "android.widget.TextView", text = "Музика"),
            UiNode(className = "android.widget.Switch", text = "Перемішування", checkable = true, clickable = true),
            UiNode(className = "android.widget.EditText", hint = "Пошук", text = "Пошук", editable = true),
            UiNode(
                className = "androidx.recyclerview.widget.RecyclerView", scrollable = true,
                children = listOf(
                    UiNode(
                        className = "android.view.ViewGroup", clickable = true,
                        children = listOf(UiNode(text = "Me Gustas Tu"), UiNode(text = "Ману Чао")),
                    ),
                ),
            ),
            UiNode(className = "android.widget.EditText", editable = true, password = true, text = "secret123", hint = "Пароль"),
        ),
    )

    @Test
    fun `screen becomes a short numbered description`() {
        val screen = ScreenFormatter.format("YouTube Music", playlistScreen)

        assertEquals(
            listOf(
                "Програма: YouTube Music",
                "[1] кнопка «Назад»",
                "текст: «Музика»",
                "[2] перемикач «Перемішування» (вимкнено)",
                "[3] поле «Пошук» (порожнє)",
                "[4] список (можна прокрутити)",
                "[5] елемент «Me Gustas Tu · Ману Чао»",
                "[6] поле пароля «Пароль»",
            ),
            screen.text.lines(),
        )
        assertEquals(listOf(4, 0), screen.elements.getValue(5).path)
    }

    @Test
    fun `password text never leaves the phone`() {
        val screen = ScreenFormatter.format("Вхід", playlistScreen)
        assertFalse(screen.text.contains("secret123"))
        assertTrue(screen.elements.getValue(6).password)
    }

    @Test
    fun `long screens are cut`() {
        val many = UiNode(children = List(300) { UiNode(text = "Рядок $it") })
        val screen = ScreenFormatter.format("Список", many, maxLines = 50)
        assertEquals(51, screen.text.lines().size)
        assertTrue(screen.text.endsWith("можна прокрутити)"))
    }

    @Test
    fun `buttons after a long feed still get numbers, long texts only in full mode`() {
        val post = "Пост: " + "дуже довгий текст ".repeat(20)
        val feed = UiNode(
            children = List(300) { UiNode(text = "Повідомлення $it") } +
                UiNode(className = "android.widget.ImageButton", description = "Перейти вниз", clickable = true) +
                UiNode(text = post),
        )
        val screen = ScreenFormatter.format("Telegram", feed, maxLines = 50)
        assertEquals("Перейти вниз", screen.elements.values.single().label)
        assertTrue(screen.text.contains("[1] кнопка «Перейти вниз»"))
        assertTrue(screen.text.endsWith("можна прокрутити)"))

        val short = UiNode(children = listOf(UiNode(text = post)))
        assertFalse(ScreenFormatter.format("Telegram", short).text.contains(post.trim()))
        assertTrue(ScreenFormatter.format("Telegram", short, maxText = 1500).text.contains(post.trim()))
    }

    @Test
    fun `identifiers are masked before they reach Gemini`() {
        assertEquals("Номер телефону · •••", Redactor.redact("Номер телефону · +380 67 123 4567"))
        assertEquals("IMEI · •••", Redactor.redact("IMEI · 357481898657425"))
        assertEquals("Картка •••", Redactor.redact("Картка 4441 1144 2233 4455"))
        assertEquals("MAC ••• IP •••", Redactor.redact("MAC 94:45:60:a6:7a:55 IP 192.168.1.4"))
        assertEquals("IPv6 •••", Redactor.redact("IPv6 fe80::c898:2cff:fef3:8ece"))
        assertEquals("•••@gmail.com", Redactor.redact("dmytro.test@gmail.com"))
        // Ordinary things stay readable
        assertEquals("03.10.2026, 23:30, 1 250 грн, версія 16", Redactor.redact("03.10.2026, 23:30, 1 250 грн, версія 16"))

        val about = UiNode(children = listOf(UiNode(clickable = true, children = listOf(UiNode(text = "IMEI"), UiNode(text = "357481898657425")))))
        assertFalse(ScreenFormatter.format("Налаштування", about).text.contains("357481898657425"))
    }

    @Test
    fun `sensitive apps and risky buttons`() {
        assertTrue(SensitiveApps.isSensitive("com.ftband.mono"))
        assertTrue(SensitiveApps.isSensitive("com.example.mybank"))
        assertFalse(SensitiveApps.isSensitive("org.telegram.messenger"))

        assertTrue(RiskyActions.isRisky("Видалити чат"))
        assertTrue(RiskyActions.isRisky("Оплатити 200 грн"))
        assertTrue(RiskyActions.isRisky("Send"))
        assertFalse(RiskyActions.isRisky("Перемішування"))
        assertFalse("'pay' inside another word is fine", RiskyActions.isRisky("Display settings"))
    }
}
