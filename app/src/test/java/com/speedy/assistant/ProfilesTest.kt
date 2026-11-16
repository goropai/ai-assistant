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
        assertTrue(p.summary("Тарас", at(1, 11)).contains("сьогодні в червоному"))
        assertEquals("Ти сьогодні в червоному!", p.greetingHint("Тарас", at(1, 11)))
        assertFalse(p.summary("Тарас", at(2, 11)).contains("сьогодні")) // учорашній одяг — не сьогоднішній
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
    fun aboutMeCommand() {
        assertEquals(LocalCommand.ABOUT_ME, LocalCommands.match("що ти про мене знаєш?"))
        assertEquals(LocalCommand.ABOUT_ME, LocalCommands.match("що ти пам'ятаєш про мене"))
    }
}
