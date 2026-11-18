package com.speedy.assistant.initiative

import org.json.JSONObject

/** Цікавий факт від моделі: тема (для профілю) і сам факт голосом. */
data class Fact(val topic: String, val text: String)

/**
 * Цікаві факти час від часу: модель вибирає під інтереси людини або (іноді) з нової галузі;
 * реакція ("ого, цікаво" / "нудно") — у профіль, і наступні факти точніші.
 */
object Facts {
    const val SYSTEM = "Ти добираєш один короткий цікавий і правдивий факт для домашнього робота, який скаже його вголос. " +
        "Відповідай ЛИШЕ одним JSON-об'єктом без markdown."

    fun prompt(about: String, child: Boolean, liked: List<String>, disliked: List<String>, told: List<String>, explore: Boolean): String = """
        Співрозмовник: ${about.ifBlank { "мало відомо" }}${if (child) " (дитина — простими словами, без страшного)" else ""}.
        Цікавиться: ${liked.joinToString(", ").ifEmpty { "ще невідомо" }}. Не цікаво: ${disliked.joinToString(", ").ifEmpty { "—" }}.
        Уже розказані (не повторюй): ${told.takeLast(15).joinToString(" | ").ifEmpty { "—" }}.
        ${if (explore) "Цього разу — з галузі, про яку ще не говорили (не з тих, що цікавлять чи не цікавлять)." else "Тема — з тих, що цікавлять, або близька до них; якщо інтереси невідомі — будь-яка жвава тема."}
        Формат: {"topic":"тема 1–2 слова українською, малими літерами","fact":"1–2 речення українською, починай природно, без «Цікавий факт:»"}
    """.trimIndent()

    fun parse(text: String): Fact? = try {
        val o = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
        Fact(o.getString("topic").trim().lowercase(), o.getString("fact").trim()).takeIf { it.topic.isNotBlank() && it.text.length in 20..400 }
    } catch (e: Exception) {
        null
    }

    enum class Reaction { LIKED, MORE, DISLIKED }

    private val more = Regex("(?:^|\\s)(?:ще|розкажи\\s+ще|а\\s+ще|ще\\s+один|давай\\s+ще)(?:\\s|$)")
    private val disliked = Regex("нудн|не\\s+цікав|нецікав|не\\s+треба|не\\s+хочу|досить|відчепись|потім|не\\s+зараз|та\\s+ну")
    private val liked = Regex("цікав|ого|вау|клас|круто|прикольн|супер|не\\s+знав|не\\s+знала|справді|серйозно|дивовиж|оце\\s+так|ух\\s+ти|ніфіга|нічого\\s+собі|wow")

    /** Реакція на факт; null — сказали щось інше (тоді це звичайна репліка). */
    fun reaction(text: String): Reaction? {
        val t = text.lowercase().replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            disliked.containsMatchIn(t) -> Reaction.DISLIKED
            more.containsMatchIn(t) -> Reaction.MORE
            liked.containsMatchIn(t) -> Reaction.LIKED
            else -> null
        }
    }
}
