package com.speedy.assistant

import com.speedy.assistant.settings.ChildLimit
import com.speedy.assistant.settings.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ChildLimitTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(day: Int, h: Int, m: Int = 0, s: Int = 0) = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, h, m, s)
    }.timeInMillis

    @Test
    fun conversationTimeAddsUpAndLimitHits() {
        var stored: String? = null
        val limit = ChildLimit({ stored }, { stored = it }, utc)
        // Розмова: репліки кожні 30 с протягом 15 хв.
        var t = at(16, 10)
        repeat(31) { limit.talk("Ліна", t); t += 30_000 }
        assertTrue(limit.exhausted("Ліна", 15, t))
        assertFalse(limit.exhausted("Ліна", 0, t)) // 0 — без обмеження
        assertFalse(limit.exhausted("Даринка", 15, t)) // у кожної дитини свій облік
        // Переживає перезапуск.
        assertTrue(ChildLimit({ stored }, { stored = it }, utc).exhausted("Ліна", 15, t))
    }

    @Test
    fun longPauseIsNotTalkTime() {
        val limit = ChildLimit({ null }, {}, utc)
        limit.talk("Ліна", at(16, 10))
        limit.talk("Ліна", at(16, 12)) // пауза 2 хв — лише 10 с, а не 2 хв
        assertEquals(0, limit.usedMinutes(at(16, 12))["Ліна"])
    }

    @Test
    fun resetAndNewDay() {
        val limit = ChildLimit({ null }, {}, utc)
        limit.add("Ліна", 20 * 60_000L, at(16, 10))
        assertTrue(limit.exhausted("Ліна", 15, at(16, 10, 1)))
        limit.reset()
        assertFalse(limit.exhausted("Ліна", 15, at(16, 10, 2)))
        limit.add("Ліна", 20 * 60_000L, at(16, 11))
        limit.reset()
        limit.reset() // скільки завгодно разів
        limit.add("Ліна", 20 * 60_000L, at(16, 12))
        assertFalse(limit.exhausted("Ліна", 15, at(17, 9))) // новий день
    }

    @Test
    fun pin() {
        val salt = Pin.newSalt()
        val h = Pin.hash("4821", salt)
        assertTrue(Pin.matches("4821", salt, h))
        assertFalse(Pin.matches("4820", salt, h))
        assertFalse(Pin.valid("12a4"))
        assertFalse(Pin.valid("123"))
    }
}
