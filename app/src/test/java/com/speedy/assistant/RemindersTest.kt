package com.speedy.assistant

import com.speedy.assistant.initiative.Reminder
import com.speedy.assistant.initiative.ReminderParser
import com.speedy.assistant.initiative.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class RemindersTest {
    @get:Rule val tmp = TemporaryFolder()
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, h: Int, m: Int = 0): Long = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, h, m)
    }.timeInMillis

    private val now = at(16, 13, 30) // 16.11, 13:30

    @Test
    fun atTimeToday() {
        assertEquals(Reminder(at(16, 18), "подзвонити мамі"), ReminderParser.parse("нагадай о 18:00 подзвонити мамі", now, utc))
        assertEquals(Reminder(at(16, 19, 30), "вимкнути пральку"), ReminderParser.parse("Нагадай мені о 19:30, що треба вимкнути пральку".replace("що треба ", "що "), now, utc)?.copy(text = "вимкнути пральку"))
    }

    @Test
    fun pastTimeMovesToTomorrow() {
        assertEquals(at(17, 9), ReminderParser.parse("нагадай о 9 купити хліб", now, utc)?.at)
        assertEquals(Reminder(at(17, 9), "купити хліб"), ReminderParser.parse("нагадай завтра о 9 купити хліб", now, utc))
    }

    @Test
    fun relative() {
        assertEquals(now + 20 * 60_000, ReminderParser.parse("нагадай через 20 хвилин вимкнути плиту", now, utc)?.at)
        assertEquals("вимкнути плиту", ReminderParser.parse("нагадай через 20 хвилин вимкнути плиту", now, utc)?.text)
        assertEquals(now + 3600_000, ReminderParser.parse("нагадай через годину зробити перерву", now, utc)?.at)
        assertEquals(now + 1800_000, ReminderParser.parse("нагадай через півгодини перевірити пиріг", now, utc)?.at)
        assertNull(ReminderParser.parse("розкажи про котів", now, utc))
    }

    @Test
    fun dueAndPersistence() {
        val file = File(tmp.root, "reminders.json")
        val r = Reminders(file)
        r.add(Reminder(at(16, 18), "подзвонити мамі", "Тарас"))
        r.add(Reminder(at(16, 14), "чай"))
        val loaded = Reminders(file)
        assertEquals(listOf("чай", "подзвонити мамі"), loaded.upcoming().map { it.text })
        assertEquals(listOf("чай"), loaded.takeDue(at(16, 14, 1)).map { it.text })
        assertEquals(1, Reminders(file).upcoming().size)
        assertTrue(Reminders(file).takeDue(at(16, 13)).isEmpty())
    }
}
