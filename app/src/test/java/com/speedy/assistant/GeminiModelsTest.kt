package com.speedy.assistant

import com.speedy.assistant.brain.GeminiModels
import com.speedy.assistant.brain.GeminiModels.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelsTest {
    private val listed = listOf(
        "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash",
        "gemini-embedding-001", "gemma-3-27b-it", "gemini-2.5-flash-preview-tts", "imagen-4.0",
    )

    @Test
    fun onlyTextGeminiModelsFlashFirst() {
        val m = GeminiModels().apply { setAvailable(listed, 0) }
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-2.5-pro").sortedBy(GeminiModels::rank),
            m.available.sortedBy(GeminiModels::rank),
        )
        assertFalse("gemma-3-27b-it" in m.available)
        assertFalse("gemini-2.5-flash-preview-tts" in m.available)
    }

    @Test
    fun configuredFirstThenOthers() {
        val m = GeminiModels().apply { setAvailable(listed, 0) }
        val order = m.order("gemini-2.5-flash", now = 0)
        assertEquals("gemini-2.5-flash", order.first())
        assertEquals(4, order.size)
        assertEquals(order.distinct(), order)
    }

    @Test
    fun exhaustedModelRestsThenReturns() {
        val m = GeminiModels().apply { setAvailable(listed, 0) }
        m.failed("gemini-2.5-flash", Failure.RATE_LIMIT, now = 0)
        assertFalse("gemini-2.5-flash" in m.order("gemini-2.5-flash", now = 60_000))
        assertTrue("gemini-2.5-flash" in m.order("gemini-2.5-flash", now = 11 * 60_000))
    }

    @Test
    fun lastWorkingModelIsTriedEarly() {
        val m = GeminiModels().apply { setAvailable(listed, 0) }
        m.failed("gemini-2.5-flash", Failure.UNAVAILABLE, now = 0)
        m.succeeded("gemini-2.5-pro")
        assertEquals("gemini-2.5-pro", m.order("gemini-2.5-flash", now = 1000).first())
    }

    @Test
    fun allRestingStillTriesConfigured() {
        val m = GeminiModels()
        m.failed("gemini-2.5-flash", Failure.UNAVAILABLE, now = 0)
        assertEquals(listOf("gemini-2.5-flash"), m.order("gemini-2.5-flash", now = 1000))
    }

    @Test
    fun lastWorkingModelSurvivesRestart() {
        var stored: String? = null
        val first = GeminiModels(load = { stored }, save = { stored = it }).apply { setAvailable(listed, 0) }
        first.failed("gemini-2.5-flash", Failure.NOT_FOUND, now = 0)
        first.succeeded("gemini-2.5-flash-lite")
        val second = GeminiModels(load = { stored }, save = { stored = it }).apply { setAvailable(listed, 0) }
        val order = second.order("gemini-2.5-flash", now = 1000)
        assertEquals("gemini-2.5-flash-lite", order.first())
        assertFalse("gemini-2.5-flash" in order)
    }
}
