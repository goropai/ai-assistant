package com.speedy.assistant

import com.speedy.assistant.phone.Note
import com.speedy.assistant.phone.NoteList
import com.speedy.assistant.phone.NoteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private fun note(key: String, text: String, at: Long, read: Boolean = false) = Note(key, "Viber", "Мама", text, at, read)

    @Test
    fun alreadyOnShadeAtStartAreRead() {
        val list = NoteList()
        assertFalse(list.add(note("a", "стара", 1, read = true))) // висіло на шторці при запуску
        assertTrue(list.unread().isEmpty())
        assertTrue(list.add(note("b", "нова", 2)))
        assertEquals(listOf("нова"), list.unread().map { it.text })
    }

    @Test
    fun readOnceThenNotAgain() {
        val list = NoteList()
        list.add(note("b", "нова", 2))
        list.markRead(list.unread().map { it.key })
        assertTrue(list.unread().isEmpty())
        // Те саме сповіщення оновилось без змін — не нове.
        assertFalse(list.add(note("b", "нова", 3)))
        // Нове повідомлення в тому самому чаті — знову непрочитане.
        assertTrue(list.add(note("b", "ще одне", 4)))
        assertEquals(1, list.unread().size)
    }

    @Test
    fun dismissedFromShadeGone() {
        val list = NoteList()
        list.add(note("b", "нова", 2))
        list.remove("b")
        assertTrue(list.unread().isEmpty())
        assertTrue(list.recent(5).isEmpty())
    }

    private val count = { n: Int -> "У тебе $n нові повідомлення" }

    @Test
    fun personNotesNameTheSender() {
        val mom = Note("a", "Viber", "Мама", "привіт", 1)
        assertEquals("Нове повідомлення у Viber від Мама.", NoteText.announce(listOf(mom), count))
    }

    @Test
    fun appNotesSpeakTheContent() {
        // З журналу: було "Нове повідомлення у Google від Зараз: 18° · Ужгород".
        val weather = Note("w", "Google", "Зараз: 18° · Ужгород", "", 1, fromPerson = false)
        assertEquals("Нове повідомлення від Google: зараз 18°, Ужгород.", NoteText.announce(listOf(weather), count))
        val mom = Note("a", "Viber", "Мама", "привіт", 2)
        assertEquals("У тебе 2 нові повідомлення: Google — зараз 18°, Ужгород, Viber від Мама.", NoteText.announce(listOf(weather, mom), count))
        assertEquals("нагадування: купити хліб", NoteText.spoken("Нагадування: купити хліб"))
    }

    @Test
    fun messengersAreFromPeople() {
        assertTrue(NoteText.fromPerson(null, "com.viber.voip", hasMessages = false))
        assertTrue(NoteText.fromPerson("msg", "com.example.chat", hasMessages = false))
        assertTrue(NoteText.fromPerson(null, "com.example.chat", hasMessages = true))
        assertFalse(NoteText.fromPerson(null, "com.google.android.googlequicksearchbox", hasMessages = false))
    }
}
