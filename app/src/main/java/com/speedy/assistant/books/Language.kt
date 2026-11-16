package com.speedy.assistant.books

import java.util.Locale

/** Мова книги: голос синтезатора і словник розпізнавання сканів. */
enum class Lang(val locale: Locale, val tess: String, val spoken: String) {
    UK(Locale("uk", "UA"), "ukr", "українського"),
    RU(Locale("ru", "RU"), "rus", "російського"),
    EN(Locale.US, "eng", "англійського");

    companion object {
        /** Літери, яких немає в іншій кириличній мові. */
        private const val ONLY_UK = "іїєґ"
        private const val ONLY_RU = "ыэъё"

        /**
         * Мова за літерами: латиниця переважає — англійська; кирилиця — українська чи російська
         * за "своїми" літерами (і/ї/є/ґ проти ы/э/ъ/ё). null — замало літер або не розібрати
         * (тоді — мова розділу / книги, [fallback]).
         */
        fun detect(text: String, fallback: Lang? = null): Lang? {
            var latin = 0
            var cyrillic = 0
            var uk = 0
            var ru = 0
            for (ch in text) {
                val c = ch.lowercaseChar()
                when {
                    c in 'a'..'z' -> latin++
                    c in 'а'..'я' || c in ONLY_UK || c in ONLY_RU -> {
                        cyrillic++
                        if (c in ONLY_UK) uk++ else if (c in ONLY_RU) ru++
                    }
                }
            }
            if (latin + cyrillic < 3) return fallback
            if (latin > cyrillic * 2) return EN
            if (cyrillic > latin * 2) return when {
                uk > ru -> UK
                ru > uk -> RU
                // Кирилиця без "своїх" літер (коротко): мова розділу, якщо вона кирилична.
                fallback == UK || fallback == RU -> fallback
                else -> UK
            }
            return fallback ?: if (latin > cyrillic) EN else UK
        }
    }
}
