package com.speedy.assistant.news

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Огляд новин на вимогу: пошук свіжих новин (місто, країна, світ, інтереси) і короткий голосовий огляд
 * за важливістю. "Що нового?" без уточнень — це сповіщення телефону, не новини.
 */
object News {
    private val request = Regex(
        "новин|(?:що|які)\\s+(?:нового\\s+)?(?:в|у)\\s+(?:світі|україні|країні|місті)|" +
            "що\\s+(?:відбувається|діється|сталося|трапилось)\\s+(?:в|у)\\s+(?:світі|україні|країні|місті)|" +
            "що\\s+(?:пишуть|кажуть)\\s+(?:в|у)\\s+(?:новинах|інтернеті)",
    )
    /** "Не розповідай новини", "без новин" — не прохання. */
    private val negative = Regex("(?:не|без)\\s+(?:\\p{L}+\\s+)?новин")

    fun isRequest(phrase: String): Boolean {
        val t = phrase.lowercase().replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
        return request.containsMatchIn(t) && !negative.containsMatchIn(t)
    }

    /** Запит для пошуку: сьогоднішні головні новини там, де ми, в Україні, у світі й за інтересами. */
    fun query(place: String?, interests: List<String>, now: Long): String {
        val date = SimpleDateFormat("d MMMM yyyy", Locale("uk", "UA")).format(Date(now))
        return buildString {
            append("Головні новини за $date: ")
            place?.let { append("$it; ") }
            append("Україна; світ")
            if (interests.isNotEmpty()) append("; також новини про: ${interests.take(4).joinToString(", ")}")
            append(". Коротко, з важливістю.")
        }
    }

    /** Повідомлення моделі з результатом пошуку — вона складає огляд голосом. */
    fun digestPrompt(found: String?, place: String?, interests: List<String>): String =
        if (found.isNullOrBlank()) "[система] Попросили огляд новин, але пошук свіжих новин не вдався. Чесно скажи це коротко."
        else """
            [система] Попросили короткий огляд новин. Свіжі результати пошуку:
            $found
            Зроби огляд голосом: 3–5 новин за важливістю — спершу те, що стосується ${place ?: "України"} і України
            (безпека, тривоги, відключення, погода-негода — якщо є), далі найважливіше у світі${if (interests.isNotEmpty()) ", і одна-дві — за інтересами співрозмовника (${interests.take(4).joinToString(", ")})" else ""}.
            Кожна новина — одне коротке речення своїми словами, без посилань, назв сайтів і зайвих чисел.
            Нічого не вигадуй понад результати пошуку. Наприкінці спитай, про що розповісти детальніше.
        """.trimIndent()
}
