package com.speedy.assistant

import com.speedy.assistant.vision.Kind
import com.speedy.assistant.vision.PresenceEvent
import com.speedy.assistant.vision.PresenceTracker
import com.speedy.assistant.vision.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTrackerTest {

    private fun person(cx: Float = 0.5f, cy: Float = 0.5f, area: Float = 0.2f) = Sighting(Kind.PERSON, 0.8f, cx, cy, area)
    private val cat = Sighting(Kind.CAT, 0.7f, 0.5f, 0.8f, 0.05f)

    @Test
    fun arrivalNeedsTwoFramesAndGreetsOnce() {
        val t = PresenceTracker()
        assertTrue(t.update(0, listOf(person())).isEmpty()) // один кадр — може бути хибне спрацювання
        val second = t.update(1500, listOf(person()))
        assertTrue(PresenceEvent.Arrived(Kind.PERSON) in second)
        assertTrue(t.update(3000, listOf(person())).none { it is PresenceEvent.Arrived })
        assertEquals("людина", t.summary())
    }

    @Test
    fun flickerDoesNotCauseLeaveAndReturn() {
        val t = PresenceTracker()
        t.update(0, listOf(person()))
        t.update(1500, listOf(person()))
        // Модель пропустила кілька кадрів, але менше 10 с — людина все ще поруч.
        assertTrue(t.update(3000, emptyList()).none { it == PresenceEvent.Lost })
        assertTrue(t.update(4500, listOf(person())).none { it is PresenceEvent.Arrived })
    }

    @Test
    fun leavingAndCooldown() {
        val t = PresenceTracker(greetCooldownMs = 60_000)
        t.update(0, listOf(person()))
        t.update(1_000, listOf(person()))
        assertTrue(PresenceEvent.Lost in t.update(12_000, emptyList()))
        assertEquals("", t.summary())
        // Повернулась за 30 с — без повторного вітання.
        t.update(30_000, listOf(person()))
        assertTrue(t.update(31_000, listOf(person())).none { it is PresenceEvent.Arrived })
        // Після кулдауну — знову вітаємось.
        t.update(45_000, emptyList())
        t.update(100_000, listOf(person()))
        assertTrue(PresenceEvent.Arrived(Kind.PERSON) in t.update(101_000, listOf(person())))
    }

    @Test
    fun looksAtLargestPersonMirrored() {
        val t = PresenceTracker()
        t.update(0, listOf(person(cx = 0.2f, cy = 0.25f, area = 0.3f), cat))
        val look = t.update(1500, listOf(person(cx = 0.2f, cy = 0.25f, area = 0.3f), cat))
            .filterIsInstance<PresenceEvent.Look>().single()
        // Людина в лівій частині кадру фронтальної камери — для глядача це праворуч на екрані.
        assertEquals(0.6f, look.x, 1e-4f)
        assertEquals(-0.5f, look.y, 1e-4f)
        assertEquals("людина, кіт", t.summary())
    }
}
