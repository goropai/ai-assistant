package com.speedy.assistant.english

/** Що уроку треба від Спіді: говорити (українською / англійською), слухати, радіти. */
interface LessonIO {
    suspend fun say(uk: String)
    suspend fun sayEnglish(en: String)
    /** Що почула (варіанти й упевненість розпізнавача) або null — мовчання. */
    suspend fun listen(english: Boolean): Heard?
    suspend fun cheer()
    suspend fun oops()
}

/**
 * Урок англійської (~6–8 хв) без моделі, побудований навколо теми:
 * коротка теорія → "повтори за мною" з оцінкою вимови на слух → діалог (питання англійською,
 * відповідь своїми словами) → розминка словами за кривою забування → підсумок.
 * Тема зарахована при ≥ 60% — наступного разу нова; інакше повторюємо. Прогрес — [EnglishProgress].
 */
class EnglishLesson(
    private val vocabulary: Vocabulary,
    private val curriculum: Curriculum,
    private val progress: EnglishProgress,
    private val io: LessonIO,
    private val name: String,
    private val now: () -> Long,
    /** Теми, складені моделлю раніше (понад вбудовані). */
    private val extra: UnitStore? = null,
    /** Скласти нову тему моделлю (null — не вдалось / немає "мозку"). */
    private val generate: (suspend (Level, List<String>) -> Pair<org.json.JSONObject, Unit>?)? = null,
    /** Дитина за обличчям чи словами (true), дорослий (false), невідомо (null). */
    private val child: Boolean? = null,
) {
    private var stopped = false
    /** Підсумок уроку (для логу). */
    var result: String = ""
        private set
    private var silences = 0
    private var score = 0
    private var total = 0
    private val pronunciation = mutableListOf<Int>()

    suspend fun run() {
        val level = progress.learner(name).level ?: placement()
        val kids = level == Level.KIDS
        val units = curriculum.units(level) + extra?.units(level).orEmpty()
        val index = progress.unitIndex(name, level)
        val unit = units.getOrNull(index) ?: newUnit(level, units)

        if (unit != null) {
            io.say("Урок ${index + 1}: ${unit.title}.")
            speakMixed(unit.theory)
            if (!stopped) repeatAfterMe(unit, kids)
            if (!stopped) dialogue(unit, kids)
        } else {
            io.say("Нову тему зараз скласти не вийшло. Сьогодні повторимо слова.")
        }
        if (!stopped) words(level, kids)
        finish(level, unit, index, units.size)
    }

    /** Вбудовані теми скінчились — Спіді складає нову під рівень учня (раз на рівень, потім з кешу). */
    private suspend fun newUnit(level: Level, done: List<Unit>): Unit? {
        val gen = generate ?: return null
        io.say("Вигадую нову тему для тебе…")
        val (json, unit) = gen(level, done.map { it.title }) ?: return null
        extra?.add(json, unit)
        return unit
    }

    /**
     * Швидка перевірка рівня замість питання: слова від простих до складних, по 2 на рівень,
     * до першої помилки. Дитина (за обличчям) — одразу дитячі уроки: 4-річна дитина в Україні
     * англійську на високому рівні не знає. Дорослого починаємо з "початківця".
     */
    private suspend fun placement(): Level {
        if (child == true) {
            io.say("Бачу, що ти ще маленький! Почнемо з дитячих уроків.")
            progress.setLevel(name, Level.KIDS)
            return Level.KIDS
        }
        io.say("Спершу швидко перевіримо, що ти вже знаєш. Я казатиму слова, а ти — англійською.")
        val start = if (child == false) Level.A1 else Level.KIDS
        var passed: Level? = null
        for (level in Level.entries.filter { it.ordinal >= start.ordinal }) {
            var correct = 0
            for (word in vocabulary.words(level).shuffled().take(2)) {
                io.say("«${word.uk.first()}»?")
                var heard = io.listen(english = true)
                // Не почула (розпізнавач нічого не повернув) — питаємо ще раз, а не рахуємо помилкою.
                if (heard == null) { io.say("Не почула. «${word.uk.first()}»?"); heard = io.listen(english = true) }
                if (control(heard)) break
                if (heard != null && Answers.matches(heard.texts, vocabulary.englishFor(word.uk.first()))) correct++
            }
            if (stopped || correct < 2) break
            passed = level
        }
        stopped = false // перервали перевірку — урок однаково на визначеному рівні
        silences = 0
        val level = passed ?: start
        progress.setLevel(name, level)
        io.say("Твій рівень — ${level.ua}.")
        return level
    }

    /** Українською з англійськими вставками {…} — кожну частину своїм голосом. */
    private suspend fun speakMixed(text: String) {
        for ((english, part) in Curriculum.segments(text)) if (english) io.sayEnglish(part) else io.say(part)
    }

    /** Повтори за мною: Спіді каже фразу й переклад, учень повторює, Спіді оцінює вимову на слух. */
    private suspend fun repeatAfterMe(unit: Unit, kids: Boolean) {
        io.say(if (kids) "Тепер повторюй за мною!" else "Повторюй за мною.")
        for (phrase in unit.phrases) {
            if (stopped) return
            io.sayEnglish(phrase.en)
            io.say(phrase.uk)
            var heard = io.listen(english = true)
            if (control(heard)) return
            var result = heard?.let { Pronounce.score(it, phrase.en) } ?: Pronunciation(0, null)
            if (result.score in 1..64 || heard == null) {
                // Друга спроба: послухати ще раз і повторити повільніше.
                io.say(if (result.score >= 40) "Я майже зрозуміла. Повтори ще раз повільніше:" else "Не розчула. Послухай:")
                io.sayEnglish(phrase.en)
                heard = io.listen(english = true)
                if (control(heard)) return
                result = heard?.let { Pronounce.score(it, phrase.en) } ?: Pronunciation(0, null)
            }
            pronunciation += result.score
            total++
            when {
                result.score >= 85 -> { score++; io.cheer(); io.say(listOf("Відмінна вимова!", "Чудово звучить!", "Як справжній англієць!").random()) }
                result.score >= 65 -> {
                    score++
                    val weak = result.weakWord
                    if (weak != null) {
                        io.say("Добре! Над словом")
                        io.sayEnglish(weak)
                        io.say("ще попрацюй.")
                    } else {
                        io.say("Добре!")
                    }
                }
                result.score >= 40 -> { io.say("Вже краще. Ще потренуємося."); io.sayEnglish(phrase.en) }
                else -> { io.oops(); io.say("Нічого, наступного разу вийде.") }
            }
        }
    }

    /** Діалог: питання англійською, відповідь своїми словами (зараховуємо за ключовими словами). */
    private suspend fun dialogue(unit: Unit, kids: Boolean) {
        io.say(if (kids) "А тепер поговоримо англійською!" else "Тепер поговоримо. Відповідай англійською.")
        for (q in unit.dialogue) {
            if (stopped) return
            io.sayEnglish(q.q)
            if (q.hint.isNotBlank() && (kids || unit.level == Level.A1)) io.say(q.hint)
            var heard = io.listen(english = true)
            if (control(heard)) return
            if (heard != null && isRepeat(heard)) { io.sayEnglish(q.q); heard = io.listen(english = true) }
            total++
            if (heard != null && answered(heard, q)) {
                score++
                io.cheer()
                io.say(listOf("Чудова відповідь!", "Так, саме так!", "Молодець!").random())
            } else {
                io.oops()
                io.say("Можна відповісти так:")
                io.sayEnglish(q.sample)
                io.say("Повтори.")
                val again = io.listen(english = true)
                if (control(again)) return
                again?.let { pronunciation += Pronounce.score(it, q.sample).score }
            }
        }
    }

    private fun answered(heard: Heard, q: Question): Boolean {
        val texts = heard.texts.map { " " + Answers.clean(it) + " " }
        return q.expect.any { e -> val k = Answers.clean(e); texts.any { t -> " $k " in t || (k.length > 3 && k in t) } }
    }

    /** Розминка словами: 2–3 на повторення (крива забування) і 2 нові. */
    private suspend fun words(level: Level, kids: Boolean) {
        val reviews = progress.due(name, now(), 3).mapNotNull { vocabulary.find(it) }
        val fresh = progress.fresh(name, vocabulary, level, 2)
        if (reviews.isEmpty() && fresh.isEmpty()) return
        io.say("І трохи слів.")
        for (word in fresh) {
            if (stopped) return
            io.say("Нове слово — ${word.uk.first()}:")
            io.sayEnglish(word.en)
            progress.introduce(name, word.en, now())
        }
        for ((i, word) in (reviews + fresh).withIndex()) {
            if (stopped) return
            val toEnglish = !kids && i % 2 == 0
            if (toEnglish) io.say("Як англійською «${word.uk.first()}»?") else { io.say("Що означає"); io.sayEnglish(word.en) }
            val heard = io.listen(english = toEnglish)
            if (control(heard)) return
            val ok = heard != null && Answers.matches(heard.texts, if (toEnglish) vocabulary.englishFor(word.uk.first()) else word.uk)
            total++
            if (ok) { score++; io.cheer(); io.say(listOf("Так!", "Правильно!", "Супер!").random()) }
            else { io.say("Правильно —"); if (toEnglish) io.sayEnglish(word.en) else io.say(word.uk.first()) }
            progress.answer(name, word.en, ok, now())
        }
    }

    /** "досить" — кінець уроку; тричі мовчання — теж. */
    private suspend fun control(heard: Heard?): Boolean {
        if (heard == null) {
            silences++
            if (silences >= 3) {
                io.say("Здається, ти пішов. Закінчимо урок.")
                stopped = true
                return true
            }
            return false
        }
        silences = 0
        if (heard.texts.any { h -> STOP_WORDS.any { it in h.lowercase() } }) {
            stopped = true
            return true
        }
        return false
    }

    private fun isRepeat(heard: Heard) = heard.texts.any { it.lowercase().contains("повтори") || it.lowercase().contains("repeat") }

    private suspend fun finish(level: Level, unit: Unit?, index: Int, units: Int) {
        if (total == 0) return
        val avg = if (pronunciation.isEmpty()) null else pronunciation.average().toInt()
        progress.finishLesson(name, EnglishProgress.Lesson(now(), score, total, 0, avg))
        val parts = mutableListOf("$score з $total!")
        avg?.let { parts += "Вимова сьогодні: $it зі ста." }
        if (unit != null) {
            if (score * 10 >= total * 6) {
                progress.completeUnit(name, level, index)
                parts += "Тему «${unit.title}» пройдено, наступного разу — нова."
            } else {
                parts += "Наступного разу ще раз повторимо тему «${unit.title}»."
            }
        }
        val streak = progress.streak(name, now())
        if (streak >= 2) parts += "Займаємось $streak ${plural(streak, "день", "дні", "днів")} поспіль!"
        progress.levelAdvice(name)?.let { advice ->
            parts += if (advice.ordinal > level.ordinal) "Ти так добре справляєшся — може, перейдемо на рівень «${advice.ua}»? Скажи «мій рівень англійської — ${advice.ua}»."
            else "Може, трохи спростимо — рівень «${advice.ua}»? Скажи «мій рівень англійської — ${advice.ua}»."
        }
        if (score * 2 >= total) io.cheer()
        result = parts.joinToString(" ")
        io.say(result)
    }

    companion object {
        private val STOP_WORDS = listOf("досить", "стоп", "закінч", "вистачить", "хватит", "stop")

        fun plural(n: Int, one: String, few: String, many: String) = when {
            n % 100 in 11..14 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
    }
}
