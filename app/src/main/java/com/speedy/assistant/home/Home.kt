package com.speedy.assistant.home

import com.speedy.assistant.things.ThingMemory
import java.util.Locale

/** Пристрій розумного дому: назва — як у застосунку Smart Life ("Лампа кухня"), [status] — код → значення. */
data class HomeDevice(
    val id: String,
    val name: String,
    val category: String,
    val online: Boolean,
    val status: Map<String, Any?>,
) {
    val kind: HomeKind get() = HomeKind.of(category)

    /** Код вмикання (у світла — switch_led, у розеток — switch_1…); null — не вмикається (датчик). */
    val switchCode: String? get() = SWITCH_CODES.firstOrNull { it in status }
    val brightCode: String? get() = listOf("bright_value_v2", "bright_value").firstOrNull { it in status }
    val isOn: Boolean? get() = switchCode?.let { status[it] as? Boolean }

    /** Небезпечне без людини: замки, ворота, сигналізація, обігрівачі. */
    val dangerous: Boolean get() = kind.dangerous

    companion object {
        val SWITCH_CODES = listOf("switch_led", "switch_1", "switch", "switch_on", "power", "power_go")
    }
}

/** Вид пристрою за категорією Tuya, і як його називають голосом (основи слів). */
enum class HomeKind(val categories: Set<String>, val words: List<String>, val dangerous: Boolean = false) {
    LIGHT(setOf("dj", "dd", "xdd", "fwd", "dc", "tgq", "tgkg", "fsd", "gyd", "tyndj"), listOf("світл", "ламп", "люстр", "торшер", "бра", "підсвіт", "гірлянд", "стрічк", "нічник")),
    SWITCH(setOf("kg", "cz", "pc", "tdq", "cjkg", "znrb", "wkcz"), listOf("розетк", "вимикач", "подовжувач")),
    HEATER(setOf("qn", "rs", "dr", "wk", "wkf", "nnq"), listOf("обігрівач", "батаре", "конвектор", "бойлер", "термостат", "опален", "тепл"), dangerous = true),
    CLIMATE(setOf("kt", "fs", "kj", "jsq", "cs", "xfj", "ktkzq"), listOf("кондиціонер", "вентилятор", "очищувач", "зволожувач", "осушувач", "витяжк")),
    COVER(setOf("cl", "clkg", "mc"), listOf("штор", "жалюз", "ролет", "рольставн")),
    VACUUM(setOf("sd"), listOf("пилосос")),
    SENSOR(setOf("wsdcg", "mcs", "pir", "ywbj", "rqbj", "sj", "co2bj", "hjjcy", "pm25", "ldcg", "cobj", "wnykq"), listOf("датчик", "сенсор", "термометр")),
    LOCK(setOf("ms", "jtmspro", "jtmsbh", "videolock", "bxx"), listOf("замок", "замк", "двері"), dangerous = true),
    ALARM(setOf("mal", "sgbj", "hps"), listOf("сигналізац", "сирен"), dangerous = true),
    GATE(setOf("ckmkzq"), listOf("ворот", "гараж"), dangerous = true),
    OTHER(emptySet(), emptyList());

    companion object {
        fun of(category: String) = entries.firstOrNull { category in it.categories } ?: OTHER
    }
}

/** Голосом без моделі: "увімкни світло на кухні", "притуши лампу", "яка температура в спальні", "що вдома". */
object HomeCommands {
    sealed interface Command {
        data class Power(val targets: List<HomeDevice>, val on: Boolean) : Command
        data class Brightness(val targets: List<HomeDevice>, val percent: Int) : Command
        data class Cover(val targets: List<HomeDevice>, val open: Boolean) : Command
        data class Query(val targets: List<HomeDevice>) : Command
        data object Overview : Command
        /** Кілька однаково підходять — перепитати ("Яке саме: …?"). */
        data class Which(val candidates: List<HomeDevice>, val then: Command) : Command
    }

