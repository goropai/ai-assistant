package com.speedy.assistant.brain

import com.speedy.assistant.face.Mood
import org.json.JSONArray
import org.json.JSONObject

data class AgentAction(
    /** none | look | move | stop | search */
    val type: String = "none",
    /** none | forward (фронтальна, з боку очей) | backward (задня) */
    val camera: String = "none",
    /** none | forward | backward | left | right */
    val direction: String = "none",
    val durationMs: Long = 0,
    /** Після руху зробити новий кадр спереду і продовжити (навігація до мети). */
    val thenLook: Boolean = false,
    /** Для search: що шукати в інтернеті. */
    val query: String = "",
)

/** Структурована відповідь моделі: що сказати, з яким настроєм і що зробити. */
data class AgentReply(
    val say: String,
    val mood: Mood,
    val action: AgentAction,
    /** Факт, який варто запам'ятати надовго ("Тарас любить каву"), або порожньо. */
    val remember: String = "",
) {
    companion object {
        fun parse(raw: String): AgentReply {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return AgentReply(raw.trim(), Mood.NEUTRAL, AgentAction())
            return try {
                val json = JSONObject(raw.substring(start, end + 1))
                val a = json.optJSONObject("action")
                AgentReply(
                    say = json.optString("say"),
                    mood = Mood.parse(json.optString("mood")),
                    action = AgentAction(
                        type = a?.optString("type", "none") ?: "none",
                        camera = normalizeCamera(a?.optString("camera", "none")),
                        direction = a?.optString("direction", "none") ?: "none",
                        durationMs = a?.optLong("duration_ms", 0) ?: 0,
                        thenLook = a?.optBoolean("then_look", false) ?: false,
                        query = a?.optString("query").orEmpty(),
                    ),
                    remember = json.optString("remember"),
                )
            } catch (e: Exception) {
                AgentReply(raw.trim(), Mood.NEUTRAL, AgentAction())
            }
        }

        /** Підтримка старих назв: front/back -> forward/backward. */
        fun normalizeCamera(value: String?): String = when (value?.lowercase()) {
            "forward", "front" -> "forward"
            "backward", "back" -> "backward"
            else -> "none"
        }

        /** JSON Schema для structured outputs (Claude). */
        fun jsonSchema(): JSONObject {
            fun enumOf(vararg values: String) =
                JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))

            val action = JSONObject()
                .put("type", "object")
                .put(
                    "properties", JSONObject()
                        .put("type", enumOf("none", "look", "move", "stop", "search"))
                        .put("camera", enumOf("none", "forward", "backward"))
                        .put("direction", enumOf("none", "forward", "backward", "left", "right"))
                        .put("duration_ms", JSONObject().put("type", "integer"))
                        .put("then_look", JSONObject().put("type", "boolean"))
                        .put("query", JSONObject().put("type", "string"))
                )
                .put("required", JSONArray(listOf("type", "camera", "direction", "duration_ms", "then_look", "query")))
                .put("additionalProperties", false)

            return JSONObject()
                .put("type", "object")
                .put(
                    // Порядок важливий для потокової відповіді: настрій, потім текст, потім дія.
                    "properties", JSONObject()
                        .put("mood", enumOf(*Mood.entries.map { it.wireName }.toTypedArray()))
                        .put("say", JSONObject().put("type", "string"))
                        .put("action", action)
                        .put("remember", JSONObject().put("type", "string"))
                )
                .put("required", JSONArray(listOf("mood", "say", "action", "remember")))
                .put("additionalProperties", false)
        }

        /** Та сама схема у форматі Gemini (responseSchema, OpenAPI-підмножина) з порядком полів. */
        fun geminiSchema(): JSONObject {
            fun str() = JSONObject().put("type", "STRING")
            fun enumOf(vararg values: String) = str().put("enum", JSONArray(values.toList()))
            val actionFields = listOf("type", "camera", "direction", "duration_ms", "then_look", "query")
            val action = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties", JSONObject()
                        .put("type", enumOf("none", "look", "move", "stop", "search"))
                        .put("camera", enumOf("none", "forward", "backward"))
                        .put("direction", enumOf("none", "forward", "backward", "left", "right"))
                        .put("duration_ms", JSONObject().put("type", "INTEGER"))
                        .put("then_look", JSONObject().put("type", "BOOLEAN"))
                        .put("query", str())
                )
                .put("required", JSONArray(actionFields))
                .put("propertyOrdering", JSONArray(actionFields))
            return JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties", JSONObject()
                        .put("mood", enumOf(*Mood.entries.map { it.wireName }.toTypedArray()))
                        .put("say", str())
                        .put("action", action)
                        .put("remember", str())
                )
                .put("required", JSONArray(listOf("mood", "say", "action", "remember")))
                .put("propertyOrdering", JSONArray(listOf("mood", "say", "action", "remember")))
        }
    }
}
