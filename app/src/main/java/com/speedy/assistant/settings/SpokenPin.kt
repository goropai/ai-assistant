package com.speedy.assistant.settings

/**
 * PIN, сказаний уголос: "2 0 4 7", "2047", "два нуль чотири сім", "дві тисячі сорок сім" — ні;
 * лише цифри по одній (розпізнавач зазвичай сам пише їх цифрами). Рівно 4 цифри — інакше null.
 */
object SpokenPin {
    private val WORDS = mapOf(
        "нуль" to "0", "ноль" to "0", "один" to "1", "одна" to "1", "одне" to "1", "два" to "2", "дві" to "2",
        "три" to "3", "чотири" to "4", "п'ять" to "5", "пять" to "5", "шість" to "6", "сім" to "7",
        "вісім" to "8", "дев'ять" to "9", "девять" to "9",
    )
    /** Слова навколо PIN ("мій пін 2047", "код один два три чотири") — пропускаємо. */
    private val FILLER = setOf("пін", "пін-код", "pin", "код", "мій", "це", "ось", "мого", "будь", "ласка", "мене", "звати", "новий", "старий")

    fun parse(phrase: String): String? {
        val words = phrase.lowercase().replace('’', '\'').replace(Regex("[,.!?;:]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val digits = StringBuilder()
        for (w in words) {
            when {
                w in FILLER -> {}
                w.all { it.isDigit() } -> digits.append(w)
                w in WORDS -> digits.append(WORDS.getValue(w))
                else -> return null // інше слово — це не PIN, а звичайна фраза
            }
        }
        return digits.toString().takeIf { Pin.valid(it) }
    }
}
