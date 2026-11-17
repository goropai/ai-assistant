package com.speedy.assistant

import com.speedy.assistant.settings.SettingCommand
import com.speedy.assistant.settings.SettingCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingCommandsTest {
    @Test
    fun vibration() {
        assertEquals(SettingCommand.Vibration("touch", false), SettingCommands.parse("Вимкни вібрацію на дотик"))
        assertEquals(SettingCommand.Vibration("cheer", true), SettingCommands.parse("увімкни вібрацію в уроках"))
        assertEquals(SettingCommand.Vibration("quiet", false), SettingCommands.parse("вимкни вібрацію для нагадувань"))
        assertEquals(SettingCommand.Vibration("find", true), SettingCommands.parse("включи вібрацію для пошуку"))
        assertEquals(SettingCommand.Vibration("all", false), SettingCommands.parse("вимкни всю вібрацію"))
        assertEquals(SettingCommand.Vibration("all", true), SettingCommands.parse("увімкни вібрацію"))
    }

    @Test
    fun otherSettings() {
        assertEquals(SettingCommand.Presence(false), SettingCommands.parse("вимкни камеру"))
        assertEquals(SettingCommand.Presence(false), SettingCommands.parse("не помічай людей"))
        assertEquals(SettingCommand.Presence(true), SettingCommands.parse("помічай людей"))
        assertEquals(SettingCommand.ChildLimit(20), SettingCommands.parse("постав ліміт дитини 20 хвилин"))
        assertEquals(SettingCommand.ChildLimit(0), SettingCommands.parse("зніми дитячий ліміт"))
        assertEquals(SettingCommand.Provider("claude"), SettingCommands.parse("перемкнись на клод"))
        assertEquals(SettingCommand.Provider("gemini"), SettingCommands.parse("перейди на джеміні"))
        assertEquals("вібрацію на дотик вимкнено", SettingCommands.describe(SettingCommand.Vibration("touch", false)))
        // Не налаштування.
        assertNull(SettingCommands.parse("увімкни фільм"))
        assertNull(SettingCommands.parse("вимкни світло"))
    }

    @Test
    fun findPhoneIsNotWhereAreWe() {
        assertEquals(LocalCommand.FIND_PHONE, LocalCommands.match("де ти?"))
        assertEquals(LocalCommand.FIND_PHONE, LocalCommands.match("де мій телефон"))
        assertEquals(LocalCommand.WHERE, LocalCommands.match("де ми"))
        assertEquals(LocalCommand.WHERE, LocalCommands.match("де ти знаходишся"))
    }
}
