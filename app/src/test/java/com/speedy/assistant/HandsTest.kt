package com.speedy.assistant

import com.speedy.assistant.phone.HandsCommand
import com.speedy.assistant.phone.HandsCommands
import com.speedy.assistant.phone.Safety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsTest {

    @Test
    fun notificationsAnytime() {
        assertEquals(HandsCommand.News, HandsCommands.parse("що нового?", otherAppOpen = false))
        assertEquals(HandsCommand.News, HandsCommands.parse("хто мені писав", false))
        assertEquals(HandsCommand.ReadNotes, HandsCommands.parse("прочитай повідомлення", false))
        assertEquals(HandsCommand.AllowNotes, HandsCommands.parse("дозволь читати сповіщення", false))
        assertEquals(HandsCommand.AllowHands, HandsCommands.parse("дозволь керувати телефоном", false))
    }

    @Test
    fun appControlOnlyWhenOtherAppOpen() {
        // На екрані Спіді "напиши вірш" — прохання до моделі, а не введення тексту.
        assertNull(HandsCommands.parse("напиши вірш про кота", otherAppOpen = false))
        assertEquals(HandsCommand.Type("Привіт, мамо"), HandsCommands.parse("напиши «Привіт, мамо»", true))
        assertEquals(HandsCommand.Tap("пошук"), HandsCommands.parse("натисни на пошук", true))
        assertEquals(HandsCommand.Scroll(true), HandsCommands.parse("прокрути вниз", true))
        assertEquals(HandsCommand.Scroll(false), HandsCommands.parse("прокрути вгору", true))
        assertEquals(HandsCommand.Back, HandsCommands.parse("назад", true))
        assertEquals(HandsCommand.Home, HandsCommands.parse("на головний екран", true))
        assertEquals(HandsCommand.Screen, HandsCommands.parse("що на екрані", true))
        assertEquals(HandsCommand.ComeBack, HandsCommands.parse("повернись", true))
    }

    @Test
    fun sensitiveAppsBlocked() {
        assertTrue(Safety.blocked("ua.privatbank.ap24", "Приват24"))
        assertTrue(Safety.blocked("com.ftband.mono", "monobank"))
        assertTrue(Safety.blocked("com.android.settings", "Налаштування"))
        assertTrue(Safety.blocked("com.x8bit.bitwarden", "Bitwarden"))
        assertTrue(!Safety.blocked("com.viber.voip", "Viber"))
        assertTrue(!Safety.blocked("com.google.android.youtube", "YouTube"))
    }
}
