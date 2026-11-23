package com.speedy.assistant.brushing

import org.json.JSONObject
import java.util.Calendar

/** Що просять: почати чищення зубів чи розповісти про зірочки. */
object BrushCommands {
    sealed interface Command {
        data object Start : Command
        data object Stars : Command
    }

    private val start = Regex(
        "^(?:я\\s+)?(?:зараз\\s+)?(?:чищу|почищу|буду\\s+чистити|йду\\s+чистити|чистимо|почистимо|почнімо\\s+чистити|почни\\s+чистити|" +
            "давай\\s+(?:почистимо|чистити)|час\\s+чистити|пора\\s+чистити|хочу\\s+(?:почистити|чистити))\\s+(?:\\p{L}+\\s+)?зуб(?:и|ки|оньки)(?:\\s.*)?$", // не \b: для Java кирилиця — не "літери слова"
    )
    private val stars = Regex("(?:скільки|мої|які)\\s+(?:в\\s+мене\\s+|у\\s+мене\\s+)?зіроч\\p{L}*|скільки\\s+днів\\s+(?:я\\s+)?чищу\\s+зуб\\p{L}*")

    fun parse(phrase: String): Command? {
        val t = phrase.lowercase().replace(Regex("[,!?.]"), " ").replace(Regex("\\s+"), " ").trim().replace(Regex("^спіді\\s+"), "")
        if (stars.containsMatchIn(t)) return Command.Stars
        if (start.matches(t)) return Command.Start
        return null
    }
}

/**
 * Дві хвилини чищення: 4 зони по 30 с (як радять стоматологи), що й коли сказати. Порядок зон — як у дзеркалі
 * (права сторона дитини — праворуч на екрані): верх праворуч, верх ліворуч, низ ліворуч, низ праворуч.
 */
object BrushPlan {
    const val TOTAL_S = 120
    const val ZONE_S = 30
    /** Від стількох секунд чищення зараховуємо (дочистили не до кінця — теж молодець, але зірочки немає). */
    const val COUNTS_FROM_S = 90

    enum class Zone(val ua: String, val upper: Boolean, val right: Boolean) {
        UPPER_RIGHT("верхні зубки праворуч", true, true),
        UPPER_LEFT("верхні зубки ліворуч", true, false),
        LOWER_LEFT("нижні зубки ліворуч", false, false),
        LOWER_RIGHT("нижні зубки праворуч", false, true),
    }

    fun zone(second: Int): Zone = Zone.entries[(second / ZONE_S).coerceIn(0, 3)]

    /** Частка зони, яку вже почистили (0..1). */
    fun zoneDone(second: Double, zone: Zone): Float =
        ((second - zone.ordinal * ZONE_S) / ZONE_S).coerceIn(0.0, 1.0).toFloat()

    private val cheers = listOf(
        "Ширк-ширк — мікробики тікають!",
        "Не забудь задні зубки — там мікробики ховаються!",
        "Ох і блищать зубки!",
        "Мікробики кричать: «Рятуйтеся!»",
        "Маленькими колами, легенько-легенько.",
        "Ще трішки — і тут усе сяятиме!",
        "Ти чистиш, як справжній супергерой!",
        "Мікробиків усе менше й менше!",
    )

    /** Що сказати на цій секунді (null — мовчимо). [seed] — щоб фрази не повторювались однаково щоразу. */
    fun line(second: Int, seed: Int = 0): String? = when {
        second == 0 -> "Починаємо! Спершу ${Zone.UPPER_RIGHT.ua}."
        second == TOTAL_S -> null
        second % ZONE_S == 0 -> "А тепер — ${zone(second).ua}!"
        second == TOTAL_S - 10 -> "Ще десять секунд!"
        second % ZONE_S == 15 -> cheers[(second / ZONE_S + seed).mod(cheers.size)]
        else -> null
    }
}

/**
 * Зірочки за чищення (files/brushing.json): ранок і вечір — по одній на день у кожного, серія днів поспіль
 * (хоч раз на день). [read]/[write] — файл; у тестах — пам'ять.
 */
class BrushStars(private val read: () -> String?, private val write: (String) -> Unit) {
    /** Ім'я → дата "2026-11-23" → "m" / "e" / "me" (ранок, вечір). */
    private val data: MutableMap<String, MutableMap<String, String>> = load()

    data class Result(val starsToday: Int, val newStar: Boolean, val streak: Int, val total: Int, val milestone: Int?)

    /** Зарахувати чищення о [at] (мс); дві зірочки на день максимум. */
    fun record(name: String, at: Long): Result {
        val days = data.getOrPut(key(name)) { mutableMapOf() }
        val day = date(at)
        val slot = if (hour(at) < MORNING_UNTIL) "m" else "e"
        val before = days[day].orEmpty()
        val newStar = slot !in before
        if (newStar) days[day] = (before + slot).toList().sorted().joinToString("")
        save()
        val streak = streak(name, at)
        return Result(days[day].orEmpty().length, newStar, streak, total(name), MILESTONES.firstOrNull { it == streak && newStar && before.isEmpty() })
    }

