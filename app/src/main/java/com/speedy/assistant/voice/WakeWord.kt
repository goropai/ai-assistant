package com.speedy.assistant.voice

/**
 * Розпізнає звертання "Спіді" на початку фрази і повертає решту як команду.
 * Розпізнавач мовлення пише ім'я по-різному, тож враховуємо типові варіанти.
 */
object WakeWord {

    private val pattern = Regex(
        "^(?:(?:гей|ей|хей|привіт|привет|hey)[\\s,!]+)?" +
            // "піді" — початок слова обрізався, поки вмикався розпізнавач.
            "(?:спі+ді|спіди|спиди|спідді|спідi|спідя|спід|піді|speedy|speedie|speedi)" +
            "(?=[\\s,.!?]|$)[\\s,.!?]*",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @return null — звертання немає; "" — назвали тільки ім'я; інакше текст команди.
     */
    fun extractCommand(candidates: List<String>): String? {
        for (candidate in candidates) {
            val text = candidate.trim()
            val match = pattern.find(text) ?: continue
            return text.substring(match.range.last + 1).trim()
        }
        return null
    }
}
