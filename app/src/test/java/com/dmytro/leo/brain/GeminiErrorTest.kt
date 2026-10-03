package com.dmytro.leo.brain

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiErrorTest {

    @Test
    fun `seconds plural forms`() {
        val forms = listOf(1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 45, 101, 111).associateWith { secondsUk(it) }
        assertEquals(
            mapOf(
                1 to "секунду", 2 to "секунди", 4 to "секунди", 5 to "секунд",
                11 to "секунд", 12 to "секунд", 14 to "секунд",
                21 to "секунду", 22 to "секунди", 25 to "секунд", 45 to "секунд",
                101 to "секунду", 111 to "секунд",
            ),
            forms,
        )
    }

    @Test
    fun `rate limit message`() {
        assertEquals("Забагато запитів. Спробуй через 45 секунд.", GeminiError.RateLimited(45).toUserMessage())
        assertEquals("Забагато запитів. Спробуй через 2 секунди.", GeminiError.RateLimited(2).toUserMessage())
    }
}
