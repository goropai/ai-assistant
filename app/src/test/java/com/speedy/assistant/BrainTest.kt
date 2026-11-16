package com.speedy.assistant

import com.speedy.assistant.brain.Brain
import com.speedy.assistant.brain.LlmClient
import com.speedy.assistant.brain.Part
import com.speedy.assistant.brain.Turn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {

    /** Запам'ятовує, яку історію отримав кожен запит. */
    private class FakeLlm : LlmClient {
        val calls = mutableListOf<List<Turn>>()
        override suspend fun complete(system: String, history: List<Turn>): String {
            calls += history.toList()
            return """{"say":"ок","mood":"happy","action":{"type":"none"}}"""
        }
    }

    @Test
    fun historyIsAppendOnlySoCacheHolds() = runBlocking {
        val llm = FakeLlm()
        var t = 0L
        val brain = Brain(llm, now = { t })
        brain.ask("привіт")
        t += 10_000
        brain.ask("подивись", image = ByteArray(10))
        t += 10_000
        brain.ask("а тепер?")
        // Кожен наступний запит починається з точної копії попереднього — фото не підмінене текстом.
        val (first, second, third) = llm.calls
        assertEquals(first, second.take(first.size))
        assertEquals(second, third.take(second.size))
        assertTrue(third[2].parts.any { it is Part.Image })
    }

    @Test
    fun childMarkedInRequest() = runBlocking {
        val llm = FakeLlm()
        var kid = true
        val brain = Brain(llm, now = { 0L }, child = { kid })
        brain.ask("подивись мій малюнок")
        kid = false
        brain.ask("а тепер?")
        val texts = llm.calls.last().filter { it.role == com.speedy.assistant.brain.Role.USER }
            .map { (it.parts.last() as Part.Text).text }
        assertTrue(texts[0].contains("[співрозмовник — дитина]"))
        assertTrue(!texts[1].contains("дитина"))
    }

        @Test
    fun pauseStartsNewConversation() = runBlocking {
        val llm = FakeLlm()
        var t = 0L
        val brain = Brain(llm, conversationGapMs = 60_000, now = { t })
        brain.ask("раз")
        t += 61_000
        brain.ask("два")
        assertEquals(1, llm.calls.last().size)
    }

    @Test
    fun longConversationDropsOlderHalfAtOnce() = runBlocking {
        val llm = FakeLlm()
        val brain = Brain(llm, maxTurns = 8, now = { 0L })
        repeat(4) { brain.ask("питання $it") } // 8 реплік — ще в межах
        assertEquals(8, brain.size)
        brain.ask("п'яте") // 10 > 8 → лишаємо 4
        assertEquals(4, brain.size)
    }
}
