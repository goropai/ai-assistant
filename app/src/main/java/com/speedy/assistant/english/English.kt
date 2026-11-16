package com.speedy.assistant.english

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Рівні: від "малюка" (тварини, кольори, числа) до "впевненого" (фразові дієслова). */
enum class Level(val code: String, val ua: String) {
    KIDS("kids", "малюк"), A1("a1", "початківець"), A2("a2", "середній"), B1("b1", "впевнений");

    val next: Level? get() = entries.getOrNull(ordinal + 1)
    val previous: Level? get() = entries.getOrNull(ordinal - 1)

    companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code }

        /** "малюк", "дитина", "початківець", "середній", "впевнений", "просунутий" → рівень. */
        fun fromSpoken(text: String): Level? {
            val t = text.lowercase()
            return when {
                listOf("малюк", "дитин", "маленьк", "дитяч").any { it in t } -> KIDS
                listOf("початків", "почат", "нуля", "базов").any { it in t } -> A1
                listOf("середн", "нормальн").any { it in t } -> A2
                listOf("впевнен", "просунут", "високий", "добрий", "складн").any { it in t } -> B1
                else -> null
            }
        }
    }
}

data class Word(val level: Level, val en: String, val uk: List<String>)

/** Словник уроків (assets/english/words.json). */
class Vocabulary(words: List<Word>) {
    private val byLevel = words.groupBy { it.level }

    fun words(level: Level): List<Word> = byLevel[level].orEmpty()
    fun find(en: String): Word? = byLevel.values.flatten().firstOrNull { it.en == en }

    /** Усі англійські слова з таким перекладом: "позичати" — і borrow, і lend (обидва правильні). */
    fun englishFor(uk: String): List<String> = byLevel.values.flatten().filter { uk in it.uk }.map { it.en }

    companion object {
        fun parse(json: String): Vocabulary {
            val arr = JSONArray(json)
            return Vocabulary((0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val level = Level.fromCode(o.getString("level")) ?: return@mapNotNull null
                val uk = o.getJSONArray("uk")
                Word(level, o.getString("en"), List(uk.length()) { uk.getString(it) })
            })
        }
    }
}

/**
 * Прогрес кожного учня (за іменем): рівень, слова у "коробках" інтервального повторення,
 * історія уроків. Лише на телефоні (files/english.json).
 */
class EnglishProgress(private val file: File?) {

    class Card(var box: Int = 0, var due: Long = 0, var right: Int = 0, var wrong: Int = 0)
    /** [pronunciation] — середня оцінка вимови на слух (0–100) або null. */
    class Lesson(val at: Long, val score: Int, val total: Int, val newWords: Int, val pronunciation: Int? = null)

    class Learner(
        var level: Level? = null,
        val cards: LinkedHashMap<String, Card> = LinkedHashMap(),
        val lessons: MutableList<Lesson> = mutableListOf(),
        /** Номер наступної теми на кожному рівні. */
        val units: HashMap<String, Int> = HashMap(),
    ) {
        val known: Int get() = cards.values.count { it.box >= 2 }
    }

    private val learners = LinkedHashMap<String, Learner>()

    init {
        load()
    }

    @Synchronized
    fun learner(name: String): Learner = learners.getOrPut(name) { Learner() }

    /** Скинути рівень — наступний урок почнеться з перевірки. */
    @Synchronized
    fun resetLevel(name: String) {
        learner(name).level = null
        save()
    }

    @Synchronized
    fun setLevel(name: String, level: Level) {
        learner(name).level = level
        save()
    }

    /** Слова на повторення (час настав), спершу найслабші. */
    @Synchronized
    fun due(name: String, now: Long, max: Int): List<String> =
        learner(name).cards.filter { it.value.due <= now }.entries
            .sortedWith(compareBy({ it.value.box }, { it.value.due }))
            .take(max).map { it.key }

    /** Наступні нові слова рівня, яких учень ще не бачив. */
    @Synchronized
    fun fresh(name: String, vocabulary: Vocabulary, level: Level, max: Int): List<Word> =
        vocabulary.words(level).filter { it.en !in learner(name).cards }.take(max)

    /** Відповідь на вправу: правильно — слово "піднімається" (рідше повторювати), ні — назад до початку. */
    @Synchronized
    fun answer(name: String, en: String, correct: Boolean, now: Long) {
        val card = learner(name).cards.getOrPut(en) { Card() }
        if (correct) {
            card.right++
            card.box = (card.box + 1).coerceAtMost(INTERVALS_DAYS.size)
        } else {
            card.wrong++
            card.box = 1
        }
        card.due = now + INTERVALS_DAYS[card.box - 1] * DAY_MS
        save()
    }