    fun total(name: String): Int = data[key(name)]?.values?.sumOf { it.length } ?: 0

    fun today(name: String, at: Long): Int = data[key(name)]?.get(date(at))?.length ?: 0

    /** Днів поспіль із хоча б одним чищенням (сьогодні ще не чистили — рахуємо до вчора). */
    fun streak(name: String, at: Long): Int {
        val days = data[key(name)] ?: return 0
        val c = Calendar.getInstance().apply { timeInMillis = at }
        if (date(c.timeInMillis) !in days) c.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (date(c.timeInMillis) in days) {
            n++
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }

    private fun key(name: String) = name.lowercase()

    private fun load(): MutableMap<String, MutableMap<String, String>> {
        val out = mutableMapOf<String, MutableMap<String, String>>()
        val json = try { JSONObject(read() ?: return out) } catch (e: Exception) { return out }
        for (name in json.keys()) {
            val days = json.optJSONObject(name) ?: continue
            out[name] = days.keys().asSequence().associateWith { days.optString(it) }.toMutableMap()
        }
        return out
    }

    private fun save() {
        val json = JSONObject()
        data.forEach { (name, days) -> json.put(name, JSONObject(days as Map<*, *>)) }
        write(json.toString())
    }

    companion object {
        /** До цієї години — ранкове чищення. */
        const val MORNING_UNTIL = 14
        /** Серії, за які — особлива похвала. */
        val MILESTONES = listOf(3, 7, 14, 30, 60, 100)

        fun date(at: Long): String = Calendar.getInstance().apply { timeInMillis = at }.let {
            "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
        }

        private fun hour(at: Long) = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
    }
}

/**
 * Казка під час чищення (files/brushing-story.json): у кожного своя — яку читаємо й з якого речення
 * продовжувати; дочитані — щоб наступного разу була нова. [read]/[write] — файл; у тестах — пам'ять.
 */
class BrushStory(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Place(val title: String, val sentence: Int)

    private val data: JSONObject = try { JSONObject(read() ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Яку казку продовжувати (null — нової ще не почали). */
    fun current(name: String): Place? = data.optJSONObject(key(name))?.let { p ->
        p.optString("title").takeIf { it.isNotBlank() }?.let { Place(it, p.optInt("sentence")) }
    }

    /**
     * Яку почати: підготовлену заздалегідь ([upcoming]), інакше першу з [TALES], якої ще не читали; null — нових
     * немає (тоді модель вибере ще не читану, а повтор — лише в крайньому разі: [oldestDone]). [skip] — не знайшлися.
     */
    fun next(name: String, skip: Set<String> = emptySet()): String? {
        val done = done(name)
        upcoming(name)?.takeIf { it !in done && it !in skip }?.let { return it }
        return TALES.firstOrNull { it !in done && it !in skip }
    }

    /** Казка на наступний раз, уже знайдена й збережена (готує Спіді після чищення). */
    fun upcoming(name: String): String? = data.optJSONObject(key(name))?.optString("upcoming")?.takeIf { it.isNotBlank() }

    fun setUpcoming(name: String, title: String) {
        person(name).put("upcoming", title)
        write(data.toString())
    }

    /** Найдавніше дочитана — повторити, лише коли нової не знайти ніяк. */
    fun oldestDone(name: String, skip: Set<String> = emptySet()): String? = done(name).firstOrNull { it !in skip }

    /** Які вже читали (і яку читаємо) — щоб модель їх не пропонувала. */
    fun known(name: String): List<String> = done(name) + listOfNotNull(current(name)?.title, upcoming(name))

    /** Дочитали до речення [sentence] (з нього — наступного разу). */
    fun save(name: String, title: String, sentence: Int) {
        person(name).put("title", title).put("sentence", sentence)
        write(data.toString())
    }

    /** Казку дочитали: наступного разу — нова. */
    fun finish(name: String, title: String) {
        val p = person(name)
        val done = done(name).toMutableList().apply { remove(title); add(title) }
        p.remove("title")
        p.remove("sentence")
        if (p.optString("upcoming") == title) p.remove("upcoming")
        p.put("done", org.json.JSONArray(done))
        write(data.toString())
    }

    private fun done(name: String): List<String> =
        data.optJSONObject(key(name))?.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    private fun person(name: String): JSONObject = data.optJSONObject(key(name)) ?: JSONObject().also { data.put(key(name), it) }

    private fun key(name: String) = name.lowercase()

    companion object {
        /** Завдання моделі: нова казка (не з [known]), коротка, повний текст якої є в інтернеті; JSON {"title"}. */
        fun pickPrompt(known: List<String>) =
            "Назви одну українську народну або класичну дитячу казку для дитини 4–7 років, коротку (3–6 хвилин " +
                "читання), повний текст якої легко знайти в інтернеті українською. Не з цих: " +
                known.joinToString(", ").ifEmpty { "—" } + ". Відповідь — JSON {\"title\": \"назва казки\"}."

        /** Назва з відповіді моделі; null — не розібрали чи вона з уже відомих. */
        fun parsePick(json: String?, known: List<String>): String? {
            val title = try { JSONObject(json ?: return null).optString("title").trim().trim('«', '»', '"', '.') } catch (e: Exception) { return null }
            return title.takeIf { it.length in 2..60 && known.none { k -> k.equals(it, ignoreCase = true) } }
        }

        /** Народні казки — короткі (2–3 чищення), повним текстом є в інтернеті. Для перших казок і без моделі. */
        val TALES = listOf(
            "Колобок", "Рукавичка", "Котик і півник", "Пан Коцький", "Коза-дереза", "Солом'яний бичок",
            "Кривенька качечка", "Лисичка-сестричка і вовк-панібрат", "Колосок", "Івасик-Телесик",
        )
    }
}

/** Слова після чищення (і на "мої зірочки"). */
object BrushText {
    fun days(n: Int) = when {
        n % 100 in 11..14 -> "$n днів"
        n % 10 == 1 -> "$n день"
        n % 10 in 2..4 -> "$n дні"
        else -> "$n днів"
    }

    fun stars(n: Int) = when {
        n % 100 in 11..14 -> "$n зірочок"
        n % 10 == 1 -> "$n зірочка"
        n % 10 in 2..4 -> "$n зірочки"
        else -> "$n зірочок"
    }

    /** [female] — для роду ("отримала"); null — невідомо (тоді без дієслова в минулому). */
    fun finished(r: BrushStars.Result, female: Boolean?): String = buildString {
        append("Готово! Усі мікробики переможені, зубки сяють! ")
        if (r.newStar) append("Тримай зірочку! ")
        else append(
            when (female) {
                true -> "Зірочку за цей раз ти вже отримала, але чисті зубки — це завжди чудово! "
                false -> "Зірочку за цей раз ти вже отримав, але чисті зубки — це завжди чудово! "
                null -> "Зірочка за цей раз уже є, але чисті зубки — це завжди чудово! "
            },
        )
        r.milestone?.let { append("Ого, ${days(it)} поспіль! Ти справжній герой чистих зубів! ") }
            ?: if (r.streak >= 2) append("Уже ${days(r.streak)} поспіль. ") else Unit
        append("Усього в тебе ${stars(r.total)}.")
    }.trim()

    /** Перед першим реченням казки. */
    fun taleStart(title: String, continued: Boolean) =
        if (continued) "А поки чистимо — продовжуємо казку «$title»." else "А поки чистимо — казка «$title»!"

    /** Час вийшов, а казка ще не скінчилась. */
    const val TO_BE_CONTINUED = "Ой, а що ж було далі? Дізнаємось наступного разу, як чиститимемо зубки!"
    const val TALE_END = "От і казці кінець!"
    const val ASK_FINISH = "А казки залишилось зовсім трохи. Дочитати до кінця?"
    const val LATER = "Добре, дочитаємо наступного разу!"

    /** Казки лишилось не більше стількох символів (~пів хвилини читання) — питаємо, чи дочитати. */
    const val FINISH_MAX_CHARS = 700

    fun littleLeft(sentences: List<String>, from: Int) = sentences.drop(from).sumOf { it.length } in 1..FINISH_MAX_CHARS

    /** Відповідь "дочитати?": true — так, false — ні (чи "потім"), null — не зрозуміли. Спершу "ні" ("не треба"). */
    fun yes(texts: List<String>?): Boolean? {
        for (t in texts.orEmpty()) {
            val p = " " + t.lowercase().replace(Regex("[,!?.]"), " ").replace(Regex("\\s+"), " ").trim() + " "
            if (Regex(" (ні|нє|не треба|не хочу|потім|наступного разу|завтра|досить) ").containsMatchIn(p)) return false
            if (Regex(" (так|ага|угу|давай|дочитай|дочитуй|хочу|звісно|звичайно|да|читай) ").containsMatchIn(p)) return true
        }
        return null
    }

    fun summary(total: Int, streak: Int, today: Int): String = when {
        total == 0 -> "Зірочок ще немає. Скажи «Спіді, я чищу зуби» — і заробимо першу!"
        else -> "У тебе ${stars(total)}. " +
            (if (streak >= 2) "Чистиш зубки ${days(streak)} поспіль. " else "") +
            when (today) {
                0 -> "Сьогодні ще не чистили!"
                1 -> "Сьогодні вже одна зірочка — увечері буде друга."
                else -> "Сьогодні обидві зірочки вже є!"
            }
    }
}

/** Екран під час чищення: зуби з мікробиками, поточна зона, загальний час. */
interface BrushScreen {
    fun show()
    /** [zone] — поточна, [zoneDone] — частка почищеного в ній, [total] — частка двох хвилин. */
    fun update(zone: BrushPlan.Zone, zoneDone: Float, total: Float)
    fun hide()
}
