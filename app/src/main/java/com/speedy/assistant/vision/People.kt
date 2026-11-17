package com.speedy.assistant.vision

/**
 * Пам'ять про людей: спільна для потоку камери ([PresenceWatcher]) і головного потоку
 * ([com.speedy.assistant.Assistant]), тому всі методи синхронізовані.
 * Час — той самий монотонний годинник, що й у камери (SystemClock.uptimeMillis).
 */
class People(
    private val book: FaceBook,
    /** Вік за профілем: CHILD / ADULT / UNKNOWN (див. Profiles.age). */
    private val ageOf: (String) -> String = { "UNKNOWN" },
) {
    private val tracker = FaceIdTracker(book, ageFits = { name, eye -> ageFits(ageOf(name), eye) })

    @Synchronized
    fun update(now: Long, faces: List<FaceObservation>): List<PresenceEvent> = tracker.update(now, faces)

    /**
     * Хто говорить зі Спіді: найближче (найбільше) обличчя. Знайоме — ім'я; незнайоме або давно
     * не видно — null (тоді Спіді не приписує розмову знайомому, що стоїть позаду).
     */
    @Synchronized
    fun speaker(now: Long, maxAgeMs: Long = 10_000): String? =
        tracker.closestName?.takeIf { now - tracker.closestSeenAt <= maxAgeMs }

    /** Хтось сидить перед Спіді: найближче обличчя досить велике й видно щойно. */
    @Synchronized
    fun someoneClose(now: Long, maxAgeMs: Long = 4_000): Boolean =
        tracker.closestArea >= 0.04f && now - tracker.closestSeenAt <= maxAgeMs

    /** Знайомі обличчя з останнього кадру. */
    @Synchronized
    fun lastMatches(): List<Pair<String, FaceObservation>> = tracker.lastMatches

    @Synchronized
    fun presentNames(now: Long): List<String> = tracker.presentNames(now)

    sealed interface Introduction {
        data object Remembered : Introduction
        /** Обличчя не бачили нещодавно. */
        data object NoFace : Introduction
        data object Unclear : Introduction
        data class LooksLike(val name: String) : Introduction
    }

    /**
     * Прив'язати ім'я до щойно баченого незнайомого обличчя — лише після перевірки проти
     * всіх відомих облич: схоже на когось — нове ім'я не створюємо.
     */
    @Synchronized
    fun introduce(name: String, now: Long): Introduction {
        val samples = tracker.recentStranger(now)
        if (samples.isEmpty()) return Introduction.NoFace
        // Знайома, яку зараз погано впізнає (далеко, ракурс): кадри, хоч трохи схожі на неї, —
        // до її образу, щоб далі впізнавала. Інакше "погано роздивилась" повторювалось би без кінця.
        book.names.firstOrNull { it.equals(name, ignoreCase = true) }?.let { known ->
            val theirs = samples.filter { book.match(it, FaceBook.POSSIBLE_MATCH)?.first == known }
            if (theirs.isNotEmpty()) {
                book.remember(known, theirs)
                tracker.clearStranger()
                return Introduction.Remembered
            }
        }
        return when (val check = book.check(samples)) {
            is FaceBook.Check.New -> {
                book.remember(name, check.samples)
                tracker.clearStranger()
                Introduction.Remembered
            }
            is FaceBook.Check.LooksLike ->
                if (check.name.equals(name, ignoreCase = true)) {
                    book.remember(check.name, samples) // той самий — лише уточнюємо образ
                    tracker.clearStranger()
                    Introduction.Remembered
                } else Introduction.LooksLike(check.name)
            FaceBook.Check.Unclear -> {
                // Ці кадри не годяться — наступна спроба з нових (а не з тих самих, що й щойно).
                tracker.clearStranger()
                Introduction.Unclear
            }
        }
    }

    @Synchronized
    fun forget(name: String): Boolean = book.forget(name)

    @Synchronized
    fun knows(name: String): Boolean = name in book.names

    @Synchronized
    fun names(): List<String> = book.names.toList()

    /** Знайоме ім'я, схоже на сказане з урахуванням відмінка ("Костю" → "Костя"). */
    @Synchronized
    fun findName(spoken: String): String? {
        val s = soundKey(spoken)
        book.names.firstOrNull { soundKey(it) == s }?.let { return it }
        // Відмінок: "Костю" → "Костя" (основа без останньої літери).
        val stem = if (s.length >= 3) s.dropLast(1) else s
        book.names.filter { soundKey(it).startsWith(stem) }.singleOrNull()?.let { return it }
        // Розпізнавач пише інакше ("Спіть" → "спить"): одна відмінна літера для імен від 4 літер.
        return book.names.filter { s.length >= 4 && distance(soundKey(it), s) <= 1 }.singleOrNull()
    }

    init {
        // Імена, які насправді звертання до самої Спіді (офлайн-слух чув "Спіді" як "спіть"), — прибрати.
        book.names.filter { soundKey(it) in WAKE_LIKE }.forEach { book.forget(it) }
    }

    companion object {
        /**
         * Дитяче обличчя (очі ≥ 0.47) не може бути дорослим профілем. Навпаки — обережно: у дитини
         * очі буває й вище (нахил голови, ракурс знизу) — Ліна 0.41–0.44 при Тарасові 0.39–0.42, тож
         * дитячий профіль відкидаємо лише за явно дорослих пропорцій.
         */
        fun ageFits(age: String, eyeLevel: Float?): Boolean = when {
            eyeLevel == null -> true
            age == "ADULT" -> eyeLevel < 0.47f
            age == "CHILD" -> eyeLevel > 0.38f
            else -> true
        }

        private val WAKE_LIKE = setOf("спіді", "спід", "спіть", "спікер", "speedy").map { soundKey(it) }.toSet()

        /** Звучання без дрібниць написання: і/и/ї/i, е/є, г/ґ, апостроф. */
        fun soundKey(name: String): String = name.lowercase()
            .replace('ї', 'і').replace('и', 'і').replace('i', 'і')
            .replace('є', 'е').replace('ґ', 'г')
            .filter { it.isLetter() }

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

    /**
     * Людина в кадрі — насправді [name] (назвалась жартома або її сплутали з кимось).
     * false — обличчя щойно не було видно.
     */
    @Synchronized
    fun reassign(name: String, now: Long): Boolean {
        val faces = tracker.recentFaces(now)
        if (faces.isEmpty()) return false
        val before = tracker.presentNames(now)
        book.reassign(name, faces)
        before.forEach { tracker.renamed(it, name, now) }
        return true
    }

    /** Знайомий, що зараз поруч, назвався інакше — міняємо ім'я, обличчя лишаються. */
    @Synchronized
    fun rename(old: String, new: String, now: Long): Boolean = book.rename(old, new).also {
        if (it) tracker.renamed(old, new, now)
    }
}
