package com.speedy.assistant.school

import android.util.Log
import com.speedy.assistant.english.Heard
import com.speedy.assistant.english.LessonIO
import com.speedy.assistant.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Предмети "школи Спіді" — для дитини (дорослі основи знають). */
enum class Subject(val code: String, val ua: String, val about: String) {
    MATH("math", "математика", "лічба, додавання й віднімання, порівняння, задачі на кмітливість з числами"),
    LOGIC("logic", "логіка", "закономірності, зайве в ряду, що спільного, прості логічні задачі й загадки"),
    WORLD("world", "навколишній світ", "тварини, рослини, пори року, тіло людини, безпека, як влаштовані речі"),
    UKRAINIAN("ukrainian", "українська мова", "букви й звуки, склади, рими, перший звук слова, протилежні слова; правопис (як пишеться слово — " +
        "назвати букву чи вибрати з двох варіантів), значення слів і як їх уживати в реченні, вимова й наголос (де наголос у слові); " +
        "для старших — частини мови"),
    HISTORY("history", "історія", "історія України і світу простими словами: як жили люди раніше, видатні люди, події, винаходи, символи"),
    MATH_EN("math_en", "математика англійською", "лічба й прості приклади англійською: питання англійською, відповідь числом англійською"),
    /** Письмо — без моделі: обвести пальцем обрис фігури, цифри чи літери (див. WritingLesson). */
    WRITING("writing", "письмо", "обвести пальцем фігури, цифри й літери");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code }
    }
}

/** "Урок математики", "давай порахуємо англійською", "загадай логічну задачку" — без моделі. */
object SchoolCommands {
    private const val VERB = "(?:давай|проведи|почни|хочу|можна|розпочни|влаштуй|зробимо|позаймаємось|позаймаємося|займемося|вчимо|повчимо|навчи|урок|уроки|заняття)"
    private val mathEn = Regex("(?:математик\\p{L}*|рахувати|порахуємо|рахуємо|лічб\\p{L}*)\\s+(?:\\p{L}+\\s+)?(?:англійськ|по-англійськ)|англійськ\\p{L}*\\s+(?:математик|рахун|лічб)|math")
    private val math = Regex("(?:^|\\s)(?:урок\\p{L}*\\s+)?(?:математик\\p{L}*|арифметик\\p{L}*)|$VERB\\s+(?:\\p{L}+\\s+)?(?:порахуємо|рахувати|лічбу)|^порахуємо$|^давай\\s+порахуємо")
    private val logic = Regex("логік\\p{L}*|логічн\\p{L}*\\s+(?:задач|загад|ігр)|задачк\\p{L}*\\s+на\\s+кмітлив|на\\s+кмітливість")
    private val writing = Regex("(?:урок\\p{L}*|заняття)\\s+письм|обвод\\p{L}*\\s+(?:літер|букв|цифр|фігур)|(?:давай|хочу|будемо)\\s+(?:писати|пописати|обводити)|^писати\\s+літери")
    private val world = Regex("навколишн\\p{L}*\\s+світ|(?:урок|заняття)\\s+про\\s+(?:природу|світ|тварин)")
    /** Лише як урок: "розмовляй українською" чи "розкажи історію" (казку) — не уроки. */
    private const val LESSON = "(?:урок\\p{L}*|заняття|вчимо|вчити|повчимо|повчи|навчи|позаймаємось|позаймаємося|займемося)"
    private val ukrainian = Regex("$LESSON\\s+(?:з\\s+)?(?:\\p{L}+\\s+)?українськ|українськ\\p{L}*\\s+мов\\p{L}*\\s*$|^українська\\s+мова")
    private val history = Regex("$LESSON\\s+(?:з\\s+)?(?:\\p{L}+\\s+)?істор\\p{L}*|^історія(?:\\s+україни)?$")
    /** "що таке логіка", "хто придумав математику" — питання, не урок. */
    private val question = Regex("^(?:що\\s+таке|хто\\s+(?:придумав|вигадав)|навіщо|чому)\\s")

    fun parse(phrase: String): Subject? {
        val t = phrase.lowercase().replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
        if (question.containsMatchIn(t)) return null
        return when {
            writing.containsMatchIn(t) -> Subject.WRITING
            mathEn.containsMatchIn(t) -> Subject.MATH_EN
            logic.containsMatchIn(t) -> Subject.LOGIC
            world.containsMatchIn(t) -> Subject.WORLD
            ukrainian.containsMatchIn(t) -> Subject.UKRAINIAN
            history.containsMatchIn(t) -> Subject.HISTORY
            math.containsMatchIn(t) -> Subject.MATH
            else -> null
        }
    }
}

