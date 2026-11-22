package com.speedy.assistant

import com.speedy.assistant.brushing.BrushCommands
import com.speedy.assistant.brushing.BrushPlan
import com.speedy.assistant.brushing.BrushStars
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
}
