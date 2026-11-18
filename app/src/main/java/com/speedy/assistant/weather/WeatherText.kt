package com.speedy.assistant.weather

import kotlin.math.roundToInt

data class Place(val name: String, val latitude: Double, val longitude: Double)

data class Day(val code: Int, val maxC: Double, val minC: Double, val rainChance: Int?)

data class Forecast(val nowC: Double, val nowCode: Int, val windKmh: Double, val today: Day, val tomorrow: Day?)

/** Якість повітря зараз: європейський індекс (0 — чисто, 100+ — дуже погано) і дрібний пил, мкг/м³. */
data class AirQuality(val aqi: Int, val pm25: Double?, val pm10: Double?)

/** Що питають: погоду на сьогодні чи на завтра, і де (null — там, де Спіді); [airOnly] — лише про повітря. */
data class WeatherQuery(val tomorrow: Boolean, val place: String?, val airOnly: Boolean = false)

/** Текстова частина погоди — без мережі й Android, щоб перевіряти тестами. */
object WeatherText {

    // "яка погода", "погода в Ужгороді", "чи буде дощ", "що з погодою на завтра", "холодно надворі?"
    private val air = Regex("(?:якість|якістю|забрудн\\p{L}*|чист\\p{L}*|брудн\\p{L}*|яке|як\\s+з)\\s+(?:\\p{L}+\\s+)?повітр|повітр\\p{L}*\\s+(?:чисте|брудне|забруднене)|смог")
    private val weather = Regex("погод\\p{L}*|чи\\s+буде\\s+(дощ|сніг)|прогноз|температур\\p{L}*\\s+(надворі|на\\s+вулиці)|холодно\\s+надворі|тепло\\s+надворі")
    private val place = Regex("(?:\\s|^)(?:у|в|на)\\s+(\\p{L}[\\p{L}'’-]+(?:\\s+\\p{L}[\\p{L}'’-]+)?)")
    private val notPlaces = setOf("завтра", "сьогодні", "вулиці", "дворі", "надворі", "тиждень", "вихідні", "вечір", "ранок", "день", "ніч")

    fun isWeatherQuestion(text: String) = text.lowercase().let { weather.containsMatchIn(it) || air.containsMatchIn(it) }

    fun parse(text: String): WeatherQuery {
        val lower = text.lowercase()
        val tomorrow = "завтра" in lower
        val where = place.findAll(text).map { it.groupValues[1] }
            .map { it.split(' ').filterNot { w -> w.lowercase() in notPlaces }.joinToString(" ") }
            .firstOrNull { it.isNotBlank() && it.lowercase() !in notPlaces }
        val airOnly = air.containsMatchIn(lower) && !weather.containsMatchIn(lower)
        return WeatherQuery(tomorrow, where, airOnly)
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

    /** Температура в родовому відмінку (після "від", "до"). */
    fun degreesFrom(c: Double): String {
        val n = c.roundToInt()
        val abs = kotlin.math.abs(n)
        val word = if (abs % 10 == 1 && abs % 100 != 11) "градуса" else "градусів"
        return degrees(c).substringBeforeLast(' ') + " " + word
    }

    /** Від цього індексу (погане) повітря згадуємо в погоді й розшифровуємо (пил, порада). */
    const val NOTABLE_AQI = 60

    /** Європейський індекс якості повітря — словами. */
    fun airWord(aqi: Int): String = when {
        aqi < 20 -> "чисте"
        aqi < 40 -> "добре"
        aqi < 60 -> "помірне"
        aqi < 80 -> "погане"
        aqi < 100 -> "дуже погане"
        else -> "надзвичайно погане"
    }

    /** Повітря: чисте/добре — одним словом; погане — ще й скільки дрібного пилу і порада. */
    fun airAnswer(a: AirQuality): String {
        val base = "Повітря ${airWord(a.aqi)}"
        val dust = a.pm25?.takeIf { a.aqi >= NOTABLE_AQI }?.roundToInt()?.let { n ->
            val word = when {
                n % 100 in 11..14 -> "мікрограмів"
                n % 10 == 1 -> "мікрограм"
                n % 10 in 2..4 -> "мікрограми"
                else -> "мікрограмів"
            }
            ", дрібного пилу $n $word на кубометр"
        }.orEmpty()
        val advice = when {
            a.aqi >= 80 -> " Краще не гуляти довго й не провітрювати."
            a.aqi >= 60 -> " Довгі прогулянки краще відкласти, особливо дітям."
            else -> ""
        }
        return "$base$dust.$advice"
    }

    /** Коротка відповідь голосом. [placeName] — null, якщо погода там, де Спіді. */
    fun answer(f: Forecast, query: WeatherQuery, placeName: String?, air: AirQuality? = null): String {
        if (query.airOnly) return air?.let { airAnswer(it) } ?: "Не вдалося дізнатися якість повітря."
        val where = placeName?.let { " ($it)" }.orEmpty()
        val day = if (query.tomorrow) f.tomorrow ?: f.today else f.today
        val rain = day.rainChance?.takeIf { it >= 30 }?.let { ", ймовірність опадів $it%" }.orEmpty()
        // Після "від"/"до" — родовий відмінок: "від плюс 4 градусів", "до плюс 1 градуса".
        val range = "від ${degreesFrom(day.minC)} до ${degreesFrom(day.maxC)}"
        return if (query.tomorrow) {
            "Завтра$where ${describe(day.code)}, $range$rain."
        } else {
            "Зараз$where ${degrees(f.nowC)}, ${describe(f.nowCode)}. Сьогодні $range$rain." +
                // Нормальне повітря — не новина; кажемо (з розшифровкою), лише коли погане.
                (air?.takeIf { it.aqi >= NOTABLE_AQI }?.let { " " + airAnswer(it) }.orEmpty())
        }
    }
}