    /** Нове слово показали — у першу коробку (повторити завтра). */
    @Synchronized
    fun introduce(name: String, en: String, now: Long) {
        learner(name).cards.getOrPut(en) { Card(box = 1, due = now + DAY_MS) }
        save()
    }

    @Synchronized
    fun unitIndex(name: String, level: Level): Int = learner(name).units[level.code] ?: 0

    /** Тему зараховано — наступного разу наступна. */
    @Synchronized
    fun completeUnit(name: String, level: Level, index: Int) {
        learner(name).units[level.code] = maxOf(unitIndex(name, level), index + 1)
        save()
    }

    @Synchronized
    fun finishLesson(name: String, lesson: Lesson) {
        learner(name).lessons += lesson
        while (learner(name).lessons.size > 100) learner(name).lessons.removeAt(0)
        save()
    }

    /**
     * Порада щодо рівня з трьох останніх уроків: стабільно > 90% — вище, < 50% — нижче.
     */
    @Synchronized
    fun levelAdvice(name: String): Level? {
        val l = learner(name)
        val level = l.level ?: return null
        val last = l.lessons.takeLast(3)
        if (last.size < 3 || last.any { it.total == 0 }) return null
        val rates = last.map { it.score.toFloat() / it.total }
        return when {
            rates.all { it > 0.9f } -> level.next
            rates.all { it < 0.5f } -> level.previous
            else -> null
        }
    }

    /** Днів поспіль з уроком (включно з сьогоднішнім). */
    @Synchronized
    fun streak(name: String, now: Long): Int {
        val days = learner(name).lessons.map { it.at / DAY_MS }.toSet()
        var day = now / DAY_MS
        if (day !in days) day-- // сьогодні ще не займались — рахуємо до вчора
        var n = 0
        while (day in days) { n++; day-- }
        return n
    }

    private fun load() {
        val f = file?.takeIf { it.exists() } ?: return
        try {
            val json = JSONObject(f.readText())
            for (name in json.keys()) {
                val o = json.getJSONObject(name)
                val learner = Learner(level = Level.fromCode(o.optString("level")))
                o.optJSONObject("cards")?.let { cards ->
                    for (en in cards.keys()) {
                        val c = cards.getJSONObject(en)
                        learner.cards[en] = Card(c.optInt("box"), c.optLong("due"), c.optInt("right"), c.optInt("wrong"))
                    }
                }
                o.optJSONObject("units")?.let { u -> for (k in u.keys()) learner.units[k] = u.getInt(k) }
                o.optJSONArray("lessons")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val l = arr.getJSONObject(i)
                        learner.lessons += Lesson(
                            l.getLong("at"), l.getInt("score"), l.getInt("total"), l.optInt("new"),
                            if (l.has("pron")) l.getInt("pron") else null,
                        )
                    }
                }
                learners[name] = learner
            }
        } catch (e: Exception) {
            learners.clear()
        }
    }

    private fun save() {
        val f = file ?: return
        val json = JSONObject()
        learners.forEach { (name, l) ->
            val cards = JSONObject()
            l.cards.forEach { (en, c) ->
                cards.put(en, JSONObject().put("box", c.box).put("due", c.due).put("right", c.right).put("wrong", c.wrong))
            }
            json.put(
                name, JSONObject()
                    .put("level", l.level?.code.orEmpty())
                    .put("cards", cards)
                    .put("units", JSONObject(l.units as Map<*, *>))
                    .put("lessons", JSONArray(l.lessons.map {
                        JSONObject().put("at", it.at).put("score", it.score).put("total", it.total).put("new", it.newWords)
                            .apply { it.pronunciation?.let { p -> put("pron", p) } }
                    }))
            )
        }
        f.writeText(json.toString())
    }

    companion object {
        const val DAY_MS = 24 * 3600_000L
        /** Через скільки днів повторити слово з коробки 1..6. */
        val INTERVALS_DAYS = longArrayOf(1, 2, 4, 8, 16, 32)
    }
}

/** Перевірка відповіді: розпізнавач пише по-різному — звіряємо без розділових знаків і з допуском. */
object Answers {
    fun matches(candidates: List<String>, expected: List<String>): Boolean {
        val wanted = expected.map(::clean)
        return candidates.map(::clean).any { heard ->
            wanted.any { w -> heard == w || heard.split(' ').contains(w) || (w.length >= 5 && distance(heard, w) <= 1) || stripTo(heard) == stripTo(w) }
        }
    }

    /** "to look forward to" — без "to" спереду теж правильно. */
    private fun stripTo(s: String) = s.removePrefix("to ").trim()

    fun clean(s: String) = s.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}' ]"), " ").replace(Regex("\\s+"), " ").trim()

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
