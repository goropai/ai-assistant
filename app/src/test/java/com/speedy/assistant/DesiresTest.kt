package com.speedy.assistant

import com.speedy.assistant.voice.Chirp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesiresTest {
    private val min = 60_000L

    @Test
    fun wantsChargeNotTooOften() {
        val d = Desires()
        assertEquals(Chirp.LOW_BATTERY, d.check(0, 12, false, 0, batteryPercent = 15, charging = false))
        assertNull(d.check(5 * min, 12, false, 0, 15, false))
        assertEquals(Chirp.LOW_BATTERY, d.check(15 * min, 12, false, 0, 15, false))
    }

    @Test
    fun noChargeWishWhileCharging() {
        assertNull(Desires().check(0, 12, false, 0, batteryPercent = 10, charging = true))
    }

    @Test
    fun wantsAttentionWhenIgnored() {
        val d = Desires()
        // Поговорили 5 хв тому — ще не нудно.
        assertNull(d.check(25 * min, 12, someoneNear = true, lastInteraction = 20 * min, batteryPercent = 80, charging = false))
        assertEquals(Chirp.ATTENTION, d.check(45 * min, 12, true, 20 * min, 80, false))
        assertNull(d.check(50 * min, 12, true, 20 * min, 80, false)) // пауза між проханнями
    }

    @Test
    fun noAttentionWhenAloneOrAtNight() {
        assertNull(Desires().check(60 * min, 12, someoneNear = false, lastInteraction = 0, batteryPercent = 80, charging = false))
        assertNull(Desires().check(60 * min, 23, someoneNear = true, lastInteraction = 0, batteryPercent = 80, charging = false))
        assertNull(Desires().check(60 * min, 6, someoneNear = true, lastInteraction = 0, batteryPercent = 80, charging = false))
    }
}
