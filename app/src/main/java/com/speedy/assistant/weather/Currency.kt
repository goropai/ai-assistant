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
        fun parse(phrase: String): String? {
            val text = phrase.lowercase()
            if (!question.containsMatchIn(text)) return null
            return names.firstOrNull { (name, _) -> name in text }?.second
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