    private val power = Regex("^(увімкни|ввімкни|включи|запали|вимкни|виключи|погаси|загаси)\\s+(.+)$")
    private val cover = Regex("^(відкрий|відчини|підніми|розсунь|закрий|зачини|опусти|засунь)\\s+(.+)$")
    private val dim = Regex("^(?:притуши|приглуши|зменш\\s+світло)(?:\\s+(.+))?$")
    private val bright = Regex("яскравіст\\p{L}*")
    private val number = Regex("(\\d{1,3})\\s*(?:%|відсот\\p{L}*)?")
    private val climate = Regex("(?:яка|скільки)\\s+(?:зараз\\s+)?(?:температур\\p{L}*|вологіст\\p{L}*)\\s+(.+)$")
    private val contact = Regex("^(?:чи|що\\s+з)\\s+(?:зачинен\\p{L}*\\s+|відчинен\\p{L}*\\s+|закрит\\p{L}*\\s+|відкрит\\p{L}*\\s+)?((?:вікн|двер)\\p{L}*.*)$")
    private val state = Regex("^(?:чи\\s+(?:увімкнен\\p{L}*|ввімкнен\\p{L}*|включен\\p{L}*|вимкнен\\p{L}*|працює|горить)|що\\s+з|який\\s+стан)\\s+(.+)$")
    private val overview = Regex("^(?:що\\s+вдома|стан\\s+(?:розумного\\s+)?дому|які\\s+(?:в\\s+мене\\s+|у\\s+нас\\s+)?(?:є\\s+)?(?:розумні\\s+)?пристрої)$")
    private val all = Regex("(?:^|\\s)(?:все|усе|всі|усі|скрізь|всюди)(?:\\s|$)")
    private val home = Regex("(?:^|\\s)(?:вдома|удома|в\\s+квартирі|в\\s+домі|в\\s+хаті)(?:\\s|$)")
    private val stop = setOf("на", "в", "у", "біля", "все", "усе", "всі", "усі", "будь", "ласка", "мені", "нам", "будь-ласка", "зараз", "там", "до")

    private fun normalize(phrase: String) = phrase.lowercase().replace('ʼ', '\'').replace('’', '\'')
        .replace(Regex("[,!?.]"), " ").replace(Regex("\\s+"), " ").trim().replace(Regex("^спіді\\s+"), "")

    /** Чи варто взагалі дивитися на пристрої (не тягнути їх із хмари на кожну фразу). */
    fun mayBe(phrase: String): Boolean {
        val t = normalize(phrase)
        return power.containsMatchIn(t) || cover.containsMatchIn(t) || dim.containsMatchIn(t) || bright.containsMatchIn(t) ||
            climate.containsMatchIn(t) || contact.containsMatchIn(t) || state.containsMatchIn(t) || overview.matches(t)
    }

    /** null — не про розумний дім (або таких пристроїв немає): хай розбирають інші. */
    fun parse(phrase: String, devices: List<HomeDevice>): Command? {
        if (devices.isEmpty()) return null
        val t = normalize(phrase)
        if (overview.matches(t)) return Command.Overview
        power.find(t)?.let { m ->
            val on = m.groupValues[1].let { it.startsWith("увімк") || it.startsWith("ввімк") || it.startsWith("вкл") || it == "запали" }
            val switchable = devices.filter { it.switchCode != null }
            return pick(m.groupValues[2], switchable) { Command.Power(it, on) }
        }
        cover.find(t)?.let { m ->
            val open = m.groupValues[1] in setOf("відкрий", "відчини", "підніми", "розсунь")
            return pick(m.groupValues[2], devices.filter { it.kind == HomeKind.COVER }) { Command.Cover(it, open) }
        }
        dim.find(t)?.let { m ->
            return pick(m.groupValues[1].ifBlank { "світло" }, lights(devices)) { Command.Brightness(it, DIM_PERCENT) }
        }
        if (bright.containsMatchIn(t)) {
            val pct = number.find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (Regex("макс|повн|яскравіш").containsMatchIn(t)) 100 else return null
            val rest = t.replace(bright, " ").replace(number, " ").replace(Regex("(?:зроби|постав|встанови|на)\\s"), " ")
            return pick(rest.ifBlank { "світло" }, lights(devices)) { Command.Brightness(it, pct.coerceIn(1, 100)) }
        }
        climate.find(t)?.let { m ->
            val sensors = devices.filter { d -> d.status.keys.any { it in TEMP_CODES || it in HUMIDITY_CODES } }
            if (home.containsMatchIn(m.groupValues[1])) return sensors.takeIf { it.isNotEmpty() }?.let { Command.Query(it) }
            return pick(m.groupValues[1], sensors) { Command.Query(it) }
        }
        contact.find(t)?.let { m ->
            val sensors = devices.filter { it.status.containsKey("doorcontact_state") }
            val words = words(m.groupValues[1]).filter { !it.startsWith("вікн") && !it.startsWith("двер") }
            if (words.isEmpty()) return sensors.takeIf { it.isNotEmpty() }?.let { Command.Query(it) }
            return pick(words.joinToString(" "), sensors) { Command.Query(it) }
        }
        state.find(t)?.let { m -> return pick(m.groupValues[1], devices) { Command.Query(it) } }
        return null
    }

