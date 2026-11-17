package com.speedy.assistant

import com.speedy.assistant.people.Clothing
import com.speedy.assistant.people.Profiles
import com.speedy.assistant.people.SelfFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class ProfilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3600_000L
    private val day = 24 * hour

    /** 1 листопада 2026, [h]:00 UTC + [d] днів. */
    private fun at(d: Int, h: Int): Long = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.NOVEMBER, 1 + d, h, 0)
    }.timeInMillis

    @Test
    fun selfFactsWithoutModel() {
        assertEquals("любить каву", SelfFacts.extract("Я люблю каву"))
        assertEquals("не любить дощ", SelfFacts.extract("я не люблю дощ"))
        assertEquals("любить коти", SelfFacts.extract("мені подобаються коти"))
        assertEquals("улюблений фільм — інтерстеллар", SelfFacts.extract("мій улюблений фільм інтерстеллар"))
        assertEquals("працює лікарем", SelfFacts.extract("я працюю лікарем"))
        assertEquals("є собака", SelfFacts.extract("у мене є собака"))
        assertEquals("у тебе є собака", Profiles.secondPerson("є собака"))
        assertNull(SelfFacts.extract("розкажи про космос"))
        assertTrue(SelfFacts.isPlainStatement("я люблю каву"))
        assertFalse(SelfFacts.isPlainStatement("я люблю каву, а ти що любиш пити зранку коли прокидаєшся?"))
    }

    @Test
    fun factsPersistAndOppositeReplaces() {
        val file = File(tmp.root, "profiles.json")
        val p = Profiles(file, utc)
        assertTrue(p.addFact("Тарас", "любить чай"))
        assertFalse(p.addFact("Тарас", "любить чай"))
        assertTrue(p.addFact("Тарас", "не любить чай"))
        assertTrue(p.addFact("Тарас", "працює лікарем"))
        val loaded = Profiles(file, utc)
        assertEquals("не любить чай; працює лікарем", loaded.summary("Тарас", at(0, 12)))
        assertEquals("", loaded.summary("Ніна", at(0, 12)))
    }

    @Test
    fun habitFromVisits() {
        val p = Profiles(null, utc)
        repeat(6) { d -> p.seen("Тарас", at(d, 19)) }
        assertTrue(p.summary("Тарас", at(6, 19)).contains("зазвичай буває ввечері"))
        assertTrue(p.describe("Тарас", at(6, 19)).contains("Ми бачились уже 6 разів"))
    }

    @Test
    fun clothingTodayAndChangeNoticed() {
        val p = Profiles(null, utc)
        p.clothing("Тарас", "синьому", at(0, 10))
        p.clothing("Тарас", "червоному", at(1, 10))
        // Моделі одяг не передаємо (інакше говорить про нього щоразу) — лише у відповіді "що знаєш" і вітанні.
        assertFalse(p.summary("Тарас", at(1, 11)).contains("червоному"))
        assertTrue(p.describe("Тарас", at(1, 11)).contains("Сьогодні ти в червоному"))
        assertEquals("Ти сьогодні в червоному!", p.clothingHint("Тарас", at(1, 11)))
        // Сказала — того ж дня більше не повторює.
        p.clothingMentioned("Тарас", at(1, 11))
        assertNull(p.clothingHint("Тарас", at(1, 12)))
        assertFalse(p.describe("Тарас", at(2, 11)).contains("Сьогодні")) // учорашній одяг — не сьогоднішній
    }

    @Test
    fun longAbsence() {
        val p = Profiles(null, utc)
        p.seen("Тарас", at(0, 10))
        p.seen("Тарас", at(5, 10)) // новий візит через 5 днів
        assertEquals("Давно не бачились!", p.greetingHint("Тарас", at(5, 10)))
    }

    @Test
    fun renameMergesProfile() {
        val p = Profiles(null, utc)
        p.addFact("Бетмен", "любить каву")
        p.rename("Бетмен", "Тарас")
        assertEquals("любить каву", p.summary("Тарас", at(0, 12)))
        assertTrue("Бетмен" !in p.names())
    }

    @Test
    fun describeInSecondPerson() {
        val p = Profiles(null, utc)
        p.addFact("Тарас", "любить каву")
        p.addFact("Тарас", "працює лікарем")
        assertEquals("Ти любиш каву, працюєш лікарем.", p.describe("Тарас", at(0, 12)))
    }

    @Test
    fun clothingColorNames() {
        assertEquals("чорному", Clothing.name(0f, 0f, 0.1f))
        assertEquals("білому", Clothing.name(0f, 0.05f, 0.95f))
        assertEquals("синьому", Clothing.name(225f, 0.7f, 0.6f))
        assertEquals("червоному", Clothing.name(5f, 0.8f, 0.8f))
        assertEquals("зеленому", Clothing.name(120f, 0.6f, 0.5f))
    }

    @Test
    fun ageByVoiceBeatsFace() {
        val p = Profiles(null, utc)
        // Обличчя за очима — "доросле" (у Ліни 0.40–0.49, як у тата), але голос дитячий.
        repeat(6) { p.faceSample("Ліна", 0.42f, 0) }
        assertEquals(Profiles.Age.UNKNOWN, p.age("Ліна"))
        repeat(3) { p.voiceSample("Ліна", 310f, 0) }
        assertEquals(Profiles.Age.CHILD, p.age("Ліна"))
        repeat(3) { p.voiceSample("Тарас", 120f, 0) }
        assertEquals(Profiles.Age.ADULT, p.age("Тарас"))
        p.setAge("Тарас", Profiles.Age.CHILD) // сказане вголос — головне
        assertEquals(Profiles.Age.CHILD, p.age("Тарас"))
    }

        @Test
    fun aboutMeCommand() {
        assertEquals(LocalCommand.ABOUT_ME, LocalCommands.match("що ти про мене знаєш?"))
        assertEquals(LocalCommand.ABOUT_ME, LocalCommands.match("що ти пам'ятаєш про мене"))
    }

    @Test
    fun greetOnlyAfterLongBreak() {
        val p = Profiles(null, utc)
        val start = at(17, 9)
        var now = start
        p.seen("Тарас", start)
        assertEquals(Long.MAX_VALUE, p.returnGap("Тарас", now)) // уперше — "привіт"
        p.greeted("Тарас")
        // Увесь час у кадрі (кадри щохвилини) — через 10 хв знову впізнала: не "привіт".
        (1..10).forEach { p.seen("Тарас", start + it * 60_000L) }
        now = start + 10 * 60_000L
        assertEquals(0L, p.returnGap("Тарас", now))
        // Пішов на 20 хв і повернувся — перерва коротка (< 3 год): "ти знову тут".
        now = start + 30 * 60_000L
        p.seen("Тарас", now)
        assertEquals(20 * 60_000L, p.returnGap("Тарас", now))
        p.greeted("Тарас")
        // Пішов на 4 години — знову "привіт".
        now = start + 30 * 60_000L + 4 * 3600_000L
        assertTrue(p.returnGap("Тарас", now) >= 3 * 3600_000L) // привітання раніше, ніж камера зарахувала появу
        p.seen("Тарас", now)
        assertTrue(p.returnGap("Тарас", now) >= 3 * 3600_000L)
        // Перезапуск застосунку (перерва не рахувалась): бачились 1 хв тому — не "привіт".
        val restarted = Profiles(null, utc).apply { seen("Тарас", now) }
        restarted.seen("Тарас", now + 60_000L)
        assertEquals(60_000L, restarted.returnGap("Тарас", now + 60_000L))
    }
}