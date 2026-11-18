package com.speedy.assistant

import com.speedy.assistant.brain.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgetTest {
    @Test
    fun forgetMatchingFacts() {
        val m = Memory(null)
        m.add("Тарас любить drum and bass")
        m.add("Тарас слухає IDM")
        m.add("Ліна любить малювати")
        val words = Memory.keywords("я люблю drum and bass")
        assertEquals(setOf("drum", "bass"), words)
        assertEquals(1, m.forget(words))
        assertEquals(listOf("Тарас слухає IDM", "Ліна любить малювати"), m.all())
        assertFalse(Memory.forgettable("Ліна любить малювати", Memory.keywords("drum and bass")))
        // Основа слова: "барабани" ~ "барабанів".
        assertTrue(Memory.forgettable("не любить барабанів", Memory.keywords("барабани")))
        assertEquals(0, m.forget(emptySet()))
    }
}
