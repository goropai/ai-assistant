package com.speedy.assistant.brain

import android.util.Base64
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
            .put("max_tokens", 16000)
            .put("system", system)
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

            // Відповідь може містити блоки thinking — беремо тільки текст.
            val content = json.optJSONArray("content") ?: JSONArray()
            val texts = (0 until content.length())
                .map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .map { it.optString("text") }
            texts.lastOrNull() ?: throw LlmException("Claude: порожня відповідь")
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
        val JSON = "application/json".toMediaType()
    }
}
