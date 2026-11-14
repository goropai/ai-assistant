package com.speedy.assistant.brain

import com.speedy.assistant.face.Mood
import org.json.JSONArray
import org.json.JSONObject

data class AgentAction(
    /** none | look | move | stop */
    val type: String = "none",
    /** none | back | front */
    val camera: String = "none",
    /** none | forward | backward | left | right */
    val direction: String = "none",
    val durationMs: Long = 0,
)

/** Структурована відповідь моделі: що сказати, з яким настроєм і що зробити. */
data class AgentReply(
    val say: String,
    val mood: Mood,
    val action: AgentAction,
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
                        camera = a?.optString("camera", "none") ?: "none",
                        direction = a?.optString("direction", "none") ?: "none",
                        durationMs = a?.optLong("duration_ms", 0) ?: 0,
                    ),
                )
            } catch (e: Exception) {
                AgentReply(raw.trim(), Mood.NEUTRAL, AgentAction())
            }
        }

        /** JSON Schema для structured outputs (Claude). */
        fun jsonSchema(): JSONObject {
            fun enumOf(vararg values: String) =
                JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))

            val action = JSONObject()
                .put("type", "object")
                .put(
                    "properties", JSONObject()
                        .put("type", enumOf("none", "look", "move", "stop"))
                        .put("camera", enumOf("none", "back", "front"))
                        .put("direction", enumOf("none", "forward", "backward", "left", "right"))
                        .put("duration_ms", JSONObject().put("type", "integer"))
                )
                .put("required", JSONArray(listOf("type", "camera", "direction", "duration_ms")))
                .put("additionalProperties", false)

            return JSONObject()
                .put("type", "object")
                .put(
                    "properties", JSONObject()
                        .put("say", JSONObject().put("type", "string"))
                        .put("mood", enumOf(*Mood.entries.map { it.wireName }.toTypedArray()))
                        .put("action", action)
                )
                .put("required", JSONArray(listOf("say", "mood", "action")))
                .put("additionalProperties", false)
        }
    }
}
