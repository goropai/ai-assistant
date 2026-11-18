package com.speedy.assistant

import com.speedy.assistant.initiative.Facts
import com.speedy.assistant.people.Profiles
import com.speedy.assistant.school.Numbers
import com.speedy.assistant.school.SchoolCommands
import com.speedy.assistant.school.SchoolGenerator
import com.speedy.assistant.school.SchoolProgress
import com.speedy.assistant.school.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class SchoolTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun commands() {
        assertEquals(Subject.MATH, SchoolCommands.parse("Спіді, давай урок математики"))
        assertEquals(Subject.MATH, SchoolCommands.parse("давай порахуємо"))
        assertEquals(Subject.LOGIC, SchoolCommands.parse("загадай логічну задачку"))
        assertEquals(Subject.LOGIC, SchoolCommands.parse("урок логіки"))
        assertEquals(Subject.WORLD, SchoolCommands.parse("розкажи урок про навколишній світ"))
        assertEquals(Subject.MATH_EN, SchoolCommands.parse("математика англійською"))
        assertEquals(Subject.MATH_EN, SchoolCommands.parse("давай порахуємо англійською"))
        assertEquals(Subject.UKRAINIAN, SchoolCommands.parse("давай урок української мови"))
        assertEquals(Subject.UKRAINIAN, SchoolCommands.parse("навчи Ліну української"))
        assertEquals(Subject.HISTORY, SchoolCommands.parse("Спіді, урок історії"))
        assertEquals(Subject.HISTORY, SchoolCommands.parse("позаймаємось історією"))
        assertNull(SchoolCommands.parse("розкажи історію про котика"))
        assertNull(SchoolCommands.parse("розмовляй українською"))
        assertNull(SchoolCommands.parse("що таке логіка"))
        assertNull(SchoolCommands.parse("урок англійської"))
    }

    @Test
    fun learnerByName() {
        // Як People.findName: ім'я з відмінком ("Ліну", "Ліни", "Ліною" → "Ліна").
        val find = { w: String -> "Ліна".takeIf { w.lowercase().startsWith("лін") } }
        assertEquals("Ліна", com.speedy.assistant.school.Learner.named("Спіді, навчи Ліну англійської", find))
        assertEquals("Ліна", com.speedy.assistant.school.Learner.named("урок математики для Ліни", find))
        assertEquals("Ліна", com.speedy.assistant.school.Learner.named("позаймайся з Ліною логікою", find))
        assertNull(com.speedy.assistant.school.Learner.named("навчи мене англійської", find))
    }

    @Test
    fun numbersSpokenOrDigits() {
        assertEquals("23", Numbers.normalize("двадцять три"))
        assertEquals("21", Numbers.normalize("twenty-one"))
        assertTrue(Numbers.matches(listOf("п'ять"), listOf("5")))
        assertTrue(Numbers.matches(listOf("it is five"), listOf("five", "5")))
        assertTrue(Numbers.matches(listOf("відповідь 12"), listOf("дванадцять")))
        assertFalse(Numbers.matches(listOf("15"), listOf("5")))
        assertTrue(Numbers.matches(listOf("це кіт"), listOf("кіт")))
    }

    @Test
    fun generatedLessonParsed() {
        val json = """{"topic":"Додавання до 10","intro":"Порахуймо!","tasks":[
            {"q":"Скільки буде 2 плюс 3?","lang":"uk","answers":["5","п'ять"],"hint":"Порахуй на пальцях","explain":"2 і ще 3 — це 5."},
            {"q":"How much is one plus one?","lang":"en","answers":["two","2"],"hint":"Один і ще один","explain":"One plus one is two."},
            {"q":"Скільки буде 4 мінус 1?","lang":"uk","answers":["3","три"],"hint":"","explain":"",
             "picture":[["🍎","🍎","🍎","🍎"],[],["circle:red","?"]]}]}"""
        val unit = assertNotNull(SchoolGenerator.parse("```json\n$json\n```")).let { SchoolGenerator.parse(json)!! }
        assertEquals(3, unit.tasks.size)
        assertTrue(unit.tasks[1].english)
        assertNull(unit.tasks[0].picture)
        assertEquals(listOf(listOf("🍎", "🍎", "🍎", "🍎"), listOf("circle:red", "?")), unit.tasks[2].picture)
        assertNull(SchoolGenerator.parse("""{"topic":"x","tasks":[]}"""))
    }

    @Test
    fun levelFollowsResults() {
        val p = SchoolProgress(tmp.newFile("school.json").also { it.delete() })
        assertEquals(1, p.level("Ліна", Subject.MATH))
        assertEquals(2, p.finish("Ліна", Subject.MATH, "Лічба", 5, 5, emptyList(), 1))
        assertEquals(2, p.finish("Ліна", Subject.MATH, "Додавання", 3, 5, listOf("2+3"), 2))
        assertEquals(1, p.finish("Ліна", Subject.MATH, "Віднімання", 1, 5, listOf("5-2"), 3))
        assertTrue(p.history("Ліна", Subject.MATH).last().contains("помилки: 5-2"))
        assertEquals(1, p.level("Ліна", Subject.LOGIC)) // предмети окремо
    }

    @Test
    fun factReactionsGoToProfile() {
        assertEquals(Facts.Reaction.LIKED, Facts.reaction("ого, не знав!"))
        assertEquals(Facts.Reaction.DISLIKED, Facts.reaction("не цікаво"))
        assertEquals(Facts.Reaction.MORE, Facts.reaction("розкажи ще"))
        assertNull(Facts.reaction("а котра година"))
        assertNotNull(Facts.parse("""{"topic":"Космос","fact":"На Венері день довший за рік — вона обертається дуже повільно."}"""))

        val p = Profiles(null, TimeZone.getTimeZone("UTC"))
        p.seen("Ліна", 0)
        assertTrue(p.factDue("Ліна", 1000))
        p.factTold("Ліна", "космос", "На Венері день довший за рік", 1000)
        assertFalse(p.factDue("Ліна", 2000))
        p.factReaction("Ліна", "космос", liked = true)
        p.factReaction("Ліна", "футбол", liked = false)
        val (liked, disliked, told) = p.interests("Ліна")
        assertEquals(listOf("космос"), liked)
        assertEquals(listOf("футбол"), disliked)
        assertEquals(1, told.size)
        assertTrue(p.summary("Ліна", 2000).contains("цікавиться: космос"))
    }
}
