package com.speedy.assistant

import com.speedy.assistant.people.Gender
import com.speedy.assistant.people.PersonalFacts
import com.speedy.assistant.people.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PersonalFactsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(month: Int, day: Int) = Calendar.getInstance(utc).apply { clear(); set(2026, month, day, 12, 0) }.timeInMillis

    @Test
    fun genderFromSpeechAndName() {
        assertEquals(Gender.FEMALE, PersonalFacts.gender("я дівчинка"))
        assertEquals(Gender.MALE, PersonalFacts.gender("А я ж хлопчик!"))
        assertNull(PersonalFacts.gender("я дівчинка люблю малювати")) // це вже розповідь, не відповідь
        assertEquals(Gender.FEMALE, Gender.guess("Ліна"))
        assertEquals(Gender.MALE, Gender.guess("Тарас"))
        assertEquals(Gender.MALE, Gender.guess("Микола")) // виняток на -а
        assertEquals(Gender.UNKNOWN, Gender.guess("Марго"))
    }

    @Test
    fun birthdayFromSpeech() {
        assertEquals("12-21", PersonalFacts.birthday("мій день народження 21 грудня"))
        assertEquals("2018-03-05", PersonalFacts.birthday("я народилась 5 березня 2018 року"))
        assertEquals("12-21", PersonalFacts.birthday("день народження 21 грудня")) // факт від моделі
        assertEquals("12-21", PersonalFacts.date("21 грудня"))
        assertEquals("12-21", PersonalFacts.date("21-го грудня"))
        assertNull(PersonalFacts.date("завтра"))
        assertEquals("21 грудня", PersonalFacts.spoken("2018-12-21"))
    }

    @Test
    fun birthdayInCalendarOrNotification() {
        val known = listOf("Тарас", "Ліна")
        assertEquals("Тарас", PersonalFacts.birthdayOf("увесь день: День народження Тараса", known))
        assertEquals("Ліна", PersonalFacts.birthdayOf("Сьогодні день народження в Ліни!", known))
        assertNull(PersonalFacts.birthdayOf("Зустріч з Тарасом", known))
        assertNull(PersonalFacts.birthdayOf("День народження Оксани", known))
    }

    @Test
    fun profileKeepsGenderBirthdayAppearance() {
        val p = Profiles(null, utc)
        p.seen("Ліна", at(Calendar.DECEMBER, 1))
        p.setAge("Ліна", Profiles.Age.CHILD)
        p.setBirthday("Ліна", "12-21")
        p.setAppearance("Ліна", "темне довге волосся", at(Calendar.DECEMBER, 1))
        assertTrue(p.birthdayToday("Ліна", at(Calendar.DECEMBER, 21)))
        assertFalse(p.birthdayToday("Ліна", at(Calendar.DECEMBER, 20)))
        val s = p.summary("Ліна", at(Calendar.DECEMBER, 21))
        assertTrue(s, s.contains("дівчинка") && s.contains("темне довге волосся") && s.contains("21 грудня (сьогодні!)"))
        assertFalse(p.appearanceStale("Ліна", at(Calendar.DECEMBER, 10)))
        assertTrue(p.appearanceStale("Ліна", at(Calendar.DECEMBER, 20)))
        // Сказали прямо — важливіше за ім'я.
        p.setGender("Ліна", Gender.MALE)
        assertEquals(Gender.MALE, p.gender("Ліна"))
        // Факт від моделі з датою — у поле дня народження.
        p.addFact("Тарас", "день народження 3 квітня", at(Calendar.DECEMBER, 1))
        assertEquals("04-03", p.birthday("Тарас"))
    }
}
