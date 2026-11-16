package com.speedy.assistant.brain

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini generateContent REST API. Формат відповіді задається системним промптом + JSON mime type.
 * Якщо модель не відповідає (квота, перевантаження, таймаут, модель прибрали) — пробуємо
 * наступну з [GeminiModels]: у кожної моделі окремі ліміти.
 */
class GeminiClient(
    http: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val models: GeminiModels,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : LlmClient {
    /** Перша спроба — довша, запасні — коротші, щоб уся спроба не тривала хвилину. */
    private val firstHttp = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val fallbackHttp = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun complete(system: String, history: List<Turn>): String = withContext(Dispatchers.IO) {
        refreshModelList()
        val order = models.order(model, now())
        var last: Exception? = null
        for ((i, candidate) in order.withIndex()) {
            try {
                val text = generate(if (i == 0) firstHttp else fallbackHttp, candidate, system, history)
                models.succeeded(candidate)
                if (candidate != model) Log.i(TAG, "відповіла запасна модель $candidate")
                return@withContext text
            } catch (e: GeminiHttpException) {
                val failure = when (e.code) {
                    429 -> GeminiModels.Failure.RATE_LIMIT
                    404 -> GeminiModels.Failure.NOT_FOUND
                    in 500..599 -> GeminiModels.Failure.UNAVAILABLE
                    else -> throw e // 400/401/403 — проблема запиту чи ключа, інша модель не допоможе
                }
                Log.w(TAG, "$candidate: HTTP ${e.code}, пробую іншу модель")
                models.failed(candidate, failure, now())
                last = e
            } catch (e: IOException) {
                Log.w(TAG, "$candidate: ${e.javaClass.simpleName}, пробую іншу модель")
                models.failed(candidate, GeminiModels.Failure.UNAVAILABLE, now())
                last = e
            }
        }
        throw LlmException("Gemini: жодна модель не відповіла (${order.joinToString()})", last)
    }

    private fun generate(client: OkHttpClient, name: String, system: String, history: List<Turn>): String {
        val generation = JSONObject().put("responseMimeType", "application/json")
        // Без "роздумів": відповіді короткі, а роздуми оплачуються як вихідні токени.
        if (GeminiModels.canDisableThinking(name)) generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().apply { history.forEach { put(toJson(it)) } })
            .put("generationConfig", generation)

        val request = Request.Builder()
            .url("$BASE/models/$name:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw GeminiHttpException(response.code, "Gemini $name HTTP ${response.code}: ${text.take(300)}")

            val json = JSONObject(text)
            json.optJSONObject("usageMetadata")?.let { u ->
                Log.d(
                    TAG, "$name tokens: in=${u.optInt("promptTokenCount")} cached=${u.optInt("cachedContentTokenCount")} " +
                        "out=${u.optInt("candidatesTokenCount")} thoughts=${u.optInt("thoughtsTokenCount")}"
                )
            }
            val parts = json
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw LlmException("Gemini: порожня відповідь")
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        }
    }

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            fallbackHttp.newCall(Request.Builder().url("$BASE/models?pageSize=1").header("x-goog-api-key", apiKey).build())
                .execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    /** Які моделі доступні цьому ключу (раз на 12 год; невдача — не страшно, лишається налаштована). */
    private fun refreshModelList() {
        if (now() - models.listedAt < GeminiModels.LIST_TTL_MS) return
        try {
            val names = mutableListOf<String>()
            var page: String? = null
            do {
                val url = "$BASE/models?pageSize=200" + (page?.let { "&pageToken=$it" } ?: "")
                val json = fallbackHttp.newCall(Request.Builder().url(url).header("x-goog-api-key", apiKey).build())
                    .execute().use { if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null } ?: break
                val list = json.optJSONArray("models") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val m = list.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    if ("generateContent" in methods) names += m.optString("name").removePrefix("models/")
                }
                page = json.optString("nextPageToken").ifBlank { null }
            } while (page != null)
            models.setAvailable(names, now())
            Log.i(TAG, "доступні моделі: ${models.available}")
        } catch (e: Exception) {
            Log.w(TAG, "ListModels failed: ${e.message}")
            models.setAvailable(models.available, now() - GeminiModels.LIST_TTL_MS + 10 * 60_000) // повтор за 10 хв
        }
    }

    private fun toJson(turn: Turn): JSONObject {
        val parts = JSONArray()
        turn.parts.forEach { part ->
            when (part) {
                is Part.Text -> parts.put(JSONObject().put("text", part.text))
                is Part.Image -> parts.put(
                    JSONObject().put(
                        "inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(part.jpeg, Base64.NO_WRAP))
                    )
                )
            }
        }
        return JSONObject()
            .put("role", if (turn.role == Role.USER) "user" else "model")
            .put("parts", parts)
    }

    private class GeminiHttpException(val code: Int, message: String) : LlmException(message)

    private companion object {
        const val TAG = "GeminiClient"
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        val JSON = "application/json".toMediaType()
    }
}
