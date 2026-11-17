package com.speedy.assistant

import com.speedy.assistant.voice.VoiceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceBookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val adult = floatArrayOf(1f, 0.1f, 0f)
    private val child = floatArrayOf(0f, 1f, 0.2f)

    @Test
    fun enrollMatchPersist() {
        val file = File(tmp.root, "voices.json")
        val book = VoiceBook(file)
        assertNull(book.match(adult))
        book.enroll("Тарас", adult)
        book.enroll("Ліна", child)
        val again = VoiceBook(file) // з диска
        assertEquals("Ліна", again.match(floatArrayOf(0.05f, 0.9f, 0.25f))!!.first)
        assertTrue(again.match(floatArrayOf(0.05f, 0.9f, 0.25f))!!.second >= VoiceBook.STRONG)
        assertEquals("Тарас", again.match(floatArrayOf(0.9f, 0.2f, 0f))!!.first)
    }

    @Test
    fun keepsFreshestAndRenamesForgets() {
        val book = VoiceBook(File(tmp.root, "v.json"))
        repeat(VoiceBook.MAX_SAMPLES + 3) { book.enroll("Ліна", child) }
        book.rename("Ліна", "Даринка")
        assertEquals(setOf("Даринка"), book.names)
        book.forget("Даринка")
        assertNull(book.match(child))
    }
}
