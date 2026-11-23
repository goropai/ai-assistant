package com.speedy.assistant

import com.speedy.assistant.brushing.BrushCommands
import com.speedy.assistant.brushing.BrushPlan
import com.speedy.assistant.brushing.BrushStars
import com.speedy.assistant.brushing.BrushStory
import com.speedy.assistant.brushing.BrushText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BrushingTest {
    @Test
    fun parse() {
        listOf("я чищу зуби", "Спіді, я чищу зубки", "давай почистимо зуби", "час чистити зуби", "почистимо зубоньки",
            "я йду чистити зуби").forEach { assertEquals(it, BrushCommands.Command.Start, BrushCommands.parse(it)) }
        listOf("скільки в мене зірочок", "мої зірочки", "скільки днів я чищу зуби")
            .forEach { assertEquals(it, BrushCommands.Command.Stars, BrushCommands.parse(it)) }
        // Питання й розповіді — не команда (хай відповідає модель).
        listOf("чи чистила ти зуби", "як правильно чистити зуби", "у мене болять зуби").forEach { assertNull(it, BrushCommands.parse(it)) }
    }

    @Test
    fun plan() {
        assertEquals(BrushPlan.Zone.UPPER_RIGHT, BrushPlan.zone(0))
        assertEquals(BrushPlan.Zone.UPPER_LEFT, BrushPlan.zone(30))
        assertEquals(BrushPlan.Zone.LOWER_RIGHT, BrushPlan.zone(119))
        assertEquals(0.5f, BrushPlan.zoneDone(45.0, BrushPlan.Zone.UPPER_LEFT))
        assertEquals(1f, BrushPlan.zoneDone(45.0, BrushPlan.Zone.UPPER_RIGHT))
        assertTrue(BrushPlan.line(0)!!.contains("верхні зубки праворуч"))
        assertEquals("А тепер — нижні зубки ліворуч!", BrushPlan.line(60))
        assertEquals("Ще десять секунд!", BrushPlan.line(110))
        assertNull(BrushPlan.line(7))
        // Підбадьорення посеред кожної зони.
        assertTrue((0 until 4).all { BrushPlan.line(it * 30 + 15) != null })
    }

    private fun at(day: Int, hour: Int) = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, day, hour, 0, 0) }.timeInMillis

    @Test
    fun stars() {
        var file: String? = null
        val stars = BrushStars(read = { file }, write = { file = it })
        val morning = stars.record("Ліна", at(20, 8))
        assertEquals(true, morning.newStar)
        assertEquals(1, morning.streak)
        // Двічі зранку — друга зірочка за ранок не дається; увечері — дається.
        assertEquals(false, stars.record("Ліна", at(20, 9)).newStar)
        assertEquals(2, stars.record("Ліна", at(20, 20)).starsToday)
        stars.record("Ліна", at(21, 8))
        val third = stars.record("Ліна", at(22, 8))
        assertEquals(3, third.streak)
        assertEquals(3, third.milestone) // три дні поспіль — особлива похвала
        assertEquals(4, third.total)
        // Пропустили день — серія спочатку; сьогодні ще не чистили — рахуємо до вчора.
        assertEquals(3, stars.streak("Ліна", at(23, 8)))
        assertEquals(1, stars.record("Ліна", at(24, 8)).streak)
        // Зберігається у файл і читається знову; імена — без урахування регістру.
        assertEquals(5, BrushStars(read = { file }, write = {}).total("ліна"))
        assertEquals(0, stars.total("Тарас"))
    }

    @Test
    fun text() {
        assertEquals("21 день", BrushText.days(21))
        assertEquals("3 зірочки", BrushText.stars(3))
        assertEquals("12 зірочок", BrushText.stars(12))
        val r = BrushStars.Result(starsToday = 1, newStar = true, streak = 7, total = 12, milestone = 7)
        assertEquals(
            "Готово! Усі мікробики переможені, зубки сяють! Тримай зірочку! Ого, 7 днів поспіль! Ти справжній герой чистих зубів! " +
                "Усього в тебе 12 зірочок.",
            BrushText.finished(r, female = true),
        )
        assertTrue(BrushText.finished(r.copy(newStar = false, milestone = null), female = true).contains("отримала"))
        assertTrue(BrushText.summary(0, 0, 0).contains("першу"))
    }

    @Test
    fun story() {
        var file: String? = null
        val story = BrushStory(read = { file }, write = { file = it })
        assertNull(story.current("Ліна"))
        assertEquals("Колобок", story.next("Ліна"))
        // Не дочитали — наступного разу з того ж речення (і після перезапуску).
        story.save("Ліна", "Колобок", 14)
        assertEquals(BrushStory.Place("Колобок", 14), BrushStory(read = { file }, write = {}).current("ліна"))
        // Дочитали — нова казка; не знайшлась — наступна після неї.
        story.finish("Ліна", "Колобок")
        assertNull(story.current("Ліна"))
        assertEquals("Рукавичка", story.next("Ліна"))
        assertEquals("Котик і півник", story.next("Ліна", skip = setOf("Рукавичка")))
        // У кожного своя.
        assertEquals("Колобок", story.next("Тарас"))
        // Підготовлена на наступний раз — першою; дочитали її — більше не пропонується.
        story.setUpcoming("Ліна", "Ріпка")
        assertEquals("Ріпка", story.next("Ліна"))
        story.finish("Ліна", "Ріпка")
        assertNull(story.upcoming("Ліна"))
        // Усі зі списку дочитали — не по колу: нову вибере модель, повтор — лише найдавнішої.
        BrushStory.TALES.forEach { story.finish("Ліна", it) }
        assertNull(story.next("Ліна"))
        assertEquals("Ріпка", story.oldestDone("Ліна")) // найдавніше дочитана (Колобок — щойно вдруге)
        assertEquals(true, "Ріпка" in story.known("Ліна"))
        // Відповідь моделі: нова — так, уже відома — ні.
        assertEquals("Вовк і семеро козенят", BrushStory.parsePick("""{"title": "«Вовк і семеро козенят»"}""", story.known("Ліна")))
        assertNull(BrushStory.parsePick("""{"title": "колобок"}""", story.known("Ліна")))
        assertNull(BrushStory.parsePick("не JSON", emptyList()))
        assertEquals(true, BrushStory.pickPrompt(listOf("Колобок")).contains("Колобок"))
        assertEquals("А поки чистимо — продовжуємо казку «Колобок».", BrushText.taleStart("Колобок", continued = true))
    }

    @Test
    fun askToFinish() {
        val sentences = listOf("а".repeat(500), "б".repeat(300), "в".repeat(300))
        assertEquals(false, BrushText.littleLeft(sentences, 0)) // 1100 — багато
        assertEquals(true, BrushText.littleLeft(sentences, 1)) // 600 — трохи
        assertEquals(false, BrushText.littleLeft(sentences, 3)) // нічого не лишилось
        assertEquals(true, BrushText.yes(listOf("так")))
        assertEquals(true, BrushText.yes(listOf("давай дочитай")))
        assertEquals(false, BrushText.yes(listOf("ні")))
        assertEquals(false, BrushText.yes(listOf("не треба")))
        assertEquals(false, BrushText.yes(listOf("потім")))
        assertEquals(false, BrushText.yes(listOf("давай наступного разу")))
        assertNull(BrushText.yes(listOf("а що там було")))
        assertNull(BrushText.yes(null))
    }
}
