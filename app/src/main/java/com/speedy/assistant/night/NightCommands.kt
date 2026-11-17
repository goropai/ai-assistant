package com.speedy.assistant.night

import java.util.Calendar
import java.util.TimeZone

/** Голосові команди нічного режиму — розбір без моделі. */
sealed interface NightCommand {
    /** "послухай уночі, чи я хроплю" — [untilHour] з "до сьомої" (null — до 7:00). */
    data class Start(val untilHour: Int?) : NightCommand
    /** "чи я хропів?", "що ти чула вночі?" */
    data object Report : NightCommand
    /** "увімкни запис хропіння" */
    data object Play : NightCommand
}

object NightCommands {
    private val start = Regex(
        "(?:послухай|слухай|послухаєш|послухати|слідкуй|стеж|перевір|запиши|записуй|подивись)\\p{L}*\\s.*хроп\\p{L}*" +
            "|^(?:увімкни|включи|ввімкни|почни|давай)\\s+нічн\\p{L}*\\s+режим" +
            "|^(?:послухай|слухай)\\s+(?:мене\\s+|як\\s+я\\s+сплю\\s*)?(?:уночі|вночі|цієї\\s+ночі|сьогодні\\s+вночі)",
    )
    private val report = Regex(
        "^(?:чи|я|а)?\\s*(?:я\\s+|він\\s+|вона\\s+|хтось\\s+|ми\\s+)?хроп\\p{L}*(?:\\s+(?:вночі|уночі|цієї\\s+ночі|сьогодні))?$" +
            "|^(?:що|кого)\\s+ти\\s+(?:чула|почула|записала)\\s+(?:вночі|уночі|за\\s+ніч|цієї\\s+ночі)" +
            "|^як\\s+(?:я\\s+)?(?:спав|спала|ми\\s+спали)|^скільки\\s+(?:я\\s+|він\\s+|вона\\s+)?хроп\\p{L}*",
    )
    private val play = Regex("^(?:увімкни|включи|постав|відтвори|дай\\s+послухати|покажи|хочу\\s+послухати)\\s+(?:мені\\s+)?(?:запис\\p{L}*\\s+)?хроп\\p{L}*")
    private val untilHour = Regex("до\\s+(\\d{1,2}|першої|другої|третьої|четвертої|п'ятої|шостої|сьомої|восьмої|дев'ятої|десятої)")
    private val HOURS = listOf("першої", "другої", "третьої", "четвертої", "п'ятої", "шостої", "сьомої", "восьмої", "дев'ятої", "десятої")

    fun parse(phrase: String): NightCommand? {
        val t = phrase.lowercase().replace('’', '\'').replace(Regex("[,!?«»\"]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (play.containsMatchIn(t)) return NightCommand.Play
        if (report.containsMatchIn(t)) return NightCommand.Report
        if (start.containsMatchIn(t)) {
            val h = untilHour.find(t)?.groupValues?.get(1)?.let { it.toIntOrNull() ?: (HOURS.indexOf(it) + 1) }
            return NightCommand.Start(h?.takeIf { it in 1..12 })
        }
        return null
    }

    /** Найближча [hour]:00 після [now] (ранок: 1–12). */
    fun nextTime(hour: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
}
