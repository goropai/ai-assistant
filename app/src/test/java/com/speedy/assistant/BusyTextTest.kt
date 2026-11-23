package com.speedy.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyTextTest {
    @Test
    fun progress() {
        assertEquals("Ще думаю, хвилинку…", BusyText.progress(null, 0))
        assertEquals("Ще шукаю казку, хвилинку…", BusyText.progress("Ще шукаю казку", 0))
        assertEquals("Ще шукаю казку, ще трохи.", BusyText.progress("Ще шукаю казку", 1))
        // Третій раз — підказати, як зупинити.
        assertTrue(BusyText.progress("Ще шукаю казку", 2).contains("торкнись мене"))
        assertEquals("Ще завантажую оновлення…", BusyText.progress("Ще завантажую оновлення", 5))
        // Оновлення — без "торкнись мене": зупиняти немає сенсу.
        assertEquals("Ще завантажую оновлення…", BusyText.progress("Ще завантажую оновлення", 2, offerStop = false))
        assertTrue(BusyText.NEXT_MS > BusyText.FIRST_MS)
    }
}
