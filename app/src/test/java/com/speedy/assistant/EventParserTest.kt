package com.speedy.assistant

import com.speedy.assistant.phone.EventParser
import com.speedy.assistant.phone.NewEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EventParserTest {
    private val utc = TimeZone.getTimeZone("UTC")
    /** 2026-11-17 — вівторок. */
    private fun at(day: Int, h: Int, m: Int = 0, month: Int = Calendar.NOVEMBER, year: Int = 2026) = Calendar.getInstance(utc).apply {
        clear(); set(year, month, day, h, m)
    }.timeInMillis
    private val now = at(17, 11)
    private fun parse(s: String, at: Long = now) = EventParser.parse(s, at, utc)

    @Test
    fun weekdayAndTime() {
        assertEquals(NewEvent("Лікаря", at(20, 10), false), parse("Запиши в календар лікаря на п'ятницю о 10"))
        // Так пише розпізнавач: без апострофа чи з ʼ.
        assertEquals(NewEvent("Лікаря", at(20, 10), false), parse("запиши в календар лікаря на пятницю о 10"))
        assertEquals(NewEvent("Лікаря", at(20, 10), false), parse("запиши в календар лікаря на пʼятницю о 10"))
        assertEquals(NewEvent("Зустріч з тарасом", at(18, 15, 30), false), parse("додай у календар зустріч з Тарасом завтра о 15:30"))
        assertEquals(NewEvent("Батьківські збори", at(17, 18), false), parse("створи подію батьківські збори о 6 вечора"))
    }

    @Test
    fun dateAllDayAndNextYear() {
        assertEquals(NewEvent("День народження", at(21, 0, month = Calendar.DECEMBER), true), parse("запиши в календар день народження 21 грудня"))
        // Березень уже минув — наступного року.
        assertEquals(NewEvent("Подія", at(5, 0, month = Calendar.MARCH, year = 2027), true), parse("додай в календар 5 березня"))
    }

    @Test
    fun todayOrTomorrowByTime() {
        // О 9 вже минуло (зараз 11) — завтра; о 14 — сьогодні.
        assertEquals(at(18, 9), parse("створи подію пробіжка о 9")!!.start)
        assertEquals(at(17, 14), parse("створи подію обід о 14")!!.start)
        // "У вівторок о 18", сказане у вівторок об 11, — сьогодні; "у вівторок о 9" — наступного тижня.
        assertEquals(at(17, 18), parse("запиши в календар тренування у вівторок о 18")!!.start)
        assertEquals(at(24, 9), parse("запиши в календар тренування у вівторок о 9")!!.start)
    }

    @Test
    fun notAnEvent() {
        assertNull(parse("що в мене в календарі"))
        assertNull(parse("нагадай о 18 подзвонити мамі"))
    }
}
