package com.speedy.assistant.voice

/**
 * Розпізнає звертання "Спіді" на початку фрази і повертає решту як команду.
 * Розпізнавач мовлення пише ім'я по-різному, тож враховуємо типові варіанти.
 */
object WakeWord {

    private const val NAME = "(?:спі+ді|спіди|спиди|спідді|спідi|спідя|спід|піді|speedy|speedie|speedi)"

    /** "А хто це там сидить, Спіді?" — звертання наприкінці. */
    // Лише після коми: "що там спіді" — про неї, а не до неї.
    private val trailing = Regex("\\s*,\\s*$NAME[\\s,.!?]*$", RegexOption.IGNORE_CASE)

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
            pattern.find(text)?.let { return text.substring(it.range.last + 1).trim() }
            trailing.find(text)?.let { return text.substring(0, it.range.first).trim() }
        }
        return null
    }
}
