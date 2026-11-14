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

/** Gemini generateContent REST API. Формат відповіді задається системним промптом + JSON mime type. */
class GeminiClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String,
) : LlmClient {

    override suspend fun complete(system: String, history: List<Turn>): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().apply { history.forEach { put(toJson(it)) } })
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException("Gemini HTTP ${response.code}: ${text.take(300)}")

            val parts = JSONObject(text)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw LlmException("Gemini: порожня відповідь")
            (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
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

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
