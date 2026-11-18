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
    /** Звичайний годинник: стан моделей зберігається між запусками (і перезавантаженнями). */
    private val now: () -> Long = { System.currentTimeMillis() },
) : LlmClient {
    /**
     * Зависання видно по першому шматку: немає даних 6 с (запасні — 5 с) — наступна модель.
     * Поки текст іде, читання не обриваємо; загальна межа — на випадок нескінченного потоку.
     */
    private val firstHttp = http.newBuilder().readTimeout(6, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private val fallbackHttp = http.newBuilder().readTimeout(5, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private val searchHttp = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    override suspend fun complete(system: String, history: List<Turn>): String = stream(system, history) {}

    override suspend fun stream(system: String, history: List<Turn>, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
        refreshModelList()
        // Швидкі відмови (429/404) — частки секунди, тож перебираємо, поки є час, а не фіксовану кількість.
        val order = models.order(model, now(), max = 12)
        val deadline = now() + ATTEMPTS_BUDGET_MS
        var last: Exception? = null
        // Усі моделі відмовили "квота вичерпана" — це ключ вичерпав ліміт (інший ключ може допомогти).
        var quotaOnly = true
        for ((i, candidate) in order.withIndex()) {
            if (i > 0 && now() > deadline) break
            var started = false
            try {
                val text = generate(if (i == 0) firstHttp else fallbackHttp, candidate, system, history) { delta ->
                    started = true
                    onDelta(delta)
                }
                models.succeeded(candidate)
                if (candidate != model) Log.i(TAG, "відповіла запасна модель $candidate")
                return@withContext text
            } catch (e: Exception) {
                // Почали говорити — іншу модель уже не підставиш посеред фрази.
                if (started) throw e
                when (e) {
                    is GeminiHttpException, is IOException -> {}
                    else -> throw e
                }
                handleFailure(candidate, e)
                if (!(e is GeminiHttpException && e.code == 429)) quotaOnly = false
                last = e
            }
        }
        if (quotaOnly && last != null) throw GeminiQuotaException("Gemini: квоту ключа вичерпано (${order.joinToString()})", last)
        throw LlmException("Gemini: жодна модель не відповіла (${order.joinToString()})", last)
    }

    private fun handleFailure(candidate: String, e: Exception) {
        when (e) {
            is GeminiHttpException -> {
                val failure = when (e.code) {
                    429 -> GeminiModels.Failure.RATE_LIMIT
                    404 -> GeminiModels.Failure.NOT_FOUND
                    in 500..599 -> GeminiModels.Failure.UNAVAILABLE
                    // 400 від окремої моделі — вона не підтримує щось із запиту (схему, thinking);
                    // інші моделі можуть упоратись, а цю — надовго в бік.
                    400 -> GeminiModels.Failure.NOT_FOUND
                    // Досягнуто ліміту витрат проєкту (spend cap) / оплата не діє — це "квота ключа":
                    // інший ключ (безкоштовний) може допомогти.
                    403 -> if (SPEND_LIMIT.containsMatchIn(e.message.orEmpty())) throw GeminiQuotaException("Gemini: ліміт витрат ключа", e) else throw e
                    else -> throw e // 401/403 — проблема ключа, інша модель не допоможе
                }
                // Що саме відповів Google (без ключа): "limit: 0", "per day", "spend cap"… — щоб розуміти причину.
                val why = Regex("\"message\"\\s*:\\s*\"([^\"]{0,160})").find(e.message.orEmpty())?.groupValues?.get(1).orEmpty()
                Log.w(TAG, "$candidate: HTTP ${e.code}, пробую іншу модель${if (why.isNotEmpty()) " ($why)" else ""}")
                models.failed(candidate, failure, now())
            }
            else -> {
                Log.w(TAG, "$candidate: ${e.javaClass.simpleName}, пробую іншу модель")
                models.failed(candidate, GeminiModels.Failure.UNAVAILABLE, now())
            }
        }
    }

    private fun generate(
        client: OkHttpClient,
        name: String,
        system: String,
        history: List<Turn>,
        onDelta: (String) -> Unit,
    ): String {
        val generation = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", AgentReply.geminiSchema())
        // Без "роздумів": відповіді короткі, а роздуми оплачуються як вихідні токени.
        if (GeminiModels.canDisableThinking(name)) generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().apply { history.forEach { put(toJson(it)) } })
            .put("generationConfig", generation)

        val request = Request.Builder()
            .url("$BASE/models/$name:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw GeminiHttpException(response.code, "Gemini $name HTTP ${response.code}: ${text.take(300)}")
            }
            // Server-sent events: рядки "data: {…}", у кожному — наступний шматок тексту.
            val source = response.body?.source() ?: throw LlmException("Gemini: порожня відповідь")
            val full = StringBuilder()
            var usage: JSONObject? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val chunk = JSONObject(line.removePrefix("data:").trim())
                chunk.optJSONObject("usageMetadata")?.let { usage = it }
                val parts = chunk.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (i in 0 until parts.length()) {
                    val text = parts.getJSONObject(i).optString("text")
                    if (text.isNotEmpty()) {
                        full.append(text)
                        onDelta(text)
                    }
                }
            }
            usage?.let { u ->
                Log.d(
                    TAG, "$name tokens: in=${u.optInt("promptTokenCount")} cached=${u.optInt("cachedContentTokenCount")} " +
                        "out=${u.optInt("candidatesTokenCount")} thoughts=${u.optInt("thoughtsTokenCount")}"
                )
            }
            if (full.isEmpty()) throw LlmException("Gemini: порожня відповідь")
            return full.toString()
        }
    }

    /** Пошук Google через grounding: звичайний текст (JSON-формат із пошуком не поєднується). */
    override suspend fun search(query: String): String? = withContext(Dispatchers.IO) {
        refreshModelList()
        for (candidate in models.order(model, now(), max = 2)) {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", searchPrompt(query))))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val request = Request.Builder()
                .url("$BASE/models/$candidate:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(JSON))
                .build()
            try {
                searchHttp.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "search $candidate HTTP ${response.code}: ${text.take(200)}")
                        return@use null
                    }
                    val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: return@use null
                    (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim().ifBlank { null }
                }?.let { return@withContext it }
            } catch (e: IOException) {
                Log.w(TAG, "search $candidate: ${e.message}")
            }
        }
        null
    }

    override suspend fun generateJson(system: String, prompt: String): String? = withContext(Dispatchers.IO) {
        refreshModelList()
        for (candidate in models.order(model, now(), max = 4)) {
            val generation = JSONObject().put("responseMimeType", "application/json")
            if (GeminiModels.canDisableThinking(candidate)) generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", generation)
            val request = Request.Builder().url("$BASE/models/$candidate:generateContent")
                .header("x-goog-api-key", apiKey).post(body.toString().toRequestBody(JSON)).build()
            try {
                searchHttp.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "generateJson $candidate HTTP ${response.code}")
                        return@use null
                    }
                    val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: return@use null
                    (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.ifBlank { null }
                }?.let { return@withContext it }
            } catch (e: IOException) {
                Log.w(TAG, "generateJson $candidate: ${e.message}")
            }
        }
        null
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
                is Part.Audio -> parts.put(
                    JSONObject().put(
                        "inline_data", JSONObject()
                            .put("mime_type", "audio/wav")
                            .put("data", Base64.encodeToString(part.wav, Base64.NO_WRAP))
                    )
                )
            }
        }
        return JSONObject()
            .put("role", if (turn.role == Role.USER) "user" else "model")
            .put("parts", parts)
    }

    private class GeminiHttpException(val code: Int, message: String) : LlmException(message)

    /** Ключ вичерпав квоту на всіх моделях — час перемкнутись на інший ключ. */
    class GeminiQuotaException(message: String, cause: Throwable?) : LlmException(message, cause)

    private companion object {
        const val TAG = "GeminiClient"
        const val ATTEMPTS_BUDGET_MS = 20_000L
        /** Відмова через ліміт витрат чи оплату, а не через сам ключ. */
        val SPEND_LIMIT = Regex("(?i)spend|billing|cap|quota|exceeded")
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        val JSON = "application/json".toMediaType()
    }
}
