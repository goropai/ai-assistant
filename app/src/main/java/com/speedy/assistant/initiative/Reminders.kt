package com.speedy.assistant.initiative

import com.speedy.assistant.util.writeTextAtomic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** Нагадування: коли, що і для кого. Лише на телефоні (files/reminders.json). */
data class Reminder(val at: Long, val text: String, val forName: String? = null)

class Reminders(private val file: File?) {
    private val items = mutableListOf<Reminder>()

    init {
        file?.takeIf { it.exists() }?.let {
            try {
                val arr = JSONArray(it.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items += Reminder(o.getLong("at"), o.getString("text"), o.optString("for").ifBlank { null })
                }
            } catch (e: Exception) {
                items.clear()
            }
        }
    }

    @Synchronized
    fun add(r: Reminder) {
        items += r
        items.sortBy { it.at }
        save()
    }

    /** Ті, чий час настав (і забираємо їх зі списку). */
    @Synchronized
    fun takeDue(now: Long): List<Reminder> {
        val due = items.filter { it.at <= now }
        if (due.isNotEmpty()) {
            items.removeAll(due)
            save()
        }
        return due
    }

    @Synchronized
    fun upcoming(): List<Reminder> = items.toList()

    @Synchronized
    fun clear(): Int = items.size.also { items.clear(); save() }

    private fun save() {
        val f = file ?: return
        f.writeTextAtomic(JSONArray(items.map { JSONObject().put("at", it.at).put("text", it.text).put("for", it.forName.orEmpty()) }).toString())
    }
}

/** Розбір "нагадай о 18:00 подзвонити мамі" / "через 20 хвилин" / "завтра о 9" — без моделі. */
object ReminderParser {
    private val atTime = Regex("^нагадай(?:\\s+мені)?(?:\\s+(сьогодні|завтра))?\\s+(?:о|об|на)\\s+(\\d{1,2})(?:[:.\\s](\\d{2}))?(?:\\s+годин\\p{L}*)?(?:\\s+(завтра|сьогодні))?\\s+(.+)$")
    private val inTime = Regex("^нагадай(?:\\s+мені)?\\s+через\\s+(\\d+|одну|одна|дві|два|три|п'ять|десять|пів|півгодини|годину|хвилину)?\\s*(секунд\\p{L}*|хвилин\\p{L}*|годин\\p{L}*)?\\s+(.+)$")
    private val filler = Regex("^(?:що|про\\s+те\\s+що|про|щоб|мені)\\s+")

    fun parse(phrase: String, now: Long, zone: TimeZone = TimeZone.getDefault()): Reminder? {
        val text = phrase.lowercase().replace(Regex("[,!?]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        atTime.find(text)?.let { m ->
            val h = m.groupValues[2].toInt()
            val min = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h !in 0..23 || min !in 0..59) return null
            val tomorrow = m.groupValues[1] == "завтра" || m.groupValues[4] == "завтра"
            val cal = Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (tomorrow) add(Calendar.DAY_OF_YEAR, 1)
            }
            if (!tomorrow && cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1) // уже минуло — на завтра
            return Reminder(cal.timeInMillis, clean(m.groupValues[5]))
        }
        inTime.find(text)?.let { m ->
            val amount = m.groupValues[1]
            val unit = m.groupValues[2]
            val seconds = when (amount) {
                "півгодини" -> 1800
                "годину" -> 3600
                "хвилину" -> 60
                "пів" -> if (unit.startsWith("годин")) 1800 else 30
                else -> {
                    val n = when (amount) {
                        "одну", "одна" -> 1; "дві", "два" -> 2; "три" -> 3; "п'ять" -> 5; "десять" -> 10
                        "" -> return null
                        else -> amount.toIntOrNull() ?: return null
                    }
                    when {
                        unit.startsWith("секунд") -> n
                        unit.startsWith("годин") -> n * 3600
                        else -> n * 60
                    }
                }
            }
            if (seconds !in 1..7 * 24 * 3600) return null
            return Reminder(now + seconds * 1000L, clean(m.groupValues[3]))
        }
        return null
    }

    private fun clean(s: String) = s.trim().replace(filler, "").ifBlank { "те, що ти просив" }
}
