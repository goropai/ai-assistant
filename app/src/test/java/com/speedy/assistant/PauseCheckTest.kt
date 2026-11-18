package com.speedy.assistant

import com.speedy.assistant.brain.Brain
import com.speedy.assistant.initiative.PauseCheck
import com.speedy.assistant.initiative.PauseCheck.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseCheckTest {
    @Test
    fun speechGoesToModelOnlyIfItHearsAudio() {
        assertEquals(Next.ANSWER_SPEECH, PauseCheck.next(speech = true, hearsAudio = true, presentTicks = 1, timeLeft = true))
        // Claude звуку не приймає: мовлення — лише ознака присутності.
        assertEquals(Next.WAIT, PauseCheck.next(speech = true, hearsAudio = false, presentTicks = 1, timeLeft = true))
        assertEquals(Next.CONTINUE, PauseCheck.next(speech = true, hearsAudio = false, presentTicks = 2, timeLeft = true))
    }

    @Test
    fun presenceNeedsTwoChecks() {
        assertEquals(Next.WAIT, PauseCheck.next(speech = false, hearsAudio = true, presentTicks = 1, timeLeft = true))
        assertEquals(Next.CONTINUE, PauseCheck.next(speech = false, hearsAudio = true, presentTicks = 2, timeLeft = true))
    }

    @Test
    fun nobodyEndsWhenTimeIsUp() {
        assertEquals(Next.WAIT, PauseCheck.next(speech = false, hearsAudio = true, presentTicks = 0, timeLeft = true))
        assertEquals(Next.END, PauseCheck.next(speech = false, hearsAudio = true, presentTicks = 0, timeLeft = false))
        // Бачила лише раз — замало, щоб заговорити.
        assertEquals(Next.END, PauseCheck.next(speech = false, hearsAudio = true, presentTicks = 1, timeLeft = false))
    }

    @Test
    fun timingFitsAMinute() {
        // Кілька перевірок за хвилину, і запис коротший за інтервал (решту слухає розпізнавач).
        assertTrue(PauseCheck.CHECK_MS / PauseCheck.TICK_MS in 5..8)
        assertTrue(PauseCheck.RECORD_SECONDS * 1000 < PauseCheck.TICK_MS)
    }

    @Test
    fun promptsAllowSilence() {
        assertTrue(Brain.pauseFollowUp(30, audio = true).contains("say порожній"))
        assertTrue(Brain.pauseFollowUp(30, audio = false).contains("say порожній"))
    }
}