    private fun lights(devices: List<HomeDevice>) = devices.filter { it.brightCode != null }

    /**
     * Які пристрої назвали: за словами з назви ("на кухні" ~ "Лампа кухня") і видом ("світло"). "Все світло" —
     * усі такого виду. Нічого не підійшло — null; кілька однаково — перепитати.
     */
    private fun pick(target: String, devices: List<HomeDevice>, make: (List<HomeDevice>) -> Command): Command? {
        if (devices.isEmpty()) return null
        val words = words(target).filter { it !in stop }
        val kind = HomeKind.entries.firstOrNull { k -> words.any { w -> k.words.any { w.startsWith(it) } } }
        fun ofKind(d: HomeDevice): Boolean = kind == null || d.kind == kind ||
            words(d.name).any { n -> kind.words.any { n.startsWith(it) } } // "Світло кухня" на вимикачі (kg)
        val nameWords = words.filter { w -> kind == null || kind.words.none { w.startsWith(it) } }
        val anyOfKind = devices.filter(::ofKind)
        if (anyOfKind.isEmpty()) return null
        // Лише вид, і це слово є в назвах ("лампу" — "Лампа кухня", а не "Світло коридор"; "розетку" — не вимикач).
        val kindWords = words - nameWords.toSet()
        val pool = anyOfKind.filter { d -> kindWords.any { w -> words(d.name).any { ThingMemory.sameWord(it, w) } } }.ifEmpty { anyOfKind }
        if (all.containsMatchIn(target.lowercase())) return if (kind != null) make(anyOfKind) else null
        if (nameWords.isEmpty()) {
            if (kind == null) return null
            return if (pool.size == 1) make(pool) else Command.Which(pool, make(pool))
        }
        // Назвали кімнату чи назву ("світло на кухні") — шукаємо серед усіх цього виду.
        val scored = anyOfKind.map { d -> d to nameWords.count { w -> words(d.name).any { ThingMemory.sameWord(it, w) } } }
        val best = scored.maxOf { it.second }
        if (best == 0) return null
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.size == 1) make(top) else Command.Which(top, make(top))
    }

    /** Вибрали з перепитаного ("на кухні"): ті з [candidates], що підходять за словами; null — не зрозуміла. */
    fun choose(phrase: String, candidates: List<HomeDevice>): List<HomeDevice>? {
        val words = words(normalize(phrase)).filter { it !in stop }
        if (words.isEmpty()) return null
        if (all.containsMatchIn(normalize(phrase))) return candidates
        val chosen = candidates.filter { d -> words.any { w -> words(d.name).any { ThingMemory.sameWord(it, w) } } }
        return chosen.takeIf { it.isNotEmpty() }
    }

    /** Ту саму команду — для інших пристроїв (після перепитування). */
    fun retarget(cmd: Command, targets: List<HomeDevice>): Command = when (cmd) {
        is Command.Power -> cmd.copy(targets = targets)
        is Command.Brightness -> cmd.copy(targets = targets)
        is Command.Cover -> cmd.copy(targets = targets)
        is Command.Query -> cmd.copy(targets = targets)
        else -> cmd
    }

    private fun words(text: String) = ThingMemory.words(text)

    const val DIM_PERCENT = 30
    val TEMP_CODES = listOf("va_temperature", "temp_current", "temp_indoor")
    val HUMIDITY_CODES = listOf("va_humidity", "humidity_value", "humidity_indoor")
}

