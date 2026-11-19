package com.speedy.assistant.things

import com.speedy.assistant.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Річ, яку назвала модель на фото: що, де (словами), ознаки. */
data class Seen(val name: String, val place: String, val details: String = "")

/** Річ у пам'яті. [view] — з якої камери ("front" / "back"); [missed] — скільки оглядів поспіль її не було. */
data class Thing(
    val name: String,
    val place: String,
    val details: String,
    val view: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val missed: Int = 0,
) {
    /** Двічі поспіль не бачила там, де була, — зникла (модель інколи пропускає речі, тож не з першого разу). */
    val gone get() = missed >= GONE_AFTER

    companion object {
        const val GONE_AFTER = 2
    }
}

/** Зміна: [from] null — з'явилась, [to] null — зникла, обидва — переклали. */
data class Change(val name: String, val from: String?, val to: String?, val at: Long)

/**
 * Пам'ять "що де лежить" (`files/things.json`, лише на телефоні): речі з оглядів кімнати моделлю й історія
 * змін. Старше [KEEP_MS] стирається. Час — звичайний годинник (System.currentTimeMillis).
 */
class ThingMemory(private val file: File?) {
    private val things = mutableListOf<Thing>()
    private val changes = mutableListOf<Change>()
    private val scannedViews = mutableSetOf<String>()

    init {
        load()
    }

    /** Огляд кімнати з камери [view]: оновити місця, записати, що з'явилось, перемістилось і зникло. */
    @Synchronized
    fun observe(seen: List<Seen>, view: String, now: Long) {
        val firstScan = view !in scannedViews
        val keys = mutableSetOf<String>()
        for (s in seen.take(MAX_PER_SCAN)) {
            val key = key(s.name)
            if (key.isEmpty() || !keys.add(key)) continue
            val i = things.indexOfFirst { key(it.name) == key }
            if (i < 0) {
                things += Thing(s.name, s.place, s.details, view, now, now)
                // Перший огляд — усе "нове", але це не новина.
                if (!firstScan) changes += Change(s.name, null, s.place, now)
                continue
            }
            val old = things[i]
            when {
                old.gone -> changes += Change(s.name, null, s.place, now)
                !samePlace(old.place, s.place) -> changes += Change(s.name, old.place, s.place, now)
            }
            things[i] = old.copy(name = s.name, place = s.place, details = s.details.ifBlank { old.details }, view = view, lastSeen = now, missed = 0)
        }
        // Що ця камера бачила раніше, а тепер ні.
        for ((i, t) in things.withIndex()) {
            if (t.view != view || key(t.name) in keys || t.gone) continue
            val missed = t.copy(missed = t.missed + 1)
            things[i] = missed
            if (missed.gone) changes += Change(t.name, t.place, null, now)
        }
        scannedViews += view
        prune(now)
        save()
    }

    /** Речі за словами запиту ("мої окуляри" → окуляри): найсвіжіші спершу. */
    @Synchronized
    fun find(query: String): List<Thing> {
        val words = words(query).filter { it !in STOP_WORDS }
        if (words.isEmpty()) return emptyList()
        return things.filter { t -> words(t.name).any { n -> words.any { q -> sameWord(n, q) } } }.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun changesSince(since: Long): List<Change> = changes.filter { it.at >= since }

    /** Коротко для моделі: речі, що зараз на місцях (найсвіжіші). */
    @Synchronized
    fun summary(max: Int = 10): String =
        things.filter { !it.gone }.sortedByDescending { it.lastSeen }.take(max).joinToString(", ") { "${it.name} (${it.place})" }

    /** Назви, які модель уже давала (щоб ті самі речі називала так само). */
    @Synchronized
    fun names(view: String): List<String> = things.filter { it.view == view && !it.gone }.map { it.name }.take(MAX_PER_SCAN)

    @Synchronized
    fun isEmpty() = things.isEmpty()

    @Synchronized
    fun clear() {
        things.clear()
        changes.clear()
        scannedViews.clear()
        save()
    }

    private fun prune(now: Long) {
        things.removeAll { now - it.lastSeen > KEEP_MS }
        changes.removeAll { now - it.at > KEEP_MS }
        while (changes.size > MAX_CHANGES) changes.removeAt(0)
        if (things.size > MAX_THINGS) {
            val keep = things.sortedByDescending { it.lastSeen }.take(MAX_THINGS).toSet()
            things.retainAll(keep)
        }
    }

    private fun load() {
        val f = file?.takeIf { it.exists() } ?: return
        try {
            val json = JSONObject(f.readText())
            json.optJSONArray("things")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run {
                    things += Thing(getString("name"), getString("place"), optString("details"), optString("view", "front"),
                        getLong("first"), getLong("last"), optInt("missed"))
                }
            }
            json.optJSONArray("changes")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run {
                    changes += Change(getString("name"), optString("from").ifEmpty { null }, optString("to").ifEmpty { null }, getLong("at"))
                }
            }
            json.optJSONArray("views")?.let { a -> for (i in 0 until a.length()) scannedViews += a.getString(i) }
        } catch (e: Exception) {
            // Пошкоджений файл — починаємо з нуля (це лише пам'ять речей).
        }
    }

    private fun save() {
        val f = file ?: return
        val json = JSONObject()
            .put("things", JSONArray().apply {
                things.forEach {
                    put(JSONObject().put("name", it.name).put("place", it.place).put("details", it.details).put("view", it.view)
                        .put("first", it.firstSeen).put("last", it.lastSeen).put("missed", it.missed))
                }
            })
            .put("changes", JSONArray().apply {
                changes.forEach { put(JSONObject().put("name", it.name).put("from", it.from ?: "").put("to", it.to ?: "").put("at", it.at)) }
            })
            .put("views", JSONArray(scannedViews.toList()))
        f.writeTextAtomic(json.toString())
    }

    companion object {
        const val KEEP_MS = 30L * 24 * 3600_000
        const val MAX_THINGS = 200
        const val MAX_CHANGES = 300
        const val MAX_PER_SCAN = 30
        private val STOP_WORDS = setOf(
            "де", "мій", "моя", "моє", "мої", "моїх", "мого", "твій", "наш", "наша", "наші", "ти", "не", "бачила", "бачив",
            "лежить", "лежать", "поділ", "подівся", "поділась", "поділися", "куди", "поклала", "поклав", "знаєш", "чи", "а",
            "я", "його", "її", "їх", "ці", "цей", "ця", "той", "та", "те", "там", "зараз",
        )

        fun key(name: String) = words(name).joinToString(" ")

        fun words(text: String): List<String> =
            text.lowercase().replace('ʼ', '\'').split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 2 }

        /** Та сама річ у різних відмінках: "окуляри"/"окулярів", "чашку"/"чашка", "ключі"/"ключів". */
        fun sameWord(a: String, b: String): Boolean {
            val p = a.zip(b).takeWhile { (x, y) -> x == y }.size
            return p >= 4 || (p >= 3 && minOf(a.length, b.length) <= 4) || (a == b)
        }

        /** Те саме місце різними словами ("на столі" / "на письмовому столі біля вікна"). */
        fun samePlace(a: String, b: String): Boolean {
            val x = words(a).filter { it.length > 2 }
            val y = words(b).filter { it.length > 2 }
            if (x.isEmpty() || y.isEmpty()) return key(a) == key(b)
            val common = x.count { w -> y.any { sameWord(w, it) } }
            return common * 2 >= minOf(x.size, y.size) && common > 0
        }
    }
}

/** Огляд кімнати моделлю: інструкція й розбір відповіді. */
object ThingScan {
    const val SYSTEM = "Ти — пам'ять домашнього робота. На фото — кімната з камери робота. Перелічи помітні речі й " +
        "де вони, щоб потім відповісти на \"де мої окуляри?\". Людей, обличчя й тварин не описуй; текст на екранах, " +
        "документах і листах не переписуй. name — 1–2 слова в називному відмінку, без кольору (колір і ознаки — у " +
        "details). place — коротко, відносно меблів чи інших речей: \"на столі біля вікна\", \"на підлозі під стільцем\"; " +
        "не частинами кадру (\"ліворуч у кадрі\", \"угорі\") — людина кадру не бачить. Стіни, стелю, підлогу, двері й " +
        "вікна не перелічуй як речі — лише як орієнтири в place. " +
        "До 25 речей: спершу дрібні переносні (ключі, окуляри, телефон, пульт, іграшки, книжки, чашки), потім меблі. " +
        "Відповідь — лише JSON: {\"things\": [{\"name\": \"…\", \"place\": \"…\", \"details\": \"…\"}]}."

    fun prompt(known: List<String>): String =
        if (known.isEmpty()) "Що де лежить на цьому фото?"
        else "Раніше тут бачила: ${known.joinToString(", ")}. Ті самі речі називай так само. Що де лежить на цьому фото зараз?"

    /** null — відповідь не розібрати; порожній список — речей не видно. */
    fun parse(raw: String): List<Seen>? = try {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = JSONObject(text).optJSONArray("things") ?: JSONArray()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            val place = o.optString("place").trim()
            if (name.length !in 2..40 || place.length !in 2..80) null
            else Seen(name.lowercase(), place, o.optString("details").trim().take(80))
        }
    } catch (e: Exception) {
        null
    }
}

/** Голосом без моделі: "де мої окуляри?", "що змінилось у кімнаті?", "що де лежить?", "забудь, що де лежить". */
object ThingCommands {
    sealed interface Command {
        data class Find(val what: String) : Command
        data object Changes : Command
        data object List : Command
        data object Forget : Command
    }

