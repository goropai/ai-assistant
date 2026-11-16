package com.speedy.assistant

import com.speedy.assistant.brain.PartialJson
import com.speedy.assistant.brain.SayStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SayStreamTest {

    @Test
    fun partialStringField() {
        assertNull(PartialJson.string("{\"mood\":\"hap", "say"))
        assertEquals("hap" to false, PartialJson.string("{\"mood\":\"hap", "mood"))
        assertEquals("happy" to true, PartialJson.string("{\"mood\": \"happy\", \"say\":", "mood"))
        assertEquals("Він сказав \"так\"" to true, PartialJson.string("{\"say\":\"Він сказав \\\"так\\\"\"}", "say"))
        assertEquals("Привіт" to false, PartialJson.string("{\"say\":\"Привіт\\", "say"))
    }

    @Test
    fun speaksSentencesAsTheyArrive() {
        val sentences = mutableListOf<String>()
        var mood: String? = null
        val s = SayStream({ mood = it }, { sentences += it })
        val json = """{"mood":"happy","say":"Привіт! Я Спіді. Як твій день?","action":{"type":"none"}}"""
        // Подаємо по 7 символів, як потік.
        val firstSentenceAt = mutableListOf<Int>()
        json.chunked(7).forEachIndexed { i, chunk ->
            s.append(chunk)
            if (sentences.size == 1 && firstSentenceAt.isEmpty()) firstSentenceAt += i
        }
        assertEquals("happy", mood)
        assertEquals(listOf("Привіт!", "Я Спіді.", "Як твій день?"), sentences)
        // Перше речення — задовго до кінця JSON.
        assertTrue(firstSentenceAt.first() < json.length / 7 / 2)
        assertTrue(s.spokeAnything)
    }

    @Test
    fun emptySaySpeaksNothing() {
        val sentences = mutableListOf<String>()
        val s = SayStream({}, { sentences += it })
        s.append("""{"mood":"thinking","say":"","action":{"type":"look","camera":"forward"}}""")
        assertTrue(sentences.isEmpty())
        assertTrue(!s.spokeAnything)
    }

    @Test
    fun longClauseSplitAtComma() {
        val long = "а".repeat(95) + ", далі ще трохи"
        assertEquals(96, SayStream.sentenceEnd(long, 0))
        assertNull(SayStream.sentenceEnd("коротко, ще", 0))
    }
}
