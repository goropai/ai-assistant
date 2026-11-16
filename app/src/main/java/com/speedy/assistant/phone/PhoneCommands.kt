package com.speedy.assistant.phone

/** Що попросили зробити на телефоні — розбір фрази без моделі (швидко й без токенів). */
sealed interface PhoneCommand {
    data class OpenApp(val name: String) : PhoneCommand
    data class Alarm(val hour: Int, val minute: Int) : PhoneCommand
    data class Timer(val seconds: Int) : PhoneCommand
    data class ContactNumber(val name: String) : PhoneCommand
    data class Call(val name: String) : PhoneCommand
    data object TodayCalendar : PhoneCommand
}

object PhoneCommands {
    private val openApp = Regex("^(?:відкрий|запусти|увімкни|включи)\\s+(?:мені\\s+)?(?:застосунок\\s+|програму\\s+|додаток\\s+)?(.{2,30})$")
    private val alarm = Regex("(?:будильник\\s+на|розбуди\\s+мене\\s+о|розбуди\\s+мене\\s+на)\\s+(\\d{1,2})(?:\\s*[:.\\s]\\s*(\\d{2}))?")
    private val timer = Regex("таймер\\s+на\\s+(\\d+|одну|одна|дві|два|три|п'ять|десять|півгодини|пів\\s*години)\\s*(секунд\\p{L}*|хвилин\\p{L}*|годин\\p{L}*)?")
    private val contact = Regex("(?:який|яка|який\\s+там)\\s+(?:номер|телефон)\\s+(?:у|в)\\s+(\\p{L}[\\p{L}'’-]*)|^номер\\s+(?:телефону\\s+)?(\\p{L}[\\p{L}'’-]*)$")
    private val call = Regex("^(?:подзвони|набери|зателефонуй)\\s+(?:до\\s+)?(\\p{L}[\\p{L}'’-]*)")
    private val calendar = Regex("календар|(?:які|що)\\s+(?:в\\s+мене\\s+|у\\s+мене\\s+)?(?:плани|справи|зустрічі|події)")

    fun parse(phrase: String): PhoneCommand? {
        val text = phrase.lowercase().replace(Regex("[,!?;]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        alarm.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 0..23 && min in 0..59) return PhoneCommand.Alarm(h, min)
        }
        timer.find(text)?.let { m ->
            val n = when (val v = m.groupValues[1]) {
                "одну", "одна" -> 1; "дві", "два" -> 2; "три" -> 3; "п'ять" -> 5; "десять" -> 10
                "півгодини", "пів години" -> return PhoneCommand.Timer(30 * 60)
                else -> v.replace(" ", "").toIntOrNull() ?: return@let
            }
            val unit = m.groupValues[2]
            val seconds = when {
                unit.startsWith("секунд") -> n
                unit.startsWith("годин") -> n * 3600
                else -> n * 60 // "таймер на 5" — хвилини
            }
            if (seconds in 1..24 * 3600) return PhoneCommand.Timer(seconds)
        }
        contact.find(text)?.let { m -> return PhoneCommand.ContactNumber((m.groupValues[1].ifEmpty { m.groupValues[2] })) }
        call.find(text)?.let { return PhoneCommand.Call(it.groupValues[1]) }
        if (calendar.containsMatchIn(text)) return PhoneCommand.TodayCalendar
        openApp.find(text)?.let { return PhoneCommand.OpenApp(it.groupValues[1].trim()) }
        return null
    }

    /** Кирилиця → латиниця, щоб "ютуб" знаходив "YouTube", а "вайбер" — "Viber". */
    fun translit(text: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e", 'є' to "ye",
            'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "yi", 'й' to "y", 'к' to "k", 'л' to "l",
            'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ь' to "", 'ю' to "yu",
            'я' to "ya", 'ы' to "y", 'э' to "e", 'ё' to "yo", 'ъ' to "",
        )
        return text.lowercase().map { map[it] ?: it.toString() }.joinToString("")
    }

    /** Звести назву до порівнюваного вигляду: лише латинські літери й цифри. */
    fun key(name: String): String = translit(name).filter { it.isLetterOrDigit() }

    /** Наскільки назва застосунку схожа на сказане (0 — ніяк, 1 — точно). */
    fun appScore(spoken: String, label: String): Float {
        val s = key(spoken)
        val l = key(label)
        if (s.isEmpty() || l.isEmpty()) return 0f
        if (s == l) return 1f
        if (l.startsWith(s) || s.startsWith(l)) return 0.9f
        if (s in l || l in s) return 0.75f
        // Англійські назви пишуться не так, як вимовляються: "вайбер"/Viber, "ютуб"/YouTube —
        // порівнюємо кістяк приголосних (vbr = vbr, tb = tb).
        val ss = skeleton(s)
        if (ss.length >= 2 && ss == skeleton(l)) return 0.8f
        val d = levenshtein(s, l)
        return (1f - d.toFloat() / maxOf(s.length, l.length)).coerceAtLeast(0f)
    }

    /** Основа імені з відмінка: "Кості" → "Кост", щоб знайти "Костя" в контактах. */
    fun nameStem(word: String): String {
        val w = word.trim()
        return if (w.length > 3) w.dropLast(1) else w
    }

    private fun skeleton(s: String) = s.filter { it !in "aeiouy" }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
