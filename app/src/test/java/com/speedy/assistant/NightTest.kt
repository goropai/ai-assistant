package com.speedy.assistant

import com.speedy.assistant.night.NightCommand
import com.speedy.assistant.night.NightCommands
import com.speedy.assistant.night.NightLog
import com.speedy.assistant.night.SnoreEpisodes
import com.speedy.assistant.night.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class NightTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(h: Int, m: Int = 0, s: Int = 0) = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.NOVEMBER, 18, h, m, s)
    }.timeInMillis

    /** Кадр кожні пів секунди; хропіння — 1 с звуку на вдиху кожні 4 с. */
    private fun snoring(from: Long, seconds: Int) = (0 until seconds * 2).map { i ->
        val t = from + i * 500L
        t to if ((i * 500L) % 4000 < 1000) 0.8f else 0.05f
    }

    @Test
    fun rhythmicSnoringIsAnEpisodeSingleSoundIsNot() {
        val e = SnoreEpisodes()
        // Кашель — один гучний кадр: не хропіння.
        assertNull(e.feed(at(2), 0.9f))
        (1..60).forEach { assertNull(e.feed(at(2) + it * 500L, 0.05f)) }
        // Ритмічне хропіння — початок на третьому позитивному кадрі.
        val events = (snoring(at(3), 60) + (1..60).map { at(3, 1) + it * 500L to 0.02f }).mapNotNull { (t, s) -> e.feed(t, s) }
        assertEquals(2, events.size)
        val started = events[0] as SnoreEpisodes.Event.Started
        val ended = events[1] as SnoreEpisodes.Event.Ended
        assertEquals(at(3), started.since)
        assertTrue(ended.end - ended.start in 50_000..60_000)
    }

    @Test
    fun nightSummaryInWords() {
        val file = File(tmp.root, "night.json")
        val log = NightLog(file, utc)
        assertNull(log.summary())
        log.begin(at(23) - 24 * 3600_000L, "/x") // з 23:00 попереднього дня
        assertTrue(log.summary()!!.contains("хропіння не чула"))
        log.episode(at(3, 10), at(3, 30))
        log.episode(at(3, 40), at(3, 45))
        log.episode(at(5, 0), at(5, 2))
        log.clip("/x/03-10-00.wav"); log.clip("/x/03-40-00.wav")
        log.end(at(7))
        val text = NightLog(file, utc).summary()!! // переживає перезапуск
        assertEquals(
            "Цієї ночі хропіння було 3 рази, разом близько 27 хвилин, найбільше — між третьою і четвертою. " +
                "Я записала 2 уривки — скажи «увімкни запис хропіння», щоб послухати.",
            text,
        )
    }

    @Test
    fun commands() {
        assertEquals(NightCommand.Start(null), NightCommands.parse("Спіді, послухай уночі, чи я хроплю"))
        assertEquals(NightCommand.Start(6), NightCommands.parse("послухай чи я хроплю до шостої"))
        assertEquals(NightCommand.Start(null), NightCommands.parse("увімкни нічний режим"))
        assertEquals(NightCommand.Start(null), NightCommands.parse("запиши, якщо я хропітиму"))
        assertEquals(NightCommand.Report, NightCommands.parse("чи я хропів?"))
        assertEquals(NightCommand.Report, NightCommands.parse("що ти чула вночі"))
        assertEquals(NightCommand.Report, NightCommands.parse("скільки я хропів"))
        assertEquals(NightCommand.Play, NightCommands.parse("увімкни запис хропіння"))
        assertNull(NightCommands.parse("увімкни фільм"))
        assertNull(NightCommands.parse("послухай пісню"))
    }

    @Test
    fun wakeTimeIsNextMorning() {
        assertEquals(at(7), NightCommands.nextTime(7, at(2), utc)) // о 2:00 — цього ж ранку
        assertEquals(at(7) + 24 * 3600_000L, NightCommands.nextTime(7, at(23), utc)) // о 23:00 — наступного
    }

    @Test
    fun wavHeader() {
        val f = File(tmp.root, "a.wav")
        WavWriter(f, 16_000).use { it.write(ShortArray(16_000) { (it % 100).toShort() }) }
        val b = f.readBytes()
        assertEquals(44 + 32_000, b.size)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4)); assertEquals("data", String(b, 36, 4))
        val dataSize = (b[40].toInt() and 0xff) or ((b[41].toInt() and 0xff) shl 8) or ((b[42].toInt() and 0xff) shl 16)
        assertEquals(32_000, dataSize)
    }
}
