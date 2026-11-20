package com.speedy.assistant.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import kotlin.math.roundToInt

/** Офіційний курс НБУ: безкоштовно, без ключа, без моделі. Кеш — до наступної години. */
class Currency(private val http: OkHttpClient) {
    private var cached: Pair<Long, Map<String, Pair<Double, String>>>? = null

    /** @return курс (грн за 1 одиницю) і дата, або null — такої валюти немає. */
    suspend fun rate(code: String, now: Long = System.currentTimeMillis()): Pair<Double, String>? {
        val rates = cached?.takeIf { now - it.first < 3600_000 }?.second ?: load().also { cached = now to it }
        return rates[code]
    }

    /** Курс за кожен робочий день періоду: дата ("dd.MM.yyyy") → грн за 1 одиницю, від старішого. */
    suspend fun history(code: String, days: Int, now: Long = System.currentTimeMillis()): List<Pair<String, Double>> = withContext(Dispatchers.IO) {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val start = fmt.format(java.util.Date(now - days * 86_400_000L))
        val end = fmt.format(java.util.Date(now))
        val url = "https://bank.gov.ua/NBU_Exchange/exchange_site?start=$start&end=$end&valcode=${code.lowercase()}&sort=exchangedate&order=asc&json"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("НБУ HTTP ${response.code}")
            val arr = JSONArray(response.body?.string().orEmpty())
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { it.getString("exchangedate") to it.optDouble("rate_per_unit", it.getDouble("rate")) }
        }
    }

    private suspend fun load(): Map<String, Pair<Double, String>> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json").build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("НБУ HTTP ${response.code}")
                val arr = JSONArray(response.body?.string().orEmpty())
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .associate { it.getString("cc") to (it.getDouble("rate") to it.getString("exchangedate")) }
            }
    }

    companion object {
        /** Як валюту називають голосом → код ISO. */
        private val names = listOf(
            "долар" to "USD", "бакс" to "USD", "євро" to "EUR", "злот" to "PLN", "фунт" to "GBP",
            "франк" to "CHF", "форинт" to "HUF", "юан" to "CNY", "єн" to "JPY", "крон" to "CZK",
        )
        private val question = Regex("курс|скільки\\s+(?:зараз\\s+)?(?:коштує|стоїть)\\s+(?:один\\s+)?(?:долар|євро|злот|фунт)")

        /** Код валюти з питання ("який курс долара?") або null — це не питання про курс. */
        fun parse(phrase: String): String? = query(phrase)?.code

        /** Питання про курс: [days] null — сьогоднішній, інакше як змінювався за стільки днів. */
        data class Query(val code: String, val days: Int?)

        private val trendWords = Regex(
            "змінюв|змінив|динамік|тренд|протягом|на\\s+протязі|за\\s+(?:останн\\p{L}*\\s+)?(?:\\d+\\s+)?(?:тижн|тиждень|місяц|рік|року|дн|півроку)|" +
                "(?:тиждень|місяць|рік)\\s+тому|минул\\p{L}*\\s+(?:тижн|тиждень|місяц|рік)",
        )

        fun query(phrase: String): Query? {
            val text = phrase.lowercase()
            if (!question.containsMatchIn(text)) return null
            val code = names.firstOrNull { (name, _) -> name in text }?.second ?: return null
            if (!trendWords.containsMatchIn(text)) return Query(code, null)
            val n = Regex("(\\d+)\\s+(дн|тижн|місяц)").find(text)
            val days = when {
                n != null -> n.groupValues[1].toInt() * when (n.groupValues[2]) { "тижн" -> 7; "місяц" -> 30; else -> 1 }
                "півроку" in text -> 182
                Regex("рік|року").containsMatchIn(text) -> 365
                Regex("тиждень|тижн").containsMatchIn(text) -> 7
                else -> 30 // "як змінювався курс" — за місяць
            }
            return Query(code, days.coerceIn(2, 365))
        }

        /** Родовий відмінок для "курс …". */
        val genitive = mapOf(
            "USD" to "долара", "EUR" to "євро", "PLN" to "злотого", "GBP" to "фунта", "CHF" to "франка",
            "HUF" to "форинта", "CNY" to "юаня", "JPY" to "єни", "CZK" to "крони",
        )

        /** "Тиждень тому", "Місяць тому", "Рік тому", "12 днів тому". */
        private fun ago(days: Int) = when (days) {
            7 -> "Тиждень тому"
            30 -> "Місяць тому"
            182 -> "Пів року тому"
            365 -> "Рік тому"
            else -> "${plural(days, "день", "дні", "днів")} тому"
        }

        private fun period(days: Int) = when (days) {
            7 -> "за тиждень"
            30 -> "за місяць"
            182 -> "за пів року"
            365 -> "за рік"
            else -> "за ${plural(days, "день", "дні", "днів")}"
        }

        /**
         * Як змінювався курс за [days] днів ([points] — від старішого): тоді й зараз, на скільки, найвищий і найнижчий.
         * Без прийменників перед сумами — TTS сам відмінює числа, а "з 50 гривень 53 копійок" звучить криво.
         */
        fun trend(code: String, days: Int, points: List<Pair<String, Double>>): String? {
            if (points.size < 2) return null
            val name = genitive[code] ?: code
            val (_, first) = points.first()
            val (lastDate, last) = points.last()
            val diff = last - first
            val change = when {
                kotlin.math.abs(diff) < 0.005 -> "курс майже не змінився"
                else -> {
                    val pct = kotlin.math.abs(diff) / first * 100
                    val pctText = if (pct < 1) "менш як на відсоток" else "на ${plural(pct.roundToInt(), "відсоток", "відсотки", "відсотків")}"
                    "курс ${if (diff > 0) "зріс" else "знизився"} на ${accusative(kotlin.math.abs(diff))} ($pctText)"
                }
            }
            val max = points.maxBy { it.second }
            val min = points.minBy { it.second }
            val extremes = if (max.second - min.second < 0.01) "" else
                " Найвищий — ${day(max.first)}: ${spoken(max.second)}, найнижчий — ${day(min.first)}: ${spoken(min.second)}."
            return "${ago(days)} курс $name був ${spoken(first)}, на ${day(lastDate)} — ${spoken(last)}: " +
                "${period(days)} $change.$extremes"
        }

        /** "20.10.2026" → "20 жовтня". */
        fun day(date: String): String {
            val parts = date.split('.')
            val month = parts.getOrNull(1)?.toIntOrNull() ?: return date
            val months = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")
            return "${parts[0].trimStart('0')} ${months.getOrElse(month - 1) { "" }}".trim()
        }

        /** Знахідний: "на 1 гривню 2 копійки", "на 63 копійки". */
        fun accusative(amount: Double): String {
            val kop = (amount * 100).roundToInt()
            val uah = kop / 100
            val k = kop % 100
            val parts = mutableListOf<String>()
            if (uah > 0) parts += plural(uah, "гривню", "гривні", "гривень")
            if (k > 0 || uah == 0) parts += plural(k, "копійку", "копійки", "копійок")
            return parts.joinToString(" ")
        }

        /** 45.4274 → "45 гривень 43 копійки". */
        fun spoken(rate: Double): String {
            val kop = (rate * 100).roundToInt()
            val uah = kop / 100
            val k = kop % 100
            return "${plural(uah, "гривня", "гривні", "гривень")} ${plural(k, "копійка", "копійки", "копійок")}"
        }

        private fun plural(n: Int, one: String, few: String, many: String): String {
            val w = when {
                n % 100 in 11..14 -> many
                n % 10 == 1 -> one
                n % 10 in 2..4 -> few
                else -> many
            }
            return "$n $w"
        }
    }
}