/** Для кого урок: "навчи Ліну англійської", "урок математики для Ліни", "позаймайся з Ліною". */
object Learner {
    /** Слова команди, що не можуть бути іменем учня. */
    private val notNames = setOf(
        "мене", "мені", "мною", "нас", "нам", "нами", "його", "її", "їх", "тебе", "себе", "будь", "ласка", "урок", "уроки",
        "заняття", "давай", "проведи", "навчи", "навчити", "повчи", "почни", "англійської", "англійську", "англійською",
        "математики", "математику", "математикою", "логіки", "логіку", "логікою", "світ", "навколишній", "для", "разом",
        "спіді", "трохи", "ще", "порахуємо", "рахувати", "позаймайся", "позаймаємось", "займемося", "хоче", "можна",
    )

    /** Знайоме ім'я з команди ([find] — пошук серед знайомих з урахуванням відмінка); null — не назвали. */
    fun named(command: String, find: (String) -> String?): String? =
        command.split(Regex("[^\\p{L}'’-]+")).asSequence()
            .filter { it.length >= 3 && it.lowercase() !in notNames }
            .mapNotNull(find)
            .firstOrNull()
}

/** Числа словами → цифри ("двадцять три" → "23", "twenty three" → "23"); решта — як є. */
object Numbers {
    private val units = mapOf(
        "нуль" to 0, "один" to 1, "одна" to 1, "одне" to 1, "одну" to 1, "два" to 2, "дві" to 2, "три" to 3, "чотири" to 4,
        "п'ять" to 5, "шість" to 6, "сім" to 7, "вісім" to 8, "дев'ять" to 9, "десять" to 10, "одинадцять" to 11,
        "дванадцять" to 12, "тринадцять" to 13, "чотирнадцять" to 14, "п'ятнадцять" to 15, "шістнадцять" to 16,
        "сімнадцять" to 17, "вісімнадцять" to 18, "дев'ятнадцять" to 19,
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "двадцять" to 20, "тридцять" to 30, "сорок" to 40, "п'ятдесят" to 50, "шістдесят" to 60, "сімдесят" to 70,
        "вісімдесят" to 80, "дев'яносто" to 90, "сто" to 100,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90, "hundred" to 100,
    )

    fun normalize(text: String): String {
        val words = text.lowercase().replace('’', '\'').replace('ʼ', '\'').replace(Regex("[^\\p{L}\\p{N}' -]"), " ")
            .replace('-', ' ').split(' ').filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val t = tens[w]
            if (t != null) {
                val u = words.getOrNull(i + 1)?.let { units[it] }?.takeIf { it in 1..9 && t < 100 }
                out += (t + (u ?: 0)).toString()
                i += if (u != null) 2 else 1
                continue
            }
            out += units[w]?.toString() ?: w
            i++
        }
        return out.joinToString(" ")
    }

    /** Відповідь правильна: одна з очікуваних (після нормалізації) є окремим словом / фразою у почутому. */
    fun matches(heard: List<String>, expected: List<String>): Boolean {
        val said = heard.map { " " + normalize(it) + " " }
        return expected.map { normalize(it) }.filter { it.isNotBlank() }.any { e -> said.any { " $e " in it } }
    }
}

/** Завдання уроку: питання (uk чи en), правильні відповіді, підказка і пояснення. */
data class Task(
    val q: String, val english: Boolean, val answers: List<String>, val hint: String, val explain: String,
    /** Простий малюнок до питання (рядки клітинок: емодзі, текст, "circle:red"…); null — без нього. */
    val picture: List<List<String>>? = null,
)

data class SchoolUnit(val topic: String, val intro: String, val tasks: List<Task>)

/** Прогрес учня з кожного предмета: рівень 1–10 і останні уроки (тема, рахунок, помилки). */
class SchoolProgress(private val file: File?) {
    private val root: JSONObject = file?.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: JSONObject()

    private fun subject(name: String, s: Subject): JSONObject {
        val learner = root.optJSONObject(name) ?: JSONObject().also { root.put(name, it) }
        return learner.optJSONObject(s.code) ?: JSONObject().put("level", 1).put("lessons", JSONArray()).also { learner.put(s.code, it) }
    }

