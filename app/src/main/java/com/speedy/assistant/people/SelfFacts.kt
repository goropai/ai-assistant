package com.speedy.assistant.people

/**
 * Що людина каже про себе — розбір без моделі: "я люблю каву" → "любить каву".
 * Короткі фрази-твердження Спіді лише запам'ятовує й відповідає сама (без токенів);
 * довші — запам'ятовує і все одно передає моделі як звичайну репліку.
 */
object SelfFacts {
    private val rules = listOf(
        Regex("^я\\s+(?:дуже\\s+)?люблю\\s+(.{2,60})$") to { m: MatchResult -> "любить ${m.groupValues[1]}" },
        Regex("^я\\s+не\\s+люблю\\s+(.{2,60})$") to { m: MatchResult -> "не любить ${m.groupValues[1]}" },
        Regex("^мені\\s+(?:дуже\\s+)?подобаю?ться\\s+(.{2,60})$") to { m: MatchResult -> "любить ${m.groupValues[1]}" },
        Regex("^мені\\s+не\\s+подобаю?ться\\s+(.{2,60})$") to { m: MatchResult -> "не любить ${m.groupValues[1]}" },
        Regex("^(?:мій|моя|моє|мої)\\s+улюблен\\p{L}*\\s+(\\p{L}+)\\s+(?:це\\s+|—\\s*|-\\s*)?(.{2,60})$") to
            { m: MatchResult -> "улюблений ${m.groupValues[1]} — ${m.groupValues[2]}" },
        Regex("^я\\s+працюю\\s+(.{2,60})$") to { m: MatchResult -> "працює ${m.groupValues[1]}" },
        Regex("^я\\s+вчуся\\s+(.{2,60})$") to { m: MatchResult -> "вчиться ${m.groupValues[1]}" },
        Regex("^я\\s+живу\\s+(.{2,60})$") to { m: MatchResult -> "живе ${m.groupValues[1]}" },
        Regex("^я\\s+граю\\s+(.{2,60})$") to { m: MatchResult -> "грає ${m.groupValues[1]}" },
        // "у мене є собака" → "є собака" (Спіді вимовить "у тебе є собака").
        Regex("^[ув]\\s+мене\\s+є\\s+(.{2,60})$") to { m: MatchResult -> "є ${m.groupValues[1]}" },
    )

    /** Факт у третій особі або null — це не розповідь про себе. */
    fun extract(phrase: String): String? {
        val text = phrase.lowercase().replace(Regex("[!?;]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', ',')
        for ((regex, make) in rules) regex.find(text)?.let { return make(it).trim() }
        return null
    }

    /** Коротка фраза, що лише повідомляє факт, — відповідаємо самі, модель не потрібна. */
    fun isPlainStatement(phrase: String): Boolean = extract(phrase) != null && phrase.split(' ').size <= 8 && '?' !in phrase
}
