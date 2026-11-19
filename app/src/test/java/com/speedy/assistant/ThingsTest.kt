package com.speedy.assistant

import com.speedy.assistant.things.SceneChange
import com.speedy.assistant.things.Seen
import com.speedy.assistant.things.ThingCommands
import com.speedy.assistant.things.ThingCommands.Command
import com.speedy.assistant.things.ThingMemory
import com.speedy.assistant.things.ThingScan
import com.speedy.assistant.things.ThingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hour = 3600_000L

    @Test
    fun findsThingInAnyCase() {
        val m = ThingMemory(null)
        m.observe(listOf(Seen("окуляри", "на столі біля вікна", "чорні"), Seen("чашка", "на полиці")), "front", 0)
        assertEquals("окуляри", m.find("мої окуляри").single().name)
        assertEquals("окуляри", m.find("моїх окулярів").single().name)
        assertEquals("чашка", m.find("чашку").single().name)
        assertTrue(m.find("ключі").isEmpty())
        assertTrue(m.find("мої").isEmpty())
    }

    @Test
    fun recordsMovesAppearancesAndDisappearances() {
        val m = ThingMemory(null)
        m.observe(listOf(Seen("окуляри", "на столі"), Seen("книжка", "на дивані")), "front", 0)
        assertTrue("перший огляд — без \"нового\"", m.changesSince(0).isEmpty())
        // Окуляри переклали, з'явилась чашка; книжку пропустили раз — ще не зникла.
        m.observe(listOf(Seen("окуляри", "на полиці"), Seen("чашка", "на столі")), "front", hour)
        val first = m.changesSince(0)
        assertEquals(2, first.size)
        assertTrue(first.any { it.name == "окуляри" && it.from == "на столі" && it.to == "на полиці" })
        assertTrue(first.any { it.name == "чашка" && it.from == null })
        // Удруге без книжки — зникла.
        m.observe(listOf(Seen("окуляри", "на полиці"), Seen("чашка", "на столі")), "front", 2 * hour)
        assertTrue(m.changesSince(0).any { it.name == "книжка" && it.to == null })
        assertTrue(m.find("книжку").single().gone)
        assertFalse(m.summary().contains("книжка"))
    }

    @Test
    fun samePlaceInOtherWordsIsNotAMove() {
        val m = ThingMemory(null)
        m.observe(listOf(Seen("пульт", "на дивані")), "front", 0)
        m.observe(listOf(Seen("пульт", "на дивані біля подушки")), "front", hour)
        assertTrue(m.changesSince(0).isEmpty())
    }

    @Test
    fun otherCameraDoesNotHideThings() {
        val m = ThingMemory(null)
        m.observe(listOf(Seen("окуляри", "на столі")), "front", 0)
        m.observe(emptyList(), "back", hour)
        m.observe(emptyList(), "back", 2 * hour)
        assertFalse(m.find("окуляри").single().gone)
    }

    @Test
    fun survivesRestartAndForgets() {
        val file = tmp.newFile("things.json")
        ThingMemory(file).observe(listOf(Seen("ключі", "на тумбочці в коридорі")), "front", 0)
        val again = ThingMemory(file)
        assertEquals("на тумбочці в коридорі", again.find("ключі").single().place)
        again.clear()
        assertTrue(ThingMemory(file).isEmpty())
    }

    @Test
    fun oldThingsAreForgotten() {
        val m = ThingMemory(null)
        m.observe(listOf(Seen("парасолька", "біля дверей")), "front", 0)
        m.observe(listOf(Seen("чашка", "на столі")), "back", ThingMemory.KEEP_MS + hour)
        assertTrue(m.find("парасольку").isEmpty())
    }

    @Test
    fun parsesModelReply() {
        val seen = ThingScan.parse("""{"things": [{"name": "Окуляри", "place": "на столі", "details": "чорні"}, {"name": "", "place": "x"}]}""")!!
        assertEquals(listOf(Seen("окуляри", "на столі", "чорні")), seen)
        assertEquals(emptyList<Seen>(), ThingScan.parse("""{"things": []}"""))
        assertEquals(1, ThingScan.parse("```json\n{\"things\": [{\"name\": \"чашка\", \"place\": \"на полиці\"}]}\n```")!!.size)
        assertNull(ThingScan.parse("не JSON"))
        assertTrue(ThingScan.prompt(listOf("чашка")).contains("чашка"))
    }

    @Test
    fun commands() {
        assertEquals(Command.Find("мої окуляри"), ThingCommands.parse("Де мої окуляри?"))
        assertEquals(Command.Find("ключі"), ThingCommands.parse("куди я поклала ключі"))
        assertEquals(Command.Find("мій пульт"), ThingCommands.parse("ти не бачила мій пульт"))
        assertEquals(Command.Changes, ThingCommands.parse("що змінилось у кімнаті"))
        assertEquals(Command.List, ThingCommands.parse("що де лежить"))
        assertEquals(Command.Changes, ThingCommands.parse("Спіді, що змінилось у кімнаті?"))
        assertEquals(Command.Forget, ThingCommands.parse("забудь, що де лежить"))
        // Це інші команди: де ми (місце), де ти / де телефон (знайти телефон).
        listOf("де ми", "де ти", "де телефон", "де мій телефон", "де ми зараз", "котра година").forEach {
            assertNull(it, ThingCommands.parse(it))
        }
    }

    @Test
    fun answers() {
        val now = 1_763_500_000_000L
        val m = ThingMemory(null)
        m.observe(listOf(Seen("окуляри", "на столі біля вікна")), "front", now - 10 * 60_000)
        val text = ThingText.found(m.find("окуляри"), now)
        assertTrue(text, text.startsWith("Окуляри: востаннє бачила на столі біля вікна — сьогодні о "))
        assertEquals("Нічого не помітила — усе на своїх місцях.", ThingText.changes(emptyList()))
        assertEquals("Я ще не встигла роздивитися кімнату.", ThingText.list(""))
    }

    @Test
    fun scansOnlyChangedStillRoomAndNotTooOften() {
        val s = SceneChange(minGapMs = 15 * 60_000, maxPerHour = 4, stableMs = 5_000)
        val dark = IntArray(SceneChange.W * SceneChange.H) { 50 }
        val bright = IntArray(SceneChange.W * SceneChange.H) { 120 }
        assertFalse(s.feed(dark, 0, person = false)) // перший кадр — ще не знаємо, чи завмерла
        assertFalse(s.feed(dark, 3_000, person = false))
        assertTrue(s.feed(dark, 6_000, person = false)) // ще не оглядали
        s.scanned(dark, 6_000)
        assertFalse("та сама кімната", s.feed(dark, 20 * 60_000, person = false))
        // Змінилась, але хтось у кадрі — чекаємо.
        assertFalse(s.feed(bright, 21 * 60_000, person = true))
        assertFalse(s.feed(bright, 21 * 60_000 + 6_000, person = true))
        assertFalse(s.feed(bright, 22 * 60_000, person = false))
        assertTrue(s.feed(bright, 22 * 60_000 + 6_000, person = false))
        s.scanned(bright, 22 * 60_000 + 6_000)
        // Знову змінилась одразу — не частіше ніж раз на 15 хв.
        assertFalse(s.feed(dark, 23 * 60_000, person = false))
        assertFalse(s.feed(dark, 23 * 60_000 + 6_000, person = false))
    }
}
