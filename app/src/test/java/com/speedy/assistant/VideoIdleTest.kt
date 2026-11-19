package com.speedy.assistant

import com.speedy.assistant.media.VideoIdle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIdleTest {
    private val pipe = "org.schabi.newpipe"

    @Test
    fun returnsAfterVideoStopsAndNobodyActs() {
        val idle = VideoIdle(30_000)
        assertFalse(idle.update(0, pipe, playing = true, userActedAt = 0))
        assertFalse(idle.update(5_000, pipe, playing = false, userActedAt = 0))
        assertFalse(idle.update(29_000, pipe, playing = false, userActedAt = 0))
        assertTrue(idle.update(30_000, pipe, playing = false, userActedAt = 0))
        // Повернулись — далі мовчимо, доки знову не заграє.
        assertFalse(idle.update(80_000, pipe, playing = false, userActedAt = 0))
    }

    @Test
    fun userActionRestartsTheWait() {
        val idle = VideoIdle(30_000)
        idle.update(0, pipe, playing = true, userActedAt = 0)
        // Шукає наступне відео (гортає список) — не закриваємо.
        assertFalse(idle.update(32_000, pipe, playing = false, userActedAt = 20_000))
        assertTrue(idle.update(51_000, pipe, playing = false, userActedAt = 20_000))
    }

    @Test
    fun nextVideoKeepsItOpen() {
        val idle = VideoIdle(30_000)
        idle.update(0, pipe, playing = true, userActedAt = 0)
        idle.update(20_000, pipe, playing = false, userActedAt = 0)
        idle.update(25_000, pipe, playing = true, userActedAt = 0) // наступне заграло
        assertFalse(idle.update(50_000, pipe, playing = false, userActedAt = 0))
    }

    @Test
    fun appsWithoutVideoAreLeftAlone() {
        val idle = VideoIdle(30_000)
        assertFalse(idle.update(0, "com.android.chrome", playing = false, userActedAt = 0))
        assertFalse(idle.update(600_000, "com.android.chrome", playing = false, userActedAt = 0))
        // Відео грало в одному застосунку, а людина перейшла в інший — той не закриваємо.
        idle.update(0, pipe, playing = true, userActedAt = 0)
        assertFalse(idle.update(100_000, "com.viber.voip", playing = false, userActedAt = 0))
    }
}
