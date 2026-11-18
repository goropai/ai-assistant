package com.speedy.assistant

import com.speedy.assistant.guard.GuardCommands
import com.speedy.assistant.guard.GuardSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardTest {
    @Test
    fun armAndDisarmByVoice() {
        listOf("вартуй", "Спіді, повартуй, будь ласка", "увімкни режим сторожа", "режим охорони", "охороняй квартиру").forEach {
            assertEquals(it, true, GuardCommands.parse(it))
        }
        listOf("досить вартувати", "Вимкни режим сторожа", "не треба охороняти", "перестань стерегти").forEach {
            assertEquals(it, false, GuardCommands.parse(it))
        }
        listOf("хто такий вартовий", "розкажи про охорону природи").forEach { assertNull(it, GuardCommands.parse(it)) }
    }

    @Test
    fun soundsAboveThreshold() {
        val scores = FloatArray(521)
        assertNull(GuardSound.of(scores, 0.35f))
        scores[437] = 0.6f // Shatter
        scores[0] = 0.4f // Speech
        assertEquals(GuardSound.GLASS, GuardSound.of(scores, 0.35f)?.first)
        scores[437] = 0f
        assertEquals(GuardSound.VOICE, GuardSound.of(scores, 0.35f)?.first)
        scores[0] = 0.2f
        assertNull(GuardSound.of(scores, 0.35f))
    }
}
