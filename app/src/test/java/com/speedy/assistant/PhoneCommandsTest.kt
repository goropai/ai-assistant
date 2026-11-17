package com.speedy.assistant

import com.speedy.assistant.phone.PhoneCommand
import com.speedy.assistant.phone.PhoneCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandsTest {

    @Test
    fun parsesPhoneRequests() {
        assertEquals(PhoneCommand.OpenApp("ютуб"), PhoneCommands.parse("відкрий ютуб"))
        assertEquals(PhoneCommand.Alarm(7, 30), PhoneCommands.parse("постав будильник на 7:30"))
        assertEquals(PhoneCommand.Alarm(6, 0), PhoneCommands.parse("розбуди мене о 6"))
        assertEquals(PhoneCommand.Timer(300), PhoneCommands.parse("таймер на 5 хвилин"))
        assertEquals(PhoneCommand.Timer(30), PhoneCommands.parse("постав таймер на 30 секунд"))
        assertEquals(PhoneCommand.Timer(1800), PhoneCommands.parse("таймер на півгодини"))
        assertEquals(PhoneCommand.ContactNumber("кості"), PhoneCommands.parse("який номер у Кості?"))
        assertEquals(PhoneCommand.Call("мамі"), PhoneCommands.parse("подзвони мамі"))
        assertEquals(PhoneCommand.TodayCalendar, PhoneCommands.parse("що в мене сьогодні в календарі"))
        assertEquals(PhoneCommand.TodayCalendar, PhoneCommands.parse("які в мене плани на сьогодні"))
        assertNull(PhoneCommands.parse("розкажи про котів"))
    }

    @Test
    fun matchesAppNamesAcrossAlphabets() {
        assertTrue(PhoneCommands.appScore("ютуб", "YouTube") >= 0.7f)
        assertTrue(PhoneCommands.appScore("вайбер", "Viber") >= 0.7f)
        assertTrue(PhoneCommands.appScore("камеру", "Камера") >= 0.7f)
        assertTrue(PhoneCommands.appScore("ютуб", "Калькулятор") < 0.7f)
    }

    @Test
    fun nameStemFromDeclension() {
        assertEquals("Кост", PhoneCommands.nameStem("Кості"))
        assertEquals("мам", PhoneCommands.nameStem("мамі"))
        assertEquals("Яна", PhoneCommands.nameStem("Яна")) // коротке — як є
    }
}