    @Synchronized
    fun level(name: String, s: Subject): Int = subject(name, s).optInt("level", 1)

    /** Коли востаннє був урок цього предмета (null — ще не було). */
    @Synchronized
    fun lastAt(name: String, s: Subject): Long? =
        subject(name, s).optJSONArray("lessons")?.takeIf { it.length() > 0 }?.let { it.getJSONObject(it.length() - 1).optLong("at") }

    /** Останні уроки: "тема — 4 з 5, помилки: …" (для моделі). */
    @Synchronized
    fun history(name: String, s: Subject, max: Int = 8): List<String> {
        val lessons = subject(name, s).optJSONArray("lessons") ?: return emptyList()
        return (maxOf(0, lessons.length() - max) until lessons.length()).map { i ->
            val l = lessons.getJSONObject(i)
            val mistakes = l.optJSONArray("mistakes")?.let { m -> (0 until m.length()).map { m.getString(it) } }.orEmpty()
            "${l.optString("topic")} — ${l.optInt("score")} з ${l.optInt("total")}" +
                if (mistakes.isNotEmpty()) ", помилки: " + mistakes.joinToString("; ") else ""
        }
    }

    /** Урок скінчився: ≥ 80% — рівень вище, < 40% — нижче. Повертає новий рівень. */
    @Synchronized
    fun finish(name: String, s: Subject, topic: String, score: Int, total: Int, mistakes: List<String>, at: Long): Int {
        val o = subject(name, s)
        val lessons = o.optJSONArray("lessons") ?: JSONArray().also { o.put("lessons", it) }
        lessons.put(JSONObject().put("at", at).put("topic", topic).put("score", score).put("total", total).put("mistakes", JSONArray(mistakes.take(5))))
        while (lessons.length() > MAX_LESSONS) lessons.remove(0)
        var level = o.optInt("level", 1)
        if (total > 0 && score * 10 >= total * 8) level++ else if (total > 0 && score * 10 < total * 4) level--
        level = level.coerceIn(1, 10)
        o.put("level", level)
        file?.writeTextAtomic(root.toString())
        return level
    }

    private companion object {
        const val MAX_LESSONS = 30
    }
}

/** Модель складає урок під учня: предмет, рівень, вік, стать, що вже було і де помилявся. */
object SchoolGenerator {
    const val SYSTEM = "Ти добрий вчитель для дитини, урок — голосом (дитина не бачить екрана). " +
        "Відповідай ЛИШЕ одним JSON-об'єктом без markdown."

    fun prompt(s: Subject, level: Int, learner: String, history: List<String>): String = """
        Склади короткий голосовий урок. Предмет: ${s.ua} (${s.about}).
        Учень: $learner. Рівень у цьому предметі: $level з 10 (1 — найпростіше; підбирай складність під рівень і вік).
        Останні уроки (не повторюй теми дослівно; помилки — відпрацюй ще раз інакше): ${history.joinToString(" | ").ifEmpty { "ще не було" }}.
        Формат:
        {"topic":"коротка назва теми українською","intro":"1–2 речення українською, весело",
         "tasks":[{"q":"питання","lang":"${if (s == Subject.MATH_EN) "en" else "uk"}","answers":["усі правильні відповіді: число цифрами, словами${if (s == Subject.MATH_EN) " англійською" else ""}, синоніми"],
                   "hint":"підказка українською, не відповідь","explain":"коротке пояснення правильної відповіді українською"}, …рівно 5]}
        Питання — на слух: коротке, без таблиць, відповідь — одне-два слова чи число.
        Де малюнок допомагає (лічба, порівняння, закономірності, зайве в ряду, букви) — додай до завдання
        "picture": рядки клітинок, напр. [["🍎","🍎","+","🍎"]] чи [["circle:red","square:blue","circle:red","?"]].
        Малюй САМЕ ті предмети, що названі в питанні (груша — 🍐, не інший фрукт), у тому самому порядку.
        Клітинка — один емодзі (прості: фрукти, тварини, ⭐, ❤), одна-дві цифри, букви чи знаки, або фігура
        circle|square|triangle|star|heart з кольором :red|blue|green|yellow|orange|purple|pink|brown|white|gray.
        До 3 рядків, до 8 клітинок у рядку; відповідь малюнок не підказує. Без малюнка — не пиши "picture".
        ${if (s == Subject.MATH_EN) "Питання англійською, напр. \"How much is two plus three?\"; відповіді — англійськими словами і цифрами." else ""}
    """.trimIndent()

