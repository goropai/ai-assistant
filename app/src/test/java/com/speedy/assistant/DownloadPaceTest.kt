package com.speedy.assistant

import com.speedy.assistant.update.DownloadPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPaceTest {
    @Test
    fun fastIsFine() {
        val pace = DownloadPace(startMs = 0)
        // 64 КБ кожні 100 мс (~640 КБ/с) — дві хвилини без зупинки.
        for (t in 100L..120_000L step 100) assertFalse(pace.add(64 * 1024, t))
        assertEquals(640L, pace.kbPerSecond(120_000))
    }

    @Test
    fun stalledIsDropped() {
        val pace = DownloadPace(startMs = 0)
        assertFalse(pace.add(64 * 1024, 1_000))
        // Далі — крапля раз на 10 с: за хвилину менше 256 КБ.
        val slow = (11_000L..61_000L step 10_000).map { pace.add(4 * 1024, it) }
        assertTrue(slow.last())
    }

    @Test
    fun logsEveryThirtySeconds() {
        val pace = DownloadPace(startMs = 0)
        assertFalse(pace.shouldLog(10_000))
        assertTrue(pace.shouldLog(30_000))
        assertFalse(pace.shouldLog(40_000))
        assertTrue(pace.shouldLog(60_000))
    }
}
