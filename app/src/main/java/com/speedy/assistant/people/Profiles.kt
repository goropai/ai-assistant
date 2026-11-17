package com.speedy.assistant.people

import com.speedy.assistant.util.writeTextAtomic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Локальний контекст про кожного знайомого: факти ("любить каву"), звички (коли буває
 * поруч), одяг (колір верху за камерою). Збирається здебільшого без моделі; моделі
 * дістається лише коротка довідка про співрозмовника. Лише на телефоні (files/profiles.json).
 */
class Profiles(private val file: File?, private val zone: TimeZone = TimeZone.getDefault()) {

    class Profile(
        val facts: MutableList<String> = mutableListOf(),
        /** Скільки разів бачила в кожну годину доби — звідси "зазвичай ввечері". */
        val hours: IntArray = IntArray(24),
        var visits: Int = 0,
        var firstSeen: Long = 0,
        var lastSeen: Long = 0,
        /** Колір верху й коли його бачила. */
        var clothing: String? = null,
        var clothingAt: Long = 0,
        /** Колір верху з попереднього дня — щоб помітити зміну. */
        var previousClothing: String? = null,
        /** Коли закінчився попередній візит (для "давно не бачились" на початку нового). */
        var previousVisitEnd: Long = 0,
        /** День останнього ранкового підсумку. */
        var briefedDay: Int = 0,
        /** Сума й кількість замірів положення очей (оцінка віку за обличчям). */
        var eyeSum: Float = 0f,
        var eyeCount: Int = 0,
        /** Сказали вголос: "це дитина" / "це дорослий" — важливіше за оцінку. */
        var ageOverride: String? = null,
        /** Висота голосу (Гц): сума й кількість замірів. */
        var voiceSum: Float = 0f,
        var voiceCount: Int = 0,
    )

    enum class Age { CHILD, ADULT, UNKNOWN }

    private val people = LinkedHashMap<String, Profile>()

    init {
        load()
    }

    @Synchronized
    fun names(): Set<String> = people.keys.toSet()

    /** Людина поруч. Візит рахуємо, якщо не бачила її понад [VISIT_GAP_MS]. */
    @Synchronized
    fun seen(name: String, now: Long) {
        val p = people.getOrPut(name) { Profile(firstSeen = now) }
        if (now - p.lastSeen > VISIT_GAP_MS) {
            p.previousVisitEnd = p.lastSeen
            p.visits++
            p.hours[hourOf(now)]++
        }
        p.lastSeen = now
        save()
    }

    /** Колір верхнього одягу; новий день — попередній колір запам'ятовуємо для порівняння. */
    @Synchronized
    fun clothing(name: String, color: String, now: Long) {
        val p = people.getOrPut(name) { Profile(firstSeen = now) }
        if (p.clothing != null && dayOf(p.clothingAt) != dayOf(now)) p.previousClothing = p.clothing
        if (p.clothing == color && now - p.clothingAt < 60_000) return
        p.clothing = color
        p.clothingAt = now
        save()
    }

    /** @return true — новий факт. Схожий старий факт ("любить чай" → "не любить чай") замінюється. */
    @Synchronized
    fun addFact(name: String, fact: String, now: Long = 0): Boolean {
        val f = fact.trim().trimEnd('.').take(MAX_FACT)
        if (f.length < 3) return false
        val p = people.getOrPut(name) { Profile(firstSeen = now) }
        if (p.facts.any { it.equals(f, ignoreCase = true) }) return false
        val topic = topicOf(f)
        if (topic != null) p.facts.removeAll { topicOf(it) == topic }
        p.facts += f
        while (p.facts.size > MAX_FACTS) p.facts.removeAt(0)
        save()
        return true
    }

    /** Коротка довідка для моделі (у повідомленні, а не в системному промпті — кеш не страждає). */
    @Synchronized
    fun summary(name: String, now: Long): String {
        val p = people[name] ?: return ""
        val parts = mutableListOf<String>()
        parts += p.facts.takeLast(8)
        habit(p)?.let { parts += it }
        // Одяг моделі не передаємо: інакше вона говорить про нього щоразу (лишається в describe і вітанні).
        absence(p, now)?.let { parts += it }
        return parts.joinToString("; ")
    }

