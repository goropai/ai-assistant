package com.speedy.assistant.night

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Остання ніч: коли слухали, епізоди хропіння і записані уривки (files/night.json).
 * Нова ніч затирає попередню; записи попередніх ночей лишаються на диску.
 */
class NightLog(private val file: File?, private val zone: TimeZone = TimeZone.getDefault()) {
    data class Episode(val start: Long, val end: Long)

    var startedAt = 0L; private set
    var endedAt = 0L; private set
    /** Тека записів цієї ночі. */
    var folder: String = ""; private set
    /** Підсумок уже сказали (вранці — не повторювати). */
    var reported = false; private set
    private val episodes = mutableListOf<Episode>()
    private val clips = mutableListOf<String>()

    init {
        file?.takeIf { it.exists() }?.let {
            try {
                val o = JSONObject(it.readText())
                startedAt = o.optLong("started"); endedAt = o.optLong("ended")
                folder = o.optString("folder"); reported = o.optBoolean("reported")
                o.optJSONArray("episodes")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { e -> episodes += Episode(e.getLong(0), e.getLong(1)) } }
                o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) clips += a.getString(i) }
            } catch (e: Exception) {
                episodes.clear(); clips.clear()
            }
        }
    }

    @Synchronized
    fun begin(now: Long, folder: String) {
        startedAt = now; endedAt = 0; this.folder = folder; reported = false
        episodes.clear(); clips.clear()
        save()
    }

    @Synchronized
    fun episode(start: Long, end: Long) { episodes += Episode(start, end); save() }

    @Synchronized
    fun clip(path: String) { clips += path; save() }

    @Synchronized
    fun end(now: Long) { endedAt = now; save() }

    @Synchronized
    fun markReported() { reported = true; save() }

    @Synchronized
    fun episodes(): List<Episode> = episodes.toList()

    @Synchronized
    fun clips(): List<String> = clips.toList()

    val listened: Boolean get() = startedAt > 0

    /**
     * "Цієї ночі хропіння було 5 разів, разом близько 25 хвилин, найбільше — між третьою й четвертою.
     * Я записала 4 уривки…" (або "не чула хропіння"). null — ще не слухала жодної ночі.
     */
    @Synchronized
    fun summary(): String? {
        if (!listened) return null
        val from = time(startedAt)
        val to = if (endedAt > 0) time(endedAt) else "зараз"
        if (episodes.isEmpty()) return "Цієї ночі я слухала з $from до $to і хропіння не чула."
        val minutes = episodes.sumOf { (it.end - it.start).coerceAtLeast(0) } / 60_000
        val times = episodes.size
        val duration = if (minutes < 1) "менше хвилини" else "близько $minutes ${plural(minutes.toInt(), "хвилини", "хвилин", "хвилин")}"
        val peak = peakHour()
        val clipsText = when (val n = clips.size) {
            0 -> ""
            else -> " Я записала $n ${plural(n, "уривок", "уривки", "уривків")} — скажи «увімкни запис хропіння», щоб послухати."
        }
        return "Цієї ночі хропіння було $times ${plural(times, "раз", "рази", "разів")}, разом $duration" +
            (peak?.let { ", найбільше — між ${hourWord(it)} і ${hourWord((it + 1) % 24)}" } ?: "") + "." + clipsText
    }

    /** Година з найбільшою тривалістю хропіння (якщо епізодів кілька). */
    private fun peakHour(): Int? {
        if (episodes.size < 2) return null
        val byHour = IntArray(24)
        for (e in episodes) {
            var t = e.start
            while (t < e.end) {
                byHour[hourOf(t)] += 1
                t += 60_000
            }
            if (e.end <= e.start) byHour[hourOf(e.start)] += 1
        }
        return byHour.indices.maxByOrNull { byHour[it] }
    }

    private fun hourOf(t: Long) = Calendar.getInstance(zone).apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    private fun time(t: Long) = Calendar.getInstance(zone).apply { timeInMillis = t }.let { "%d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

    private fun save() {
        val f = file ?: return
        f.writeText(
            JSONObject().put("started", startedAt).put("ended", endedAt).put("folder", folder).put("reported", reported)
                .put("episodes", JSONArray(episodes.map { JSONArray().put(it.start).put(it.end) }))
                .put("clips", JSONArray(clips)).toString()
        )
    }

    companion object {
        fun plural(n: Int, one: String, few: String, many: String): String {
            val mod10 = n % 10
            val mod100 = n % 100
            return when {
                mod10 == 1 && mod100 != 11 -> one
                mod10 in 2..4 && mod100 !in 12..14 -> few
                else -> many
            }
        }

        private val HOURS = listOf(
            "північчю", "першою", "другою", "третьою", "четвертою", "п'ятою", "шостою", "сьомою", "восьмою",
            "дев'ятою", "десятою", "одинадцятою", "дванадцятою",
        )

        /** "між третьою й четвертою" — година в орудному відмінку (12-годинна, 0 — північ). */
        fun hourWord(h: Int): String = if (h == 0) HOURS[0] else HOURS[(h - 1) % 12 + 1]
    }
}
