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
    fun findRobotCommands() {
        listOf("знайди платформу", "Знайди робота!", "пошукай колеса", "підключись до платформи").forEach {
            assertEquals(it, LocalCommand.FIND_ROBOT, LocalCommands.match(it))
        }
    }

    @Test
    fun englishCommands() {
        listOf(
            "проведи урок англійської", "давай вчити англійську", "займемося англійською", "англійська урок",
            // Так дитячу фразу чув розпізнавач:
            "навчи мене англійської", "навчись будь ласка мене англійської", "навчиш будь ласка мене англійської",
            "будь ласка навчиш мене англійської", "Спіді, навчи мене, будь ласка, англійської!",
            "можеш навчити мене англійської?", "повчи зі мною англійську",
        ).forEach {
            assertEquals(it, LocalCommand.ENGLISH_LESSON, LocalCommands.match(it))
        }
        // Не урок: переклад, питання про англійську.
        assertEquals(null, LocalCommands.match("переклади на англійську я люблю каву"))
        assertEquals(null, LocalCommands.match("хто вчитель англійської у школі"))
        // "поклич мене нагадуванням" — не знайомство з іменем "Нагадуванням".
        assertEquals(null, LocalCommands.parseName("поклич мене нагадуванням за пів години"))
        assertEquals("Тарас", LocalCommands.parseName("клич мене Тарас"))
        assertEquals(LocalCommand.ENGLISH_PROGRESS, LocalCommands.match("як мій прогрес в англійській?"))
        assertEquals(LocalCommand.ENGLISH_LEVEL, LocalCommands.match("мій рівень англійської — середній"))
        assertEquals(LocalCommand.ENGLISH_LEVEL, LocalCommands.match("постав рівень англійської малюк"))
    }

        @Test
    fun endOfConversation() {
        listOf("дякую", "Дякую, все", "бувай", "на сьогодні все", "все, дякую", "можеш відпочити").forEach {
            assertEquals(it, LocalCommand.END_CONVERSATION, LocalCommands.match(it))
        }
        assertEquals(null, LocalCommands.match("дякую, а розкажи ще про котів"))
    }

    @Test
    fun wakeWordAtEnd() {
        assertEquals("А хто це там сидить", com.speedy.assistant.voice.WakeWord.extractCommand(listOf("А хто це там сидить, Спіді")))
        assertEquals("котра година", com.speedy.assistant.voice.WakeWord.extractCommand(listOf("Спіді, котра година")))
        // Офлайн-модель (Vosk) не знає "спіді" і чує "спід і".
        assertEquals("котра година", com.speedy.assistant.voice.WakeWord.extractCommand(listOf("спід і котра година")))
        assertEquals("", com.speedy.assistant.voice.WakeWord.extractCommand(listOf("спід і")))
        assertEquals(null, com.speedy.assistant.voice.WakeWord.extractCommand(listOf("я купив спідниці")))
        // Дитяча вимова "Спіді" (так розпізнав Google, коли говорила Ліна).
        assertEquals("як справи", com.speedy.assistant.voice.WakeWord.extractCommand(listOf("Привіт Сенді як справи")))
        assertEquals("", com.speedy.assistant.voice.WakeWord.extractCommand(listOf("привіт спидин")))
        assertEquals("", com.speedy.assistant.voice.WakeWord.extractCommand(listOf("співдін")))
        assertEquals(null, com.speedy.assistant.voice.WakeWord.extractCommand(listOf("спить кіт")))
        assertEquals(null, com.speedy.assistant.voice.WakeWord.extractCommand(listOf("сьогодні холодно")))
    }

    @Test
    fun timeAndBattery() {
        listOf("котра година", "Котра зараз година?", "скільки часу", "який зараз час").forEach {
            assertEquals(it, LocalCommand.TIME, LocalCommands.match(it))
        }
        listOf("скільки в тебе заряду", "який у тебе заряд", "як батарея?").forEach {
            assertEquals(it, LocalCommand.BATTERY, LocalCommands.match(it))
        }
        assertEquals(null, LocalCommands.match("розкажи, як працює батарея в електромобілі і чому вона сідає"))
    }

    @Test
    fun otherCommands() {
        assertEquals(LocalCommand.STOP, LocalCommands.match("Стоп!"))
        listOf("розкажи жарт").forEach {
            assertNull(it, LocalCommands.match(it))
        }
        // Слово "оновлення" не має запускати оновлення застосунку — це питання про погоду.
        listOf("оновлення погоди на завтра", "онови мені пам'ять про погоду").forEach {
            assertEquals(it, LocalCommand.WEATHER, LocalCommands.match(it))
        }
    }
}