    private val find = Regex(
        "^(?:а\\s+)?(?:де|куди\\s+(?:я\\s+)?(?:поклала?|поклав|подів(?:ся|ла|ав)?|поділ(?:ися|ась|ось)?)|" +
            "(?:ти\\s+)?не\\s+бачила|чи\\s+(?:ти\\s+)?(?:не\\s+)?бачила)\\s+" +
            "(?!(?:ми|я|ти|мій\\s+телефон|телефон|ми\\s+зараз)(?:\\s|$))(.{2,60})$",
    )
    private val changes = Regex("^що\\s+(?:змінилос[ья]|змінилось|нового|пересунули|переставили)(?:\\s+(?:у|в)\\s+кімнаті)?$")
    private val list = Regex("^(?:що\\s+де\\s+лежить|що\\s+ти\\s+пам'ятаєш\\s+про\\s+(?:речі|кімнату)|які\\s+речі\\s+ти\\s+пам'ятаєш)$")
    private val forget = Regex("^(?:забудь|очисти|видали)[,\\s]+(?:що\\s+де\\s+лежить|(?:пам'ять\\s+)?(?:про\\s+)?речі|пам'ять\\s+речей)$")

    fun parse(phrase: String): Command? {
        val t = phrase.lowercase().replace('ʼ', '\'').replace('’', '\'').replace(Regex("[?!.]"), "").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^спіді[,\\s]+"), "")
        return when {
            forget.matches(t) -> Command.Forget
            changes.matches(t) -> Command.Changes
            list.matches(t) -> Command.List
            else -> find.find(t)?.let { Command.Find(it.groupValues[1].trim()) }
        }
    }
}

/** Відповіді голосом (без моделі). */
object ThingText {
    private val uk = Locale("uk", "UA")

    /** "сьогодні о 14:20", "учора о 9:05", "3 листопада". */
    fun whenSeen(at: Long, now: Long): String {
        val day = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val time = SimpleDateFormat("H:mm", uk).format(Date(at))
        val days = (today.get(Calendar.YEAR) - day.get(Calendar.YEAR)) * 366 + today.get(Calendar.DAY_OF_YEAR) - day.get(Calendar.DAY_OF_YEAR)
        return when (days) {
            0 -> "сьогодні о $time"
            1 -> "учора о $time"
            else -> SimpleDateFormat("d MMMM", uk).format(Date(at))
        }
    }

    fun found(found: List<Thing>, now: Long): String = found.take(3).joinToString(" ") { t ->
        val name = t.name.replaceFirstChar { it.uppercase() }
        // "Назва: …" — без узгодження відмінка ("лампу бачила"): назву модель дає в називному.
        if (t.gone) "$name: бачила ${t.place} ${whenSeen(t.lastSeen, now)}, але згодом там уже не було."
        else "$name: востаннє бачила ${t.place} — ${whenSeen(t.lastSeen, now)}."
    }

    fun changes(list: List<Change>): String {
        if (list.isEmpty()) return "Нічого не помітила — усе на своїх місцях."
        // Без дієслів з родом ("з'явилась/з'явились") — назву модель дає будь-якого роду й числа.
        return list.takeLast(5).joinToString("; ") { c ->
            when {
                c.from == null -> "нове — ${c.name} ${c.to}"
                c.to == null -> "${c.name} — більше не бачу ${c.from}"
                else -> "${c.name}: було ${c.from}, тепер ${c.to}"
            }
        }.replaceFirstChar { it.uppercase() } + "."
    }

    fun list(summary: String): String =
        if (summary.isBlank()) "Я ще не встигла роздивитися кімнату." else "Пам'ятаю: $summary."
}

/**
 * Коли оглядати кімнату: сцена змінилась відносно останнього огляду (мініатюра 16×12, яскравість) і
 * вже не рухається (ніхто не ходить у кадрі), не частіше ніж раз на [minGapMs] і [maxPerHour] на годину.
 */
class SceneChange(
    private val minGapMs: Long = 15 * 60_000L,
    private val maxPerHour: Int = 4,
    private val stableMs: Long = 5_000L,
    private val changed: Int = 18,
    private val still: Int = 6,
) {
    private var reference: IntArray? = null
    private var last: IntArray? = null
    private var stableSince = 0L
    private var withPerson = false
    private val attempts = ArrayDeque<Long>()

    /** true — час оглянути (після спроби — [scanned] чи [failed]). */
    @Synchronized
    fun feed(thumb: IntArray, now: Long, person: Boolean): Boolean {
        val prev = last
        last = thumb
        // Рух чи людина в кадрі (і перший кадр після неї) — відлік "завмерла" починається знову.
        val hadPerson = withPerson
        withPerson = person
        if (person || hadPerson || prev == null || diff(prev, thumb) > still) {
            stableSince = now
            return false
        }
        if (now - stableSince < stableMs) return false
        while (attempts.isNotEmpty() && now - attempts.first() > 3600_000) attempts.removeFirst()
        if (attempts.size >= maxPerHour || attempts.lastOrNull()?.let { now - it < minGapMs } == true) return false
        val ref = reference
        return ref == null || diff(ref, thumb) >= changed
    }

    @Synchronized
    fun scanned(thumb: IntArray, now: Long) {
        reference = thumb
        attempts.addLast(now)
    }

    @Synchronized
    fun failed(now: Long) {
        attempts.addLast(now)
    }

    companion object {
        const val W = 16
        const val H = 12

        /** Середня різниця яскравості (0–255) двох мініатюр. */
        fun diff(a: IntArray, b: IntArray): Int {
            if (a.size != b.size || a.isEmpty()) return 255
            var sum = 0L
            for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
            return (sum / a.size).toInt()
        }
    }
}