/** Стан пристроїв словами (назва — з двокрапкою, без відмінювання). */
object HomeText {
    private val uk = Locale("uk", "UA")

    /**
     * Число з урахуванням масштабу Tuya ([scales]: код → scale; 215 зі scale 1 — 21.5). Невідомий масштаб
     * температури: понад 60 — це десяті.
     */
    fun value(code: String, raw: Any?, scales: Map<String, Int>): Double? {
        val n = (raw as? Number)?.toDouble() ?: return null
        val scale = scales[code] ?: if (code in HomeCommands.TEMP_CODES && kotlin.math.abs(n) > 60) 1 else 0
        return n / Math.pow(10.0, scale.toDouble())
    }

    private fun number(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(uk, "%.1f", v)

    fun state(d: HomeDevice, scales: Map<String, Int> = emptyMap()): String {
        if (!d.online) return "${d.name}: не в мережі"
        val parts = mutableListOf<String>()
        d.isOn?.let { on ->
            parts += if (on) "увімкнено" else "вимкнено"
            if (on) d.brightCode?.let { code ->
                val raw = (d.status[code] as? Number)?.toInt()
                val max = if (code == "bright_value_v2") 1000 else 255
                if (raw != null) parts += "яскравість ${(raw * 100 / max).coerceIn(1, 100)} відсотків"
            }
            (d.status["cur_power"] as? Number)?.toDouble()?.let { w -> if (on && w > 0) parts += "споживає ${number(w / 10)} ват" }
        }
        HomeCommands.TEMP_CODES.firstOrNull { it in d.status }?.let { c -> value(c, d.status[c], scales)?.let { parts += "${number(it)} °C" } }
        HomeCommands.HUMIDITY_CODES.firstOrNull { it in d.status }?.let { c -> value(c, d.status[c], scales)?.let { parts += "вологість ${number(it)} відсотків" } }
        (d.status["doorcontact_state"] as? Boolean)?.let { parts += if (it) "відчинено" else "зачинено" }
        (d.status["pir"] as? String)?.let { parts += if (it == "pir") "є рух" else "руху немає" }
        listOf("smoke_sensor_status", "gas_sensor_status", "watersensor_state").firstOrNull { it in d.status }?.let { c ->
            parts += if (d.status[c] == "alarm") "ТРИВОГА" else "усе гаразд"
        }
        (d.status["percent_state"] as? Number)?.let { parts += "відкрито на ${it.toInt()} відсотків" }
        (d.status["battery_percentage"] as? Number)?.toInt()?.let { if (it < 20) parts += "батарейка сідає ($it відсотків)" }
        if (parts.isEmpty()) parts += "на зв'язку"
        return "${d.name}: ${parts.joinToString(", ")}"
    }

    /** Коротко для моделі: "Лампа кухня (світло, увімк.)". */
    fun brief(d: HomeDevice): String {
        val kind = when (d.kind) {
            HomeKind.LIGHT -> "світло"; HomeKind.SWITCH -> "розетка/вимикач"; HomeKind.HEATER -> "обігрів, з PIN"
            HomeKind.CLIMATE -> "клімат"; HomeKind.COVER -> "штори"; HomeKind.VACUUM -> "пилосос"; HomeKind.SENSOR -> "датчик"
            HomeKind.LOCK -> "замок, з PIN"; HomeKind.ALARM -> "сигналізація, з PIN"; HomeKind.GATE -> "ворота, з PIN"
            HomeKind.OTHER -> "пристрій"
        }
        val on = when {
            !d.online -> ", не в мережі"
            d.isOn == true -> ", увімк."
            d.isOn == false -> ", вимк."
            else -> ""
        }
        return "${d.name} ($kind$on)"
    }
}
