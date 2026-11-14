package com.speedy.assistant

import com.speedy.assistant.voice.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {

    @Test
    fun wakeWordExtractsCommand() {
        assertEquals("перевір оновлення", WakeWord.extractCommand(listOf("Спіді, перевір оновлення")))
        assertEquals("що ти бачиш", WakeWord.extractCommand(listOf("спиди що ти бачиш")))
        assertEquals("", WakeWord.extractCommand(listOf("Спіді!")))
        assertEquals("онови застосунок", WakeWord.extractCommand(listOf("привіт", "Гей, Спіді, онови застосунок")))
        assertNull(WakeWord.extractCommand(listOf("спідометр показує сто")))
        assertNull(WakeWord.extractCommand(listOf("що там спіді")))
    }

    @Test
    fun updateCommands() {
        listOf("перевір оновлення", "перевірити оновлення", "Перевір, чи є оновлення?", "чи є оновлення",
            "є нова версія", "яка в тебе версія").forEach {
            assertEquals(it, LocalCommand.CHECK_UPDATE, LocalCommands.match(it))
        }
        listOf("оновись", "оновися", "онови застосунок", "Оновись!", "встанови оновлення",
            "завантажи нову версію", "обнови себе").forEach {
            assertEquals(it, LocalCommand.INSTALL_UPDATE, LocalCommands.match(it))
        }
    }

    @Test
    fun otherCommands() {
        assertEquals(LocalCommand.STOP, LocalCommands.match("Стоп!"))
        listOf("розкажи жарт", "оновлення погоди на завтра", "онови мені пам'ять про погоду").forEach {
            assertNull(it, LocalCommands.match(it))
        }
    }
}