    /** Що Спіді знає про людину — голосом, без моделі. */
    @Synchronized
    fun describe(name: String, now: Long): String {
        val p = people[name] ?: return "Я ще мало про тебе знаю. Розкажи щось про себе!"
        val parts = mutableListOf<String>()
        if (p.facts.isNotEmpty()) parts += "Ти " + p.facts.takeLast(5).joinToString(", ") { secondPerson(it) }
        habit(p)?.let { parts += "Зазвичай ти поруч ${it.removePrefix("зазвичай буває ")}" }
        if (p.clothing != null && dayOf(p.clothingAt) == dayOf(now)) parts += "Сьогодні ти в ${p.clothing}"
        if (p.visits > 1) parts += "Ми бачились уже ${p.visits} ${plural(p.visits, "раз", "рази", "разів")}"
        return if (parts.isEmpty()) "Я ще мало про тебе знаю. Розкажи щось про себе!" else parts.joinToString(". ") + "."
    }

    /** Привід для живого вітання: давно не бачились або новий колір одягу. */
    @Synchronized
    fun greetingHint(name: String, now: Long): String? {
        val p = people[name] ?: return null
        val days = if (p.previousVisitEnd > 0) (now - p.previousVisitEnd) / DAY_MS else 0
        if (days >= 3) return "Давно не бачились!"
        val today = p.clothing?.takeIf { dayOf(p.clothingAt) == dayOf(now) }
        if (today != null && p.previousClothing != null && today != p.previousClothing) return "Ти сьогодні в ${today}!"
        return null
    }

    /** Замір положення очей (див. FaceObservation.eyeLevel); зберігаємо не частіше разу на хвилину. */
    @Synchronized
    fun faceSample(name: String, eyeLevel: Float, now: Long) {
        val p = people.getOrPut(name) { Profile(firstSeen = now) }
        if (p.eyeCount >= MAX_EYE_SAMPLES) return
        p.eyeSum += eyeLevel
        p.eyeCount++
        save()
    }

    /** Замір висоти голосу людини, що говорить. */
    @Synchronized
    fun voiceSample(name: String, hz: Float, now: Long) {
        val p = people.getOrPut(name) { Profile(firstSeen = now) }
        if (p.voiceCount >= MAX_EYE_SAMPLES) return
        p.voiceSum += hz
        p.voiceCount++
        save()
    }

    /**
     * Дитина чи дорослий: спершу сказане вголос; далі голос (від 3 фраз) — він розрізняє надійно;
     * обличчя — лише явно дитяче (положення очей у дітей і дорослих на цій камері перетинається).
     */
    @Synchronized
    fun age(name: String): Age {
        val p = people[name] ?: return Age.UNKNOWN
        p.ageOverride?.let { return Age.valueOf(it) }
        if (p.voiceCount >= 3) {
            val hz = p.voiceSum / p.voiceCount
            if (hz >= com.speedy.assistant.voice.Pitch.CHILD_HZ) return Age.CHILD
            if (hz <= com.speedy.assistant.voice.Pitch.ADULT_HZ) return Age.ADULT
        }
        if (p.eyeCount >= 5 && p.eyeSum / p.eyeCount >= CHILD_EYE_LEVEL) return Age.CHILD
        return Age.UNKNOWN
    }

    @Synchronized
    fun voiceHz(name: String): Float? = people[name]?.takeIf { it.voiceCount > 0 }?.let { it.voiceSum / it.voiceCount }

    @Synchronized
    fun eyeLevel(name: String): Float? = people[name]?.takeIf { it.eyeCount > 0 }?.let { it.eyeSum / it.eyeCount }

    @Synchronized
    fun setAge(name: String, age: Age) {
        people.getOrPut(name) { Profile() }.ageOverride = age.name
        save()
    }

    /** Чи пора ранкового підсумку (5:00–12:00, раз на день); якщо так — позначаємо. */
    @Synchronized
    fun takeMorningBriefing(name: String, now: Long): Boolean {
        val p = people.getOrPut(name) { Profile(firstSeen = now) }
        if (hourOf(now) !in 5..11 || p.briefedDay == dayOf(now)) return false
        p.briefedDay = dayOf(now)
        save()
        return true
    }

    @Synchronized
    fun rename(old: String, new: String) {
        val from = people.remove(old) ?: return
        val to = people[new]
        if (to == null) {
            people[new] = from
        } else {
            from.facts.forEach { if (it !in to.facts) to.facts += it }
            for (h in 0 until 24) to.hours[h] += from.hours[h]
            to.visits += from.visits
            to.lastSeen = maxOf(to.lastSeen, from.lastSeen)
        }
        save()
    }

    @Synchronized
    fun forget(name: String) {
        if (people.remove(name) != null) save()
    }

    private fun habit(p: Profile): String? {
        if (p.visits < 5) return null
        val parts = mapOf("вранці" to 5..11, "вдень" to 12..16, "ввечері" to 17..22, "вночі" to listOf(23, 0, 1, 2, 3, 4))
        val counts = parts.mapValues { (_, hours) -> hours.sumOf { p.hours[it] } }
        val (part, count) = counts.maxByOrNull { it.value } ?: return null
        return if (count * 2 >= p.visits) "зазвичай буває $part" else null
    }

