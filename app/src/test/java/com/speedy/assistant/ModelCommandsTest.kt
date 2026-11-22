package com.speedy.assistant

import com.speedy.assistant.books.BookCommands
import com.speedy.assistant.guard.GuardCommands
import com.speedy.assistant.news.News
import com.speedy.assistant.school.SchoolCommands
import com.speedy.assistant.media.MediaCommands
import com.speedy.assistant.night.NightCommands
import com.speedy.assistant.phone.EventParser
import com.speedy.assistant.phone.PhoneCommand
import com.speedy.assistant.phone.PhoneCommands
import com.speedy.assistant.translate.TranslateCommands
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кожна форма з опису дії "command" для моделі (Brain) має розбиратися тим самим ланцюжком, що й
 * у Assistant.runModelCommand, — інакше модель скаже команду "за інструкцією", а Спіді не зрозуміє.
 */
class ModelCommandsTest {
    private val allowedLocal = setOf(
        LocalCommand.ENGLISH_LESSON, LocalCommand.ENGLISH_PROGRESS, LocalCommand.REMIND, LocalCommand.LIST_REMINDERS,
        LocalCommand.CANCEL_REMINDERS, LocalCommand.FIND_PHONE,
        LocalCommand.TIME, LocalCommand.WEATHER, LocalCommand.WHERE, LocalCommand.BATTERY,
    )

    private fun understood(command: String): Boolean {
        LocalCommands.match(command)?.let { return it in allowedLocal }
        if (EventParser.parse(command, System.currentTimeMillis()) != null) return true
        if (NightCommands.parse(command) != null) return true
        if (GuardCommands.parse(command) == true) return true // лише ввімкнути
        if (SchoolCommands.parse(command) != null) return true
        if (com.speedy.assistant.brushing.BrushCommands.parse(command) != null) return true
        if (News.isRequest(command)) return true
        if (com.speedy.assistant.home.HomeCommands.parse(command, HomeTest.DEVICES) != null) return true
        when (com.speedy.assistant.things.ThingCommands.parse(command)) {
            null -> {}
            com.speedy.assistant.things.ThingCommands.Command.Forget -> return false
            else -> return true
        }
        if (TranslateCommands.parse(command) != null) return true
        if (com.speedy.assistant.media.OnlineVideoCommands.parse(command) != null) return true
        if (com.speedy.assistant.books.Tales.parse(command) != null) return true
        if (MediaCommands.parse(command) != null) return true
        if (BookCommands.parse(command) != null) return true
        return when (PhoneCommands.parse(command)) {
            is PhoneCommand.TodayCalendar, is PhoneCommand.Alarm, is PhoneCommand.Timer, is PhoneCommand.Call -> true
            else -> false
        }
    }

    @Test
    fun everyDocumentedFormIsUnderstood() {
        listOf(
            "почитай книгу Кобзар", "почитай книгу Clean Code українською", "продовж читати", "які в тебе є книги",
            "яку книгу ми читаємо", "читай з початку", "з початку розділу", "наступний розділ", "попередній розділ",
            "читай українською", "читай в оригіналі",
            "увімкни фільм Дюна", "увімкни мультик Шрек", "увімкни аудіокнигу Кобзар", "увімкни музику Океан Ельзи",
            "продовж дивитись", "продовж аудіокнигу", "наступна серія", "пауза", "які є фільми", "які є аудіокниги",
            "урок англійської", "мій прогрес в англійській",
            "нагадай о 18:00 подзвонити мамі", "нагадай через 30 хвилин вимкнути духовку", "які в мене нагадування",
            "постав будильник на 07:00", "постав таймер на 5 хвилин", "постав таймер на 30 секунд", "котра година",
            "що в мене сьогодні в календарі", "запиши в календар лікаря у п'ятницю о 10:00",
            "подзвони мамі",
            "переклади на англійську я люблю каву", "переклади на російську добрий день",
            "яка погода", "яка погода завтра", "яка погода у Львові", "де ми", "скільки в тебе заряду",
            "послухай уночі чи я хроплю", "чи я хропів", "увімкни запис хропіння",
            "знайди на ютубі мультики про котів",
            "урок математики", "урок логіки", "урок про навколишній світ", "математика англійською",
            "урок української мови", "урок історії", "урок математики для Ліни",
            "огляд новин", "скасуй нагадування", "яке повітря", "де ти", "вартуй",
            "де мої окуляри", "що змінилось у кімнаті",
            "увімкни Лампа кухня", "вимкни Розетка чайник", "яскравість 30 відсотків Лампа кухня", "закрий Штори спальня", "що вдома",
            "яка температура Датчик спальня", "яка температура вдома", "знайди казку Пан Коцький", "я чищу зуби", "мої зірочки",
            "увімкни перше відео", "увімкни друге відео", "увімкни третє відео", "закрий відео",
        ).forEach { assertTrue("модель скаже «$it», а Спіді не зрозуміє", understood(it)) }
    }

    @Test
    fun forbiddenStayForbidden() {
        // Налаштування, PIN, знайомства, оновлення — моделі ні.
        listOf("зміни пін-код", "мене звати Тарас", "забудь мене", "оновись", "це дитина", "досить вартувати", "забудь, що де лежить").forEach {
            assertTrue("«$it» не має виконуватися від моделі", !understood(it))
        }
    }
}
