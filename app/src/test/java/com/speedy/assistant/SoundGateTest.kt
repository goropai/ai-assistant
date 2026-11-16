package com.speedy.assistant

import com.speedy.assistant.voice.SoundGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundGateTest {

    @Test
    fun steadyNoiseNeverTriggers() {
        val gate = SoundGate()
        // Холодильник / вентилятор: рівний шум із невеликими коливаннями.
        repeat(2000) { assertFalse(gate.feed(45f + (it % 5))) }
    }

    @Test
    fun speechAboveNoiseTriggersQuickly() {
        val gate = SoundGate()
        repeat(100) { gate.feed(40f) }
        val frames = (1..10).map { gate.feed(65f) }
        // Спрацьовує на 5-му гучному кадрі (~100 мс), а не чекає довше.
        assertEquals(4, frames.indexOf(true))
    }

    @Test
    fun singleClickIsIgnored() {
        val gate = SoundGate()
        repeat(100) { gate.feed(40f) }
        assertFalse(gate.feed(80f)) // стук, клацання
        repeat(20) { assertFalse(gate.feed(40f)) }
    }

    @Test
    fun noiseFloorFollowsSlowChange() {
        val gate = SoundGate()
        repeat(100) { gate.feed(40f) }
        // Шум повільно росте (увімкнули витяжку) — поріг підлаштовується, хибних спрацювань немає.
        var level = 40f
        repeat(600) {
            level += 0.03f
            assertFalse(gate.feed(level))
        }
        // А мова над новим шумом — так само спрацьовує.
        assertTrue((1..10).any { gate.feed(level + 20f) })
    }

    @Test
    fun levelOfSilenceAndLoudSignal() {
        assertEquals(0f, SoundGate.levelDb(ShortArray(320), 320), 0.01f)
        val loud = ShortArray(320) { if (it % 2 == 0) 10000 else -10000 }
        assertEquals(80f, SoundGate.levelDb(loud, 320), 0.1f)
    }
}
