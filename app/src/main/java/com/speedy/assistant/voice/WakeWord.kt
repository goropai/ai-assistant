package com.speedy.assistant.voice

/**
 * Розпізнає звертання "Спіді" в будь-якому місці фрази і повертає решту як команду.
 * Розпізнавач мовлення пише ім'я по-різному, тож враховуємо типові варіанти.
 */
object WakeWord {

    private const val NAME = "(?:спі+ді|спіди|спиди|спідді|спідi|спідя|спід|піді|speedy|speedie|speedi)"

    /** "А хто це там сидить, Спіді?" — звертання наприкінці. */
    // Лише після коми: "що там спіді" — про неї, а не до неї.
    private val trailing = Regex("\\s*,\\s*$NAME[\\s,.!?]*$", RegexOption.IGNORE_CASE)

    private val LEADING_AND = Regex("^(?:і|ді|и)(?:\\s+|$)", RegexOption.IGNORE_CASE)

    /**
     * Посеред фрази ("так, Спіді, що ти бачиш", "почитай, спіді, книгу") — лише повні форми імені:
     * обрізані "спід", "піді" всередині речення зачепили б інші слова.
     */
    private val middle = Regex("(?<![\\p{L}])(?:спі+ді|спіди|спиди|спідді|speedy|speedie|speedi)(?![\\p{L}])", RegexOption.IGNORE_CASE)

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
            // Офлайн-модель не знає слова "спіді" і пише "спід і …" — сполучник прибираємо.
            pattern.find(text)?.let { return text.substring(it.range.last + 1).trim().replace(LEADING_AND, "") }
            trailing.find(text)?.let { return text.substring(0, it.range.first).trim() }
            // Ім'я посередині — звертання; команда — фраза без нього.
            middle.find(text)?.let { m ->
                return text.removeRange(m.range).replace(Regex("\\s*,\\s*(?=[,.!?]|$)"), "")
                    .replace(Regex("\\s{2,}"), " ").replace(Regex("^[\\s,.!?]+|[\\s,]+$"), "")
            }
        }
        // Дитяча вимова: Google пише "Сенді", "спидин", "співдін" — нечітко, лише на початку фрази.
        for (candidate in candidates) fuzzy(candidate.trim())?.let { return it }
        return null
    }

    private val greeting = setOf("гей", "ей", "хей", "привіт", "привет", "hey")

    /** Перше слово (після привітання) схоже на "спіді": 4–7 літер, на "с"/"ц", ≤ 2 відмінності. */
    private fun fuzzy(text: String): String? {
        val words = text.split(Regex("[\\s,.!?]+")).filter { it.isNotEmpty() }
        val start = if (words.firstOrNull()?.lowercase() in greeting) 1 else 0
        // "и"/"і" розпізнавач у дитячій вимові плутає — зрівнюємо; "-ть" — дієслова ("спить"), не ім'я.
        val word = words.getOrNull(start)?.lowercase()?.replace('и', 'і') ?: return null
        // "-ть" — дієслово ("спить кіт"), але після привітання "привіт, спить" — це Google так чує "Спіді".
        if (word.length !in 4..7 || word[0] !in "сц" || (start == 0 && word.endsWith("ть")) || distance(word, "спіді") > 2) return null
        return words.drop(start + 1).joinToString(" ")
    }

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
