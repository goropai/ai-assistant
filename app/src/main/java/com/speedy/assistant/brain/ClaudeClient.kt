package com.speedy.assistant.brain

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

/**
 * Claude Messages API напряму через HTTP: офіційний Java SDK не орієнтований на
 * Android 7.1, а OkHttp уже використовується і для Gemini, і для платформи.
 *
 * Економія токенів:
 * - системний промпт кешується (cache_control), а верхньорівневий cache_control кешує
 *   ще й історію — кожен наступний запит розмови платить за неї ~0.1 ціни;
 * - без "роздумів" (thinking between_tools) — відповіді короткі, JSON за схемою;
 * - історія лише дописується ([Brain]), інакше кеш скидається.
 */
class ClaudeClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val effort: String,
) : LlmClient {

    override suspend fun complete(system: String, history: List<Turn>): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put(
                "system", JSONArray().put(
                    JSONObject().put("type", "text").put("text", system)
                        .put("cache_control", JSONObject().put("type", "ephemeral"))
                )
            )
            // Кеш і для історії розмови: точка кешу ставиться на останній блок.
            .put("cache_control", JSONObject().put("type", "ephemeral"))
            .apply {
                // between_tools — найнижчий рівень роздумів; дозволений лише до effort "high".
                if (effort in NO_THINKING_EFFORTS) put("thinking", JSONObject().put("type", "between_tools"))
            }
            .put(
                "output_config", JSONObject()
                    .put("effort", effort)
                    .put("format", JSONObject().put("type", "json_schema").put("schema", AgentReply.jsonSchema()))
            )
            // Якщо класифікатор безпеки відхилить запит — сервер сам повторить його на резервній моделі.
            .put("fallbacks", "default")
            .put("messages", JSONArray().apply { history.forEach { put(toJson(it)) } })

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "server-side-fallback-2026-07-01")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException("Claude HTTP ${response.code}: ${text.take(300)}")

            val json = JSONObject(text)
            if (json.optString("stop_reason") == "refusal") throw LlmException("Claude refusal")
            json.optJSONObject("usage")?.let { u ->
                Log.d(
                    TAG, "tokens: in=${u.optInt("input_tokens")} cache_read=${u.optInt("cache_read_input_tokens")} " +
                        "cache_write=${u.optInt("cache_creation_input_tokens")} out=${u.optInt("output_tokens")}"
                )
            }

            // Відповідь може містити блоки thinking — беремо тільки текст.
            val content = json.optJSONArray("content") ?: JSONArray()
            val texts = (0 until content.length())
                .map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .map { it.optString("text") }
            texts.lastOrNull() ?: throw LlmException("Claude: порожня відповідь")
        }
    }

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=1")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
        try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun toJson(turn: Turn): JSONObject {
        val content = JSONArray()
        // Зображення — перед текстом.
        turn.parts.filterIsInstance<Part.Image>().forEach {
            content.put(
                JSONObject().put("type", "image").put(
                    "source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(it.jpeg, Base64.NO_WRAP))
                )
            )
        }
        turn.parts.filterIsInstance<Part.Text>().forEach {
            content.put(JSONObject().put("type", "text").put("text", it.text))
        }
        return JSONObject()
            .put("role", if (turn.role == Role.USER) "user" else "assistant")
            .put("content", content)
    }

    private companion object {
        const val TAG = "ClaudeClient"
        /** Відповідь — короткий JSON; запас на випадок довшої репліки. */
        const val MAX_TOKENS = 2048
        val NO_THINKING_EFFORTS = setOf("low", "medium", "high")
        val JSON = "application/json".toMediaType()
    }
}
