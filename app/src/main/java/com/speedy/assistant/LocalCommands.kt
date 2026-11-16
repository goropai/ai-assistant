package com.speedy.assistant

/** Команди, які виконуються одразу, без звернення до LLM (і без витрати токенів). */
enum class LocalCommand { STOP, FIND_ROBOT, CHECK_UPDATE, INSTALL_UPDATE, TIME, BATTERY, INTRODUCE, FORGET_ME, WHO_AM_I, WEATHER, WHERE }

object LocalCommands {

    private val stop = setOf("стоп", "стій", "зупинись", "зупинися", "stop")

    // "перевір оновлення", "перевіри оновлення", "чи є оновлення", "є нова версія?"
    private val checkUpdate = Regex(
        "(перев[іi]р\\p{L}*|глянь|подивись)\\s+(чи\\s+є\\s+)?(оновлен\\p{L}*|нов\\p{L}+\\s+верс\\p{L}*)" +
            "|^(чи\\s+)?є\\s+(оновлен\\p{L}*|нов\\p{L}+\\s+верс\\p{L}*)" +
            "|^яка\\s+(в\\s+тебе\\s+)?верс\\p{L}*",
    )

    // "оновись", "оновися", "онови застосунок", "встанови оновлення", "обнови"
    private val installUpdate = Regex(
        "^(онови(сь|ся)?|обнови(сь|ся)?|оновитись|оновитися)(\\s+(застосунок|програму|додаток|себе|прошивку))?$" +
            "|(встанови|завантаж|постав)\\p{L}*\\s+(оновлен\\p{L}*|нов\\p{L}+\\s+верс\\p{L}*)",
    )

    // "знайди платформу", "шукай робота", "підключись до платформи"
    private val findRobot = Regex(
        "^(знайди|шукай|пошукай|підключи(сь|ся)?(\\s+до)?|з'єднайся\\s+з)\\s+(платформ|робот|колес|шасі|машин)\\p{L}*",
    )

    // "котра година", "скільки зараз часу", "який час"
    private val time = Regex("^(котра|яка)\\s+(зараз\\s+)?година|^скільки\\s+(зараз\\s+)?час\\p{L}*|^який\\s+(зараз\\s+)?час")

    // "скільки заряду", "який у тебе заряд", "як батарея"
    private val battery = Regex("заряд\\p{L}*|батаре\\p{L}*|акумулятор\\p{L}*")

    // "мене звати Тарас", "моє ім'я Тарас", "запам'ятай мене, я Тарас"
    private val introduce = Regex("(мене\\s+звати|моє\\s+ім.я|запам.ятай\\s+мене\\s+я|познайомся\\s+я)\\s+(\\p{L}[\\p{L}'’-]*)")
    private val forgetMe = Regex("^забудь\\s+(мене|моє\\s+обличчя)")
    private val whoAmI = Regex("^(хто\\s+я|ти\\s+мене\\s+знаєш|ти\\s+мене\\s+пам.ятаєш|як\\s+мене\\s+звати)")
    private val notNames = setOf("не", "ні", "ніяк", "нічого", "неважливо", "секрет", "відстань", "стоп", "так", "що")

    /** Ім'я з фрази знайомства ("мене звати Тарас") або з відповіді на "як тебе звати?" ("Тарас", "я Тарас"). */
    // "де ми", "де я", "де ти зараз", "де ми знаходимось"
    private val where = Regex("^де\\s+(ми|я|ти)(\\s+(зараз|знаходимо?ся|знаходишся|знаходимось))?$")

    fun parseName(phrase: String, answer: Boolean = false): String? {
        val text = normalize(phrase)
        val word = introduce.find(text)?.groupValues?.get(2)
            ?: if (answer) {
                val words = text.split(' ').filter { it.isNotBlank() }
                when {
                    words.size >= 2 && words[0] == "я" -> words[1]
                    words.size in 1..2 -> words[0]
                    else -> null
                }
            } else null
        return word?.takeIf { it !in notNames && it.length >= 2 }?.replaceFirstChar { it.uppercase() }
    }

    private fun normalize(command: String) =
        command.lowercase().replace(Regex("[,.!?;:—]"), " ").replace(Regex("\\s+"), " ").trim()

    fun match(command: String): LocalCommand? {
        val text = normalize(command)
        return when {
            text in stop -> LocalCommand.STOP
            findRobot.containsMatchIn(text) -> LocalCommand.FIND_ROBOT
            installUpdate.containsMatchIn(text) -> LocalCommand.INSTALL_UPDATE
            checkUpdate.containsMatchIn(text) -> LocalCommand.CHECK_UPDATE
            introduce.containsMatchIn(text) -> LocalCommand.INTRODUCE
            forgetMe.containsMatchIn(text) -> LocalCommand.FORGET_ME
            whoAmI.containsMatchIn(text) -> LocalCommand.WHO_AM_I
            where.containsMatchIn(text) -> LocalCommand.WHERE
            com.speedy.assistant.weather.WeatherText.isWeatherQuestion(text) -> LocalCommand.WEATHER
            time.containsMatchIn(text) -> LocalCommand.TIME
            // Лише короткі питання: "постав мене на зарядку, а потім…" — уже до моделі.
            battery.containsMatchIn(text) && text.split(' ').size <= 5 -> LocalCommand.BATTERY
            else -> null
        }
    }
}