    fun parse(text: String): SchoolUnit? = try {
        val o = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
        val arr = o.getJSONArray("tasks")
        val tasks = (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val answers = t.getJSONArray("answers").let { a -> (0 until a.length()).map { a.getString(it) } }.filter { it.isNotBlank() }
            val picture = t.optJSONArray("picture")?.let { rows ->
                (0 until rows.length()).mapNotNull { r -> rows.optJSONArray(r)?.let { row -> (0 until row.length()).map { row.optString(it) }.filter { it.isNotBlank() }.take(MAX_CELLS) } }
                    .filter { it.isNotEmpty() }.take(MAX_ROWS)
            }?.takeIf { it.isNotEmpty() }
            Task(t.getString("q"), t.optString("lang") == "en", answers, t.optString("hint"), t.optString("explain"), picture)
        }.filter { it.q.isNotBlank() && it.answers.isNotEmpty() }
        val unit = SchoolUnit(o.optString("topic"), o.optString("intro"), tasks)
        unit.takeIf { it.topic.isNotBlank() && it.tasks.size in 3..8 }
    } catch (e: Exception) {
        null
    }

    private const val MAX_ROWS = 3
    private const val MAX_CELLS = 8
}

/**
 * Урок (~5 хв): тема від моделі під учня → 5 завдань голосом; помилка — підказка й друга спроба,
 * далі — пояснення. "Досить" чи тричі мовчання — кінець. Рівень — за результатом ([SchoolProgress]).
 */
class SchoolLesson(
    private val subject: Subject,
    private val progress: SchoolProgress,
    private val io: LessonIO,
    private val name: String,
    /** Про учня для моделі: "дівчинка, 6 років" тощо. */
    private val learner: String,
    private val generateJson: suspend (system: String, prompt: String) -> String?,
    private val now: () -> Long,
) {
    var result: String = ""
        private set
    private var silences = 0

    suspend fun run() {
        val level = progress.level(name, subject)
        io.say("Вигадую урок…")
        val unit = generateJson(SchoolGenerator.SYSTEM, SchoolGenerator.prompt(subject, level, learner, progress.history(name, subject)))
            ?.let { SchoolGenerator.parse(it) }
            ?: return io.say("Урок зараз скласти не вийшло. Спробуймо трохи згодом.")
        io.say("${subject.ua.replaceFirstChar { it.uppercase() }}: ${unit.topic}. ${unit.intro}")
        var score = 0
        var total = 0
        val mistakes = mutableListOf<String>()
        for (task in unit.tasks) {
            Log.i("School", "завдання: ${task.q} | відповіді: ${task.answers} | малюнок: ${task.picture ?: "—"}")
            io.show(task.picture)
            ask(task)
            var heard = io.listen(task.english)
            if (stop(heard)) break
            var ok = heard != null && Numbers.matches(heard.texts, task.answers)
            if (!ok) {
                io.say(if (heard == null) "Не розчула. Підказка: ${task.hint}" else "Не зовсім. Підказка: ${task.hint}")
                ask(task)
                heard = io.listen(task.english)
                if (stop(heard)) break
                ok = heard != null && Numbers.matches(heard.texts, task.answers)
            }
            total++
            if (ok) {
                score++
                io.cheer()
                io.say(listOf("Правильно!", "Так!", "Молодець!", "Супер!").random())
            } else {
                io.oops()
                mistakes += task.q.take(60)
                io.say(task.explain.ifBlank { "Правильна відповідь — ${task.answers.first()}." })
            }
            io.show(null)
        }
        io.show(null)
        if (total == 0) return
        val newLevel = progress.finish(name, subject, unit.topic, score, total, mistakes, now())
        val parts = mutableListOf("$score з $total!")
        when {
            newLevel > level -> parts += "Наступного разу — трохи складніше."
            newLevel < level -> parts += "Наступного разу — трохи простіше, потренуємось."
        }
        if (score * 2 >= total) io.cheer()
        result = "${unit.topic}: " + parts.joinToString(" ")
        io.say(parts.joinToString(" "))
    }

    private suspend fun ask(task: Task) = if (task.english) io.sayEnglish(task.q) else io.say(task.q)

    private suspend fun stop(heard: Heard?): Boolean {
        if (heard == null) {
            if (++silences >= 3) { io.say("Здається, ти пішов. Закінчимо урок."); return true }
            return false
        }
        silences = 0
        return heard.texts.any { h -> STOP_WORDS.any { it in h.lowercase() } }
    }

    private companion object {
        val STOP_WORDS = listOf("досить", "стоп", "закінч", "вистачить", "хватит", "stop")
    }
}

