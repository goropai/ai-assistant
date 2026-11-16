package com.speedy.assistant

import com.speedy.assistant.voice.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PitchTest {
    /** Голосоподібний сигнал: основний тон + гармоніки (як у мовленні). */
    private fun voice(hz: Float, ms: Int = 400): ShortArray = ShortArray(16 * ms) { i ->
        val t = i / 16000.0
        (6000 * sin(2 * PI * hz * t) + 3000 * sin(2 * PI * 2 * hz * t) + 1500 * sin(2 * PI * 3 * hz * t)).toInt().toShort()
    }

    @Test
    fun adultMaleChildAndWoman() {
        assertEquals(120f, Pitch.ofSpeech(voice(120f).toList().chunked(320).map { it.toShortArray() })!!, 6f)
        assertEquals(300f, Pitch.ofSpeech(voice(300f).toList().chunked(320).map { it.toShortArray() })!!, 12f)
        assertEquals(210f, Pitch.ofSpeech(voice(210f).toList().chunked(320).map { it.toShortArray() })!!, 10f)
    }

    @Test
    fun silenceAndNoiseAreNotVoice() {
        assertNull(Pitch.estimate(ShortArray(640)))
        val noise = java.util.Random(1).let { r -> ShortArray(640) { (r.nextGaussian() * 3000).toInt().toShort() } }
        assertNull(Pitch.estimate(noise))
    }
}
