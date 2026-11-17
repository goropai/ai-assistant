package com.speedy.assistant.phone

import java.util.Calendar
import java.util.TimeZone

/** Подія для календаря: що, коли; [allDay] — час не сказали. */
data class NewEvent(val title: String, val start: Long, val allDay: Boolean)

/**
 * "Запиши в календар лікаря на п'ятницю о 10", "додай у календар зустріч з Тарасом завтра о 15:30",
 * "створи подію день народження 21 грудня" — без моделі. Що лишилося після дня й часу — назва.
 */
object EventParser {
    private val command = Regex(
        "^(?:запиши|занеси|додай|внеси|постав|створи|запланий)\\p{L}*\\s+(?:мені\\s+)?(?:в|у|до)\\s+календар\\p{L}*\\s+(.+)$" +
            "|^(?:створи|додай|запиши|заплануй)\\s+(?:мені\\s+)?(?:подію|зустріч|нагадування\\s+в\\s+календарі)\\s+(.+)$" +
            "|^(?:запиши|занеси|додай)\\s+(.+?)\\s+(?:в|у|до)\\s+календар\\p{L}*$",
    )
    private val MONTHS = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")
    private val DAYS = listOf("неділю", "понеділок", "вівторок", "середу", "четвер", "п'ятницю", "суботу") // Calendar.SUNDAY = 1
    private val date = Regex("(?:^|\\s)(?:на\\s+)?(\\d{1,2})(?:-?го|-?е)?\\s+(${MONTHS.joinToString("|")})(?=\\s|$)")
    private val relative = Regex("(?:^|\\s)(?:на\\s+)?(сьогодні|завтра|післязавтра)(?=\\s|$)")
    // Апостроф розпізнавач часто пропускає: "пятницю".
    private val weekday = Regex("(?:^|\\s)(?:у|в|на)\\s+(${DAYS.joinToString("|") { it.replace("'", "'?") }})(?=\\s|$)")
    private val time = Regex("(?:^|\\s)(?:о|об|на)\\s+(\\d{1,2})(?:[:.\\s](\\d{2}))?(?:\\s+(?:годині|год))?(?:\\s+(ранку|вечора|дня|ночі))?(?=\\s|$)")

    fun parse(phrase: String, now: Long, zone: TimeZone = TimeZone.getDefault()): NewEvent? {
        val text = phrase.lowercase().replace(Regex("[’ʼ`]"), "'").replace(Regex("[,!?«»\"]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        val m = command.find(text) ?: return null
        var rest = m.groupValues.drop(1).first { it.isNotEmpty() }
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var dayGiven = false

        date.find(rest)?.let { d ->
            val day = d.groupValues[1].toInt()
            val month = MONTHS.indexOf(d.groupValues[2])
            if (day !in 1..31) return null
            val sameYear = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day) }
            cal.set(Calendar.MONTH, month); cal.set(Calendar.DAY_OF_MONTH, day)
            // Дата вже минула цього року — наступного.
            if (sameYear.get(Calendar.DAY_OF_YEAR) < Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)) cal.add(Calendar.YEAR, 1)
            rest = rest.removeRange(d.range)
            dayGiven = true
        }
        relative.find(rest)?.let { r ->
            cal.add(Calendar.DAY_OF_YEAR, when (r.groupValues[1]) { "завтра" -> 1; "післязавтра" -> 2; else -> 0 })
            rest = rest.removeRange(r.range)
            dayGiven = true
        }
        var weekdayGiven = false
        weekday.find(rest)?.let { w ->
            val target = DAYS.indexOfFirst { it.replace("'", "") == w.groupValues[1].replace("'", "") } + 1
            var diff = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (diff == 0) diff = 7 // "у п'ятницю", сказане в п'ятницю, — наступна (уточнимо нижче за часом)
            cal.add(Calendar.DAY_OF_YEAR, diff)
            rest = rest.removeRange(w.range)
            dayGiven = true
            weekdayGiven = diff == 7
        }
        var allDay = true
        time.find(rest)?.let { t ->
            var h = t.groupValues[1].toInt()
            val min = t.groupValues[2].ifEmpty { "0" }.toInt()
            when (t.groupValues[3]) {
                "вечора", "дня" -> if (h in 1..11) h += 12
                "ночі" -> if (h == 12) h = 0
            }
            if (h !in 0..23 || min !in 0..59) return null
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
            allDay = false
            rest = rest.removeRange(t.range)
        }
        if (allDay) { cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0) }
        // Без дня: сьогодні, а якщо цей час уже минув — завтра.
        if (!dayGiven && !allDay && cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        // "У п'ятницю о 18", сказане в п'ятницю о 10, — сьогодні.
        if (weekdayGiven && !allDay && cal.timeInMillis - 7 * 24 * 3600_000L > now) cal.add(Calendar.DAY_OF_YEAR, -7)

        val title = rest.replace(Regex("^(?:про|що|щодо|мені)\\s+"), "").replace(Regex("\\s+"), " ").trim()
            .ifEmpty { "Подія" }.replaceFirstChar { it.uppercase() }
        return NewEvent(title, cal.timeInMillis, allDay)
    }
}