/** Урок, який Спіді пропонує сама: англійська (свій курс) чи предмет "школи"; вага — з налаштувань. */
enum class LessonKind(val code: String, val ua: String, val defaultWeight: Int, val subject: Subject?) {
    ENGLISH("english", "урок англійської", 5, null),
    MATH("math", "урок математики", 4, Subject.MATH),
    UKRAINIAN("ukrainian", "урок української", 3, Subject.UKRAINIAN),
    WRITING("writing", "урок письма — обводити літери пальчиком", 3, Subject.WRITING),
    LOGIC("logic", "логічні задачки", 3, Subject.LOGIC),
    WORLD("world", "урок про природу й навколишній світ", 2, Subject.WORLD),
    HISTORY("history", "урок історії", 2, Subject.HISTORY),
    MATH_EN("math_en", "математику англійською", 2, Subject.MATH_EN);

    companion object {
        fun of(subject: Subject) = entries.first { it.subject == subject }

        /** Ваги з налаштувань ("english:5,math:4,…"); чого немає — типова. */
        fun weights(saved: String?): Map<LessonKind, Int> {
            val map = saved.orEmpty().split(',').mapNotNull { part ->
                val (k, v) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                entries.firstOrNull { it.code == k.trim() }?.let { it to (v.trim().toIntOrNull() ?: it.defaultWeight).coerceIn(0, 5) }
            }.toMap()
            return entries.associateWith { map[it] ?: it.defaultWeight }
        }

        fun save(weights: Map<LessonKind, Int>): String = weights.entries.joinToString(",") { "${it.key.code}:${it.value}" }
    }
}

/** Пропозиція уроку дитині: два різні види, випадково з урахуванням ваг (0 — не пропонувати). */
object LessonOffers {
    private val yes = Regex("^(?:так|ага|давай|давайте|хочу|можна|звісно|звичайно|окей|ок|добре|залюбки|yes)(?:\\s|$)")
    private val no = Regex("^(?:ні|не\\s+хочу|не\\s+треба|потім|не\\s+зараз|пізніше|не\\s+буду|досить|no)(?:\\s|$)|не\\s+хочу")
    private val englishWord = Regex("англійськ|english")

    /** Два різні види; [exclude] — недоступні зараз (письмо на колісній платформі). Менше двох — що є. */
    fun choose(weights: Map<LessonKind, Int>, exclude: Set<LessonKind> = emptySet(), random: kotlin.random.Random = kotlin.random.Random): List<LessonKind> {
        val pool = weights.filter { it.value > 0 && it.key !in exclude }.toMutableMap()
        val picked = mutableListOf<LessonKind>()
        repeat(2) {
            val total = pool.values.sum()
            if (total <= 0) return picked
            var r = random.nextInt(total)
            val kind = pool.entries.first { e -> (r < e.value).also { r -= e.value } }.key
            picked += kind
            pool.remove(kind)
        }
        return picked
    }

    sealed interface Reply {
        data class Take(val kind: LessonKind) : Reply
        data object Decline : Reply
    }

    /** Відповідь на пропозицію; null — сказали щось інше (звичайна репліка). */
    fun reply(text: String, offers: List<LessonKind>): Reply? {
        val t = text.lowercase().replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
        if (no.containsMatchIn(t)) return Reply.Decline
        // Назвали урок: "про природу", "англійську", "давай історію", "писати".
        if (englishWord.containsMatchIn(t) && !t.contains("математ")) return Reply.Take(LessonKind.ENGLISH)
        if (t.contains("писа") || t.contains("письм") || t.contains("обвод")) return Reply.Take(LessonKind.WRITING)
        SchoolCommands.parse("урок $t")?.let { return Reply.Take(LessonKind.of(it)) }
        if (t.contains("природ") || t.contains("тварин")) return Reply.Take(LessonKind.WORLD)
        if (t.contains("другий") || t.contains("друге")) offers.getOrNull(1)?.let { return Reply.Take(it) }
        if (yes.containsMatchIn(t) || t.contains("перший") || t.contains("перше")) offers.firstOrNull()?.let { return Reply.Take(it) }
        return null
    }
}
