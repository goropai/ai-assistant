package com.speedy.assistant.settings

/** Зміни налаштувань голосом — лише після PIN батьків (і не від моделі). */
sealed interface SettingCommand {
    /** [which]: touch | cheer | quiet | find | all. */
    data class Vibration(val which: String, val on: Boolean) : SettingCommand
    data class Presence(val on: Boolean) : SettingCommand
    /** Хвилин на день для дитини; 0 — без обмеження. */
    data class ChildLimit(val minutes: Int) : SettingCommand
    /** claude | gemini */
    data class Provider(val name: String) : SettingCommand
}

object SettingCommands {
    private const val ON = "увімкни|включи|ввімкни"
    private const val OFF = "вимкни|виключи|відключи"
    private val vibration = Regex("^($ON|$OFF)\\s+(?:всю\\s+|усю\\s+)?вібр\\p{L}*(?:\\s+(?:на|при|для|в|у|під\\s+час)\\s+(.+))?$")
    private val presence = Regex("^($ON|$OFF)\\s+(?:камеру|камери|помічання\\s+людей|розпізнавання\\s+облич)$|^(не\\s+)?помічай\\s+(?:людей|нікого)$")
    private val limit = Regex("^(?:постав|встанови|зроби|зміни)\\s+(?:денний\\s+)?(?:дитячий\\s+)?ліміт\\s+(?:для\\s+)?(?:дитини\\s+|дітей\\s+)?(?:на\\s+)?(\\d{1,3})\\s*хвилин\\p{L}*$")
    private val noLimit = Regex("^(?:$OFF|прибери|зніми)\\s+(?:денний\\s+)?(?:дитячий\\s+)?ліміт(?:\\s+(?:для\\s+)?(?:дитини|дітей))?$")
    private val provider = Regex("^(?:перемкнись|перейди|переключись|перемкни\\s+мозок|переключи\\s+мозок)\\s+на\\s+(клод\\p{L}*|claude|джеміні|джемені|gemini)$")

    fun parse(phrase: String): SettingCommand? {
        val t = phrase.lowercase().replace(Regex("[,!?«»\"]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        vibration.find(t)?.let { m ->
            val on = m.groupValues[1] in ON.split('|')
            val q = m.groupValues[2]
            val which = when {
                q.isEmpty() -> "all"
                Regex("дотик|звертан|ім.я").containsMatchIn(q) -> "touch"
                Regex("урок|відповід|навчан").containsMatchIn(q) -> "cheer"
                Regex("нагадуван|вночі|ніч|тиш").containsMatchIn(q) -> "quiet"
                Regex("пошук|знайти|де\\s").containsMatchIn(q) -> "find"
                else -> return null
            }
            return SettingCommand.Vibration(which, on)
        }
        presence.find(t)?.let { m ->
            return SettingCommand.Presence(
                if (m.groupValues[1].isNotEmpty()) m.groupValues[1] in ON.split('|') else m.groupValues[2].isEmpty()
            )
        }
        limit.find(t)?.let { return SettingCommand.ChildLimit(it.groupValues[1].toInt().coerceIn(0, 600)) }
        if (noLimit.matches(t)) return SettingCommand.ChildLimit(0)
        provider.find(t)?.let { m -> return SettingCommand.Provider(if (m.groupValues[1].startsWith("кл") || m.groupValues[1] == "claude") "claude" else "gemini") }
        return null
    }

    /** "вібрацію на дотик вимкнено" — для підтвердження вголос. */
    fun describe(cmd: SettingCommand): String = when (cmd) {
        is SettingCommand.Vibration -> {
            val what = when (cmd.which) {
                "touch" -> "вібрацію на дотик"; "cheer" -> "вібрацію в уроках"
                "quiet" -> "вібрацію для нагадувань уночі"; "find" -> "вібрацію для пошуку телефона"
                else -> "усю вібрацію"
            }
            "$what ${if (cmd.on) "увімкнено" else "вимкнено"}"
        }
        is SettingCommand.Presence -> if (cmd.on) "камера знову помічає людей і тварин" else "камера більше не помічає людей і тварин"
        is SettingCommand.ChildLimit -> if (cmd.minutes == 0) "дитячий ліміт знято" else "дитячий ліміт — ${cmd.minutes} хвилин на день"
        is SettingCommand.Provider -> "тепер мій мозок — ${if (cmd.name == "claude") "Claude" else "Gemini"}"
    }

    /** Застосувати; повертає пояснення, якщо не можна (немає ключа). */
    fun apply(cmd: SettingCommand, s: AppSettings): String? {
        when (cmd) {
            is SettingCommand.Vibration -> when (cmd.which) {
                "touch" -> s.vibrateTouch = cmd.on
                "cheer" -> s.vibrateCheer = cmd.on
                "quiet" -> s.vibrateQuiet = cmd.on
                "find" -> s.vibrateFind = cmd.on
                else -> { s.vibrateTouch = cmd.on; s.vibrateCheer = cmd.on; s.vibrateQuiet = cmd.on; s.vibrateFind = cmd.on }
            }
            is SettingCommand.Presence -> s.presenceEnabled = cmd.on
            is SettingCommand.ChildLimit -> s.childLimitMinutes = cmd.minutes
            is SettingCommand.Provider -> {
                val key = if (cmd.name == "claude") s.anthropicKey else s.geminiKey
                if (key.isBlank()) return "Для цього потрібен ключ ${if (cmd.name == "claude") "Anthropic" else "Gemini"} — додай його в налаштуваннях."
                s.provider = cmd.name
            }
        }
        return null
    }
}