    private fun absence(p: Profile, now: Long): String? {
        val days = (now - p.lastSeen) / DAY_MS
        return if (p.lastSeen > 0 && days >= 3) "не бачились $days ${plural(days.toInt(), "день", "дні", "днів")}" else null
    }

    private fun hourOf(t: Long) = Calendar.getInstance(zone).apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
    private fun dayOf(t: Long) = Calendar.getInstance(zone).apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }

    private fun load() {
        val f = file?.takeIf { it.exists() } ?: return
        try {
            val json = JSONObject(f.readText())
            for (name in json.keys()) {
                val o = json.getJSONObject(name)
                val facts = o.optJSONArray("facts") ?: JSONArray()
                val hours = o.optJSONArray("hours") ?: JSONArray()
                people[name] = Profile(
                    facts = MutableList(facts.length()) { facts.getString(it) },
                    hours = IntArray(24) { hours.optInt(it) },
                    visits = o.optInt("visits"),
                    firstSeen = o.optLong("firstSeen"),
                    lastSeen = o.optLong("lastSeen"),
                    clothing = o.optString("clothing").ifBlank { null },
                    clothingAt = o.optLong("clothingAt"),
                    previousClothing = o.optString("previousClothing").ifBlank { null },
                    previousVisitEnd = o.optLong("previousVisitEnd"),
                    briefedDay = o.optInt("briefedDay"),
                    eyeSum = o.optDouble("eyeSum", 0.0).toFloat(),
                    eyeCount = o.optInt("eyeCount"),
                    ageOverride = o.optString("age").ifBlank { null },
                    voiceSum = o.optDouble("voiceSum", 0.0).toFloat(),
                    voiceCount = o.optInt("voiceCount"),
                )
            }
        } catch (e: Exception) {
            people.clear()
        }
    }

    private fun save() {
        val f = file ?: return
        val json = JSONObject()
        people.forEach { (name, p) ->
            json.put(
                name, JSONObject()
                    .put("facts", JSONArray(p.facts))
                    .put("hours", JSONArray(p.hours.toList()))
                    .put("visits", p.visits)
                    .put("firstSeen", p.firstSeen)
                    .put("lastSeen", p.lastSeen)
                    .put("clothing", p.clothing.orEmpty())
                    .put("clothingAt", p.clothingAt)
                    .put("previousClothing", p.previousClothing.orEmpty())
                    .put("previousVisitEnd", p.previousVisitEnd)
                    .put("briefedDay", p.briefedDay)
                    .put("eyeSum", p.eyeSum.toDouble())
                    .put("eyeCount", p.eyeCount)
                    .put("age", p.ageOverride.orEmpty())
                    .put("voiceSum", p.voiceSum.toDouble())
                    .put("voiceCount", p.voiceCount)
            )
        }
        f.writeTextAtomic(json.toString())
    }

    companion object {
        const val VISIT_GAP_MS = 30 * 60_000L
        const val DAY_MS = 24 * 3600_000L
        const val MAX_FACTS = 30
        const val MAX_FACT = 140
        const val MAX_EYE_SAMPLES = 200
        /**
         * Положення очей у рамці ML Kit (0 — верх, 1 — низ). Дорослий на камері Спіді: 0.406–0.430
         * (середнє 0.417, заміряно 16.11.2026); у дітей очі нижче (більший лоб) — поріг наближений.
         * Між порогами — "невідомо" (вирішує перевірка рівня); надійніше — "це дитина" вголос.
         */
        const val ADULT_EYE_LEVEL = 0.44f
        const val CHILD_EYE_LEVEL = 0.47f

        /** "любить каву" і "не любить каву" — про одне; новий факт замінює старий. */
        fun topicOf(fact: String): String? {
            val m = Regex("^(?:не\\s+)?(?:любить|подобається|подобаються)\\s+(.+)$").find(fact.lowercase()) ?: return null
            return "likes:" + m.groupValues[1]
        }

        /** "любить каву" → "любиш каву". */
        fun secondPerson(fact: String): String {
            // \b у Java не бачить меж кириличних слів — межі задаємо через "не літера" поруч.
            fun word(w: String) = Regex("(?<!\\p{L})$w(?!\\p{L})")
            return fact
                .replace(word("любить"), "любиш")
                .replace(word("працює"), "працюєш")
                .replace(word("живе"), "живеш")
                .replace(word("грає"), "граєш")
                .replace(word("вчиться"), "вчишся")
                .replace(Regex("^є\\s+"), "у тебе є ")
        }

        fun plural(n: Int, one: String, few: String, many: String) = when {
            n % 100 in 11..14 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
    }
}
