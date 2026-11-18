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

    override suspend fun complete(system: String, history: List<Turn>): String = stream(system, history) {}

    override suspend fun stream(system: String, history: List<Turn>, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
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
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw LlmException("Claude HTTP ${response.code}: ${text.take(300)}")
            }
            // Server-sent events: message_start (usage), content_block_delta (text_delta — текст;
            // thinking-дельти пропускаємо), message_delta (stop_reason, output usage), error.
            val source = response.body?.source() ?: throw LlmException("Claude: порожня відповідь")
            val full = StringBuilder()
            var inputUsage: JSONObject? = null
            var outputTokens = 0
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val event = JSONObject(line.removePrefix("data:").trim())
                when (event.optString("type")) {
                    "message_start" -> inputUsage = event.optJSONObject("message")?.optJSONObject("usage")
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        if (delta.optString("type") == "text_delta") {
                            val text = delta.optString("text")
                            full.append(text)
                            onDelta(text)
                        }
                    }
                    "message_delta" -> {
                        if (event.optJSONObject("delta")?.optString("stop_reason") == "refusal") throw LlmException("Claude refusal")
                        outputTokens = event.optJSONObject("usage")?.optInt("output_tokens") ?: outputTokens
                    }
                    "error" -> throw LlmException("Claude stream error: ${event.optJSONObject("error")?.optString("message")}")
                }
            }
            inputUsage?.let { u ->
                Log.d(
                    TAG, "tokens: in=${u.optInt("input_tokens")} cache_read=${u.optInt("cache_read_input_tokens")} " +
                        "cache_write=${u.optInt("cache_creation_input_tokens")} out=$outputTokens"
                )
            }
            if (full.isEmpty()) throw LlmException("Claude: порожня відповідь")
            full.toString()
        }
    }

    /** Вбудований веб-пошук Claude (серверний інструмент): звичайний текст, без JSON-формату. */
    override suspend fun search(query: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("output_config", JSONObject().put("effort", "low"))
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 3)))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", searchPrompt(query))))
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "search HTTP ${response.code}: ${text.take(200)}")
                    return@use null
                }
                val content = JSONObject(text).optJSONArray("content") ?: return@use null
                (0 until content.length()).map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("") { it.optString("text") }.trim().ifBlank { null }
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "search: ${e.message}")
            null
        }
    }

    override suspend fun generateJson(system: String, prompt: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("system", system)
            .put("output_config", JSONObject().put("effort", "low"))
            .apply { if (effort in NO_THINKING_EFFORTS) put("thinking", JSONObject().put("type", "between_tools")) }
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use null
                val content = JSONObject(text).optJSONArray("content") ?: return@use null
                (0 until content.length()).map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }.ifBlank { null }
            }
        } catch (e: java.io.IOException) {
            null
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
        // Звуку Claude не приймає — чесна примітка замість нього.
        if (turn.parts.any { it is Part.Audio }) {
            content.put(JSONObject().put("type", "text").put("text", "[звук записано, але ця модель не чує аудіо — скажи про це]"))
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
