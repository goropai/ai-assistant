package com.speedy.assistant.vision

import com.speedy.assistant.LocalCommand
import com.speedy.assistant.LocalCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FacesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vec(vararg v: Float) = floatArrayOf(*v)
    private val adult = vec(1f, 0f, 0f)
    private val adultAgain = vec(0.9f, 0.1f, 0f)
    private val child = vec(0f, 1f, 0f)

    @Test
    fun rememberMatchAndPersist() {
        val file = File(tmp.root, "faces.json")
        FaceBook(file).remember("Тарас", listOf(adult))
        val book = FaceBook(file) // з диска
        assertEquals("Тарас", book.match(adultAgain)?.first)
        assertNull(book.match(child))
        assertTrue(book.forget("Тарас"))
        assertNull(FaceBook(file).match(adult))
    }

    @Test
    fun recognizedAfterTwoFramesThenCooldown() {
        val book = FaceBook(null).apply { remember("Тарас", listOf(adult)) }
        val t = FaceIdTracker(book)
        val face = FaceObservation(adultAgain, 0.1f)
        assertTrue(t.update(0, listOf(face)).isEmpty())
        assertEquals(listOf(PresenceEvent.Recognized("Тарас")), t.update(1500, listOf(face)))
        assertTrue(t.update(3000, listOf(face)).isEmpty())
        assertEquals(listOf("Тарас"), t.presentNames(3000))
    }

    @Test
    fun strangerAskedOnceAndCanBeIntroduced() {
        val people = People(FaceBook(null))
        val face = FaceObservation(child, 0.1f)
        people.update(0, listOf(face))
        assertEquals(listOf(PresenceEvent.Stranger), people.update(1500, listOf(face)))
        assertTrue(people.introduce("Валя", 2000))
        // Тепер це знайоме обличчя.
        people.update(3000, listOf(face))
        assertEquals(listOf(PresenceEvent.Recognized("Валя")), people.update(4500, listOf(face)))
    }

    @Test
    fun tinyBackgroundFaceIsNotAsked() {
        val people = People(FaceBook(null))
        val far = FaceObservation(child, 0.01f)
        people.update(0, listOf(far))
        assertTrue(people.update(1500, listOf(far)).isEmpty())
        assertTrue(!people.introduce("Хтось", 2000))
    }

    @Test
    fun namesFromPhrases() {
        assertEquals(LocalCommand.INTRODUCE, LocalCommands.match("мене звати Тарас"))
        assertEquals("Тарас", LocalCommands.parseName("Мене звати Тарас"))
        assertEquals("Тарас", LocalCommands.parseName("запам'ятай мене, я Тарас"))
        assertEquals("Валя", LocalCommands.parseName("Валя", answer = true))
        assertEquals("Валя", LocalCommands.parseName("я Валя", answer = true))
        assertNull(LocalCommands.parseName("не скажу", answer = true))
        assertNull(LocalCommands.parseName("розкажи мені щось цікаве про космос", answer = true))
        assertEquals(LocalCommand.WHO_AM_I, LocalCommands.match("Як мене звати?"))
        assertEquals(LocalCommand.FORGET_ME, LocalCommands.match("забудь мене"))
    }
}
