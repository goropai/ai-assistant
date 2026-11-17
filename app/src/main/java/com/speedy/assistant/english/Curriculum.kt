package com.speedy.assistant.english

import org.json.JSONArray

/** Тема уроку: коротка теорія, фрази "повтори за мною", діалог. */
data class Unit(
    val level: Level,
    val title: String,
    /** Українською; англійське — у фігурних дужках: "{I am} — я є". */
    val theory: String,
    val phrases: List<Phrase>,
    val dialogue: List<Question>,
)

data class Phrase(val en: String, val uk: String)

/** Питання англійською; відповідь зарахована, якщо в ній є будь-що з [expect]. */
data class Question(val q: String, val hint: String, val expect: List<String>, val sample: String)

class Curriculum(units: List<Unit>) {
    private val byLevel = units.groupBy { it.level }

    fun units(level: Level): List<Unit> = byLevel[level].orEmpty()

    companion object {
        fun parse(json: String): Curriculum {
            val arr = JSONArray(json)
            return Curriculum((0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val level = Level.fromCode(o.getString("level")) ?: return@mapNotNull null
                val phrases = o.getJSONArray("phrases")
                val dialogue = o.getJSONArray("dialogue")
                Unit(
                    level, o.getString("title"), o.getString("theory"),
                    List(phrases.length()) { phrases.getJSONObject(it).let { p -> Phrase(p.getString("en"), p.getString("uk")) } },
                    List(dialogue.length()) {
                        dialogue.getJSONObject(it).let { d ->
                            val ex = d.getJSONArray("expect")
                            Question(d.getString("q"), d.optString("hint"), List(ex.length()) { ex.getString(it) }, d.getString("sample"))
                        }
                    },
                )
            })
        }

        /** "Кажемо {I like} — мені подобається" → [(uk "Кажемо"), (en "I like"), (uk "— мені подобається")]. */
        fun segments(text: String): List<Pair<Boolean, String>> {
            val out = mutableListOf<Pair<Boolean, String>>()
            var rest = text
            while (rest.isNotEmpty()) {
                val open = rest.indexOf('{')
                if (open < 0) { out += false to rest; break }
                if (open > 0) out += false to rest.substring(0, open)
                val close = rest.indexOf('}', open)
                if (close < 0) { out += false to rest.substring(open + 1); break }
                out += true to rest.substring(open + 1, close)
                rest = rest.substring(close + 1)
            }
            return out.map { it.first to it.second.trim() }.filter { it.second.isNotEmpty() && it.second.any(Char::isLetterOrDigit) }
        }
    }
}

/** Що почув розпізнавач: варіанти тексту й упевненість (Google дає її для першого варіанта). */
data class Heard(val texts: List<String>, val confidence: Float? = null)

/** Оцінка вимови "на слух" — за тим, наскільки впевнено розпізнавач почув саме цю фразу. */
data class Pronunciation(val score: Int, val weakWord: String?)

object Pronounce {
    /**
     * Перший (найімовірніший) варіант містить усю фразу і розпізнавач упевнений — висока оцінка;
     * фраза лише в запасних варіантах або бракує слів — нижча; слово, якого бракує, — "слабке".
     */
    fun score(heard: Heard, target: String): Pronunciation {
        // Артиклі розпізнавач майже ніколи не пише ("A green apple." → "green apple") — не вимагаємо.
        val words = Answers.clean(target).split(' ').filter { it.isNotEmpty() }
            .let { all -> all.filter { it !in ARTICLES }.ifEmpty { all } }
        if (words.isEmpty() || heard.texts.isEmpty()) return Pronunciation(0, null)
        fun coverage(text: String): Float {
            val got = Answers.clean(text).split(' ').toSet()
            return words.count { it in got }.toFloat() / words.size
        }
        val top = coverage(heard.texts.first())
        val best = heard.texts.maxOf { coverage(it) }
        val confidence = heard.confidence?.takeIf { it > 0f } ?: 0.7f
        val score = when {
            top >= 0.99f -> 70 + (30 * confidence).toInt()
            top >= 0.7f -> 50 + (20 * top).toInt() + (10 * confidence).toInt()
            best >= 0.7f -> 40 + (20 * best).toInt()
            else -> (40 * best).toInt()
        }.coerceIn(0, 100)
        val topWords = Answers.clean(heard.texts.first()).split(' ').toSet()
        val weak = words.filter { it.length > 2 && it !in topWords }.maxByOrNull { it.length }
        return Pronunciation(score, weak)
    }

    private val ARTICLES = setOf("a", "an", "the")
}
