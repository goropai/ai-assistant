package com.speedy.assistant.people

/** Стать — для правильного роду ("рада тебе бачити, Ліно", "ти схожа"). */
enum class Gender {
    FEMALE, MALE, UNKNOWN;

    companion object {
        /** Чоловічі імена на -а/-я (решта імен на -а/-я — жіночі). */
        private val MALE_A = setOf("микола", "ілля", "кузьма", "сава", "сава", "лука", "хома", "фома", "никита", "микита", "мирослав", "данила", "гаврила")

        /** Вгадати з імені: українські жіночі імена здебільшого на -а/-я. Не впевнені — UNKNOWN. */
        fun guess(name: String): Gender {
            val n = name.lowercase().trim()
            if (n.length < 2) return UNKNOWN
            if (n in MALE_A) return MALE
            return when (n.last()) {
                'а', 'я' -> FEMALE
                'й', 'р', 'л', 'с', 'н', 'м', 'в', 'д', 'п', 'т', 'к', 'г', 'б', 'ф', 'х', 'ч', 'ш' -> MALE
                else -> UNKNOWN // "Барсик"-подібні, імена на -о (Петро — чол., Марго — жін.)
            }
        }
    }
}

/** Що людина каже про себе прямо: стать, день народження. Розбір без моделі. */
object PersonalFacts {
    private val gender = Regex("^(?:а\\s+)?я\\s+(?:ж\\s+|же\\s+)?(дівчинка|дівчина|жінка|мама|хлопчик|хлопець|чоловік|тато)$")
    private val MONTHS = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")
    private val birthday = Regex(
        "(?:день\\s+народження|народи(?:вся|лася|лась))\\s+(?:у\\s+мене\\s+)?(?:—\\s+)?(\\d{1,2})(?:-?го)?\\s+(${MONTHS.joinToString("|")})(?:\\s+(\\d{4}))?",
    )
    /** Відповідь на "коли в тебе день народження?": "21 грудня", "двадцять першого грудня" — лише цифрами. */
    private val bareDate = Regex("^(?:у\\s+|в\\s+|на\\s+)?(\\d{1,2})(?:-?го)?\\s+(${MONTHS.joinToString("|")})(?:\\s+(\\d{4})(?:\\s+року)?)?$")

    /** Дата сама по собі ("21 грудня") — "MM-dd" або "yyyy-MM-dd". */
    fun date(phrase: String): String? = bareDate.find(normalize(phrase))?.let { toDate(it) }

    private fun toDate(m: MatchResult): String? {
        val day = m.groupValues[1].toInt()
        if (day !in 1..31) return null
        val md = "%02d-%02d".format(MONTHS.indexOf(m.groupValues[2]) + 1, day)
        return m.groupValues[3].takeIf { it.isNotEmpty() }?.let { "$it-$md" } ?: md
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[,!?.«»\"]"), " ").replace(Regex("\\s+"), " ").trim()

    fun gender(phrase: String): Gender? = gender.find(normalize(phrase))?.groupValues?.get(1)?.let {
        if (it in setOf("дівчинка", "дівчина", "жінка", "мама")) Gender.FEMALE else Gender.MALE
    }

    /** "MM-dd" або "yyyy-MM-dd". */
    fun birthday(phrase: String): String? = birthday.find(normalize(phrase))?.let { toDate(it) }

    /** "День народження Тараса" / "Тарас — день народження" у календарі чи сповіщенні — чий (знайоме ім'я). */
    fun birthdayOf(text: String, known: Collection<String>): String? {
        val t = text.lowercase()
        if (!t.contains("народжен")) return null
        val words = Regex("\\p{L}+").findAll(t).map { it.value }.toList()
        // Ім'я у будь-якому відмінку: "Тараса", "Ліни" — однакова основа (без закінчення).
        return known.firstOrNull { name ->
            val stem = name.lowercase().let { if (it.length > 3) it.dropLast(1) else it }
            words.any { it.startsWith(stem) && it.length - stem.length <= 2 }
        }
    }

    /** "21 грудня" з "MM-dd" чи "yyyy-MM-dd". */
    fun spoken(birthday: String): String {
        val parts = birthday.split('-')
        val (m, d) = parts.takeLast(2).map { it.toInt() }
        return "$d ${MONTHS[m - 1]}"
    }
}
