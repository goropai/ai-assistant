package com.speedy.assistant

import com.speedy.assistant.media.MediaCommands
import com.speedy.assistant.media.OnlineVideoCommands
import com.speedy.assistant.media.OnlineVideoCommands.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineVideoCommandsTest {
    @Test
    fun picksVideoByNumber() {
        // З журналу: людина — "включи перше відео", модель — "увімкни перше відео Hunter x Hunter".
        assertEquals(Command.Pick(0), OnlineVideoCommands.parse("включи перше відео"))
        assertEquals(Command.Pick(0), OnlineVideoCommands.parse("увімкни перше відео Hunter x Hunter"))
        assertEquals(Command.Pick(1), OnlineVideoCommands.parse("Спіді, увімкни друге відео"))
        assertEquals(Command.Pick(2), OnlineVideoCommands.parse("постав третій ролик"))
        assertEquals(Command.Pick(4), OnlineVideoCommands.parse("запусти п'яте відео"))
    }

    @Test
    fun closesVideo() {
        listOf("закрий NewPipe", "ну тоді закрий new pipe".removePrefix("ну тоді "), "закрий відео", "закрий ютуб", "вимкни це відео").forEach {
            assertEquals(it, Command.Close, OnlineVideoCommands.parse(it))
        }
    }

    @Test
    fun leavesOtherMediaCommandsAlone() {
        listOf("увімкни фільм Дюна", "увімкни мультик Шрек", "пауза", "наступна серія", "знайди на ютубі котиків").forEach {
            assertNull(it, OnlineVideoCommands.parse(it))
            assertNotNull(it, MediaCommands.parse(it))
        }
    }
}
