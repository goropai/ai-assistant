package com.speedy.assistant.weather

import kotlin.math.roundToInt

data class Place(val name: String, val latitude: Double, val longitude: Double)

data class Day(val code: Int, val maxC: Double, val minC: Double, val rainChance: Int?)

data class Forecast(val nowC: Double, val nowCode: Int, val windKmh: Double, val today: Day, val tomorrow: Day?)

/** Що питають: погоду на сьогодні чи на завтра, і де (null — там, де Спіді). */
data class WeatherQuery(val tomorrow: Boolean, val place: String?)

/** Текстова частина погоди — без мережі й Android, щоб перевіряти тестами. */
object WeatherText {

    // "яка погода", "погода в Ужгороді", "чи буде дощ", "що з погодою на завтра", "холодно надворі?"
    private val weather = Regex("погод\\p{L}*|чи\\s+буде\\s+(дощ|сніг)|прогноз|температур\\p{L}*\\s+(надворі|на\\s+вулиці)|холодно\\s+надворі|тепло\\s+надворі")
    private val place = Regex("(?:\\s|^)(?:у|в|на)\\s+(\\p{L}[\\p{L}'’-]+(?:\\s+\\p{L}[\\p{L}'’-]+)?)")
    private val notPlaces = setOf("завтра", "сьогодні", "вулиці", "дворі", "надворі", "тиждень", "вихідні", "вечір", "ранок", "день", "ніч")

    fun isWeatherQuestion(text: String) = weather.containsMatchIn(text.lowercase())

    fun parse(text: String): WeatherQuery {
        val lower = text.lowercase()
        val tomorrow = "завтра" in lower
        val where = place.findAll(text).map { it.groupValues[1] }
            .map { it.split(' ').filterNot { w -> w.lowercase() in notPlaces }.joinToString(" ") }
            .firstOrNull { it.isNotBlank() && it.lowercase() !in notPlaces }
        return WeatherQuery(tomorrow, where)
    }

    /**
     * Розпізнавач пише місто в місцевому відмінку ("в Ужгороді", "у Львові"), а геокодер
     * знає лише називний. Повертає кілька ймовірних форм називного — запитуємо по черзі.
     */
    fun nominativeCandidates(word: String): List<String> {
        val w = word.trim()
        val out = linkedSetOf(w)
        fun base(n: Int) = w.dropLast(n)
        when {
            w.endsWith("єві") -> out += base(3) + "їв" // Києві → Київ
            w.endsWith("ові") -> { out += base(3) + "ів"; out += base(3) + "ово" } // Львові → Львів
            w.endsWith("еві") -> { out += base(3) + "ево"; out += base(3) + "ів" } // Мукачеві → Мукачево
        }
        when {
            w.endsWith("ах") || w.endsWith("ях") -> { out += base(2) + "и"; out += base(2) + "і" } // Черкасах
            w.endsWith("і") -> { out += base(1); out += base(1) + "а"; out += base(1) + "о"; out += base(1) + "я" }
            w.endsWith("у") || w.endsWith("ю") -> out += base(1) // Івано-Франківську
        }
        return out.toList()
    }

    fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "переважно ясно"
        2 -> "мінлива хмарність"
        3 -> "хмарно"
        45, 48 -> "туман"
        51, 53, 55 -> "мряка"
        56, 57 -> "крижана мряка"
        61 -> "невеликий дощ"
        63 -> "дощ"
        65 -> "сильний дощ"
        66, 67 -> "крижаний дощ"
        71 -> "невеликий сніг"
        73 -> "сніг"
        75 -> "сильний сніг"
        77 -> "снігова крупа"
        80, 81 -> "зливи"
        82 -> "сильні зливи"
        85, 86 -> "снігопад"
        95 -> "гроза"
        96, 99 -> "гроза з градом"
        else -> "невідома погода"
    }

    fun degrees(c: Double): String {
        val n = c.roundToInt()
        val sign = if (n > 0) "плюс " else if (n < 0) "мінус " else ""
        val abs = kotlin.math.abs(n)
        val word = when {
            abs % 100 in 11..14 -> "градусів"
            abs % 10 == 1 -> "градус"
            abs % 10 in 2..4 -> "градуси"
            else -> "градусів"
        }
        return "$sign$abs $word"
    }

    /** Коротка відповідь голосом. [placeName] — null, якщо погода там, де Спіді. */
    fun answer(f: Forecast, query: WeatherQuery, placeName: String?): String {
        val where = placeName?.let { " ($it)" }.orEmpty()
        val day = if (query.tomorrow) f.tomorrow ?: f.today else f.today
        val rain = day.rainChance?.takeIf { it >= 30 }?.let { ", ймовірність опадів $it%" }.orEmpty()
        val range = "від ${degrees(day.minC)} до ${degrees(day.maxC)}"
        return if (query.tomorrow) {
            "Завтра$where ${describe(day.code)}, $range$rain."
        } else {
            "Зараз$where ${degrees(f.nowC)}, ${describe(f.nowCode)}. Сьогодні $range$rain."
        }
    }
}
