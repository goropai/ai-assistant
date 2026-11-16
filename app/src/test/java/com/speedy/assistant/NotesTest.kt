package com.speedy.assistant

import com.speedy.assistant.phone.Note
import com.speedy.assistant.phone.NoteList
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
}
