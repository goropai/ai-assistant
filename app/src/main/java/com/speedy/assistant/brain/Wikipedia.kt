package com.speedy.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Запасний пошук, коли пошук моделі недоступний: українська Вікіпедія (без ключа,
 * кілька КБ). Перша знайдена стаття — її короткий опис.
 */
class Wikipedia(private val http: OkHttpClient) {

    suspend fun summary(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val search = "https://uk.wikipedia.org/w/rest.php/v1/search/page".toHttpUrl().newBuilder()
                .addQueryParameter("q", query).addQueryParameter("limit", "1").build()
            val key = get(search.toString())?.optJSONArray("pages")?.optJSONObject(0)?.optString("key")
                ?.takeIf { it.isNotBlank() } ?: return@withContext null
            val page = "https://uk.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl().newBuilder()
                .addPathSegment(key).build()
            get(page.toString())?.optString("extract")?.takeIf { it.isNotBlank() }?.take(MAX_CHARS)
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: String): JSONObject? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "Speedy/1.0 (github.com/goropai/ai-assistant)").build())
            .execute().use { if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null }

    private companion object {
        const val MAX_CHARS = 1200
    }
}
