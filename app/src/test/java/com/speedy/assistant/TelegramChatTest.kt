package com.speedy.assistant

import com.speedy.assistant.guard.Alerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramChatTest {
    @Test
    fun validChat() {
        assertTrue(Alerts.validChat("123456789"))
        assertTrue(Alerts.validChat("-1001234567890")) // група
        assertTrue(Alerts.validChat("@speedy_alerts")) // канал
        // Ім'я бота — не чат (так тривога й не доходила).
        assertFalse(Alerts.validChat("speedy_home_bot"))
        assertFalse(Alerts.validChat(""))
    }

    @Test
    fun chatFromUpdates() {
        assertNull(Alerts.chatFromUpdates("""{"ok":true,"result":[]}"""))
        val updates = """{"ok":true,"result":[
            {"update_id":1,"message":{"chat":{"id":111,"type":"private"},"text":"/start"}},
            {"update_id":2,"my_chat_member":{"chat":{"id":-1002223334445,"type":"supergroup"}}}
        ]}"""
        // Найсвіжіше — додали бота в групу.
        assertEquals("-1002223334445", Alerts.chatFromUpdates(updates))
        assertEquals("111", Alerts.chatFromUpdates("""{"ok":true,"result":[{"update_id":1,"message":{"chat":{"id":111}}}]}"""))
    }
}
