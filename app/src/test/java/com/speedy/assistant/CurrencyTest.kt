package com.speedy.assistant

import com.speedy.assistant.weather.Currency
import com.speedy.assistant.weather.Currency.Companion.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyTest {
    @Test
    fun todayOrTrend() {
        assertEquals(Query("EUR", null), Currency.query("який курс євро"))
        assertEquals(Query("USD", null), Currency.query("скільки коштує долар"))
        // З журналу: двічі відповідала сьогоднішнім курсом.
        assertEquals(Query("EUR", 30), Currency.query("А як змінювався курс євро до гривні на протязі місяця"))
        assertEquals(Query("EUR", 30), Currency.query("Ні я питаю як змінювався курс євро на протязі останнього місяця"))
        assertEquals(Query("USD", 7), Currency.query("як змінився курс долара за тиждень"))
        assertEquals(Query("USD", 365), Currency.query("курс долара за останній рік"))
        assertEquals(Query("EUR", 10), Currency.query("як змінювався курс євро за 10 днів"))
        assertEquals(Query("EUR", 30), Currency.query("як змінювався курс євро"))
        assertNull(Currency.query("як змінювався курс"))
        assertNull(Currency.query("яка погода"))
    }

    @Test
    fun trendText() {
        val points = listOf("20.10.2026" to 50.5311, "22.10.2026" to 50.5601, "15.11.2026" to 49.40, "20.11.2026" to 49.5300)
        val text = Currency.trend("EUR", 30, points)!!
        assertEquals(
            "Місяць тому курс євро був 50 гривень 53 копійки, на 20 листопада — 49 гривень 53 копійки: " +
                "за місяць курс знизився на 1 гривню (на 2 відсотки). " +
                "Найвищий — 22 жовтня: 50 гривень 56 копійок, найнижчий — 15 листопада: 49 гривень 40 копійок.",
            text,
        )
        assertTrue(Currency.trend("USD", 7, listOf("13.11.2026" to 45.40, "20.11.2026" to 45.401))!!.contains("майже не змінився"))
        assertNull(Currency.trend("USD", 7, listOf("20.11.2026" to 45.4)))
        assertEquals("63 копійки", Currency.accusative(0.63))
        assertEquals("2 гривні 1 копійку", Currency.accusative(2.01))
    }
}
