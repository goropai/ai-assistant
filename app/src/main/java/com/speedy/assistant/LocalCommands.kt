package com.speedy.assistant

/** Команди, які виконуються одразу, без звернення до LLM (і без витрати токенів). */
enum class LocalCommand { END_CONVERSATION, STOP, FIND_ROBOT, CHECK_UPDATE, INSTALL_UPDATE, TIME, BATTERY, INTRODUCE, FORGET_ME, WHO_AM_I, WEATHER, WHERE, NAME_PET, CORRECT_NAME }

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
    // "мене звати Тарас", "насправді мене звати Тарас", "називай мене Тарас", "моє справжнє ім'я Тарас",
    // "я не Бетмен я Тарас" — і знайомство, і виправлення жартівливого імені.
    private val introduce = Regex(
        "(мене\\s+звати|моє\\s+(?:справжнє\\s+)?ім.я|запам.ятай\\s+мене\\s+я|познайомся\\s+я|" +
            "називай\\s+мене|клич\\s+мене|звертайся\\s+до\\s+мене)\\s+(\\p{L}[\\p{L}'’-]*)",
    )
    private val forgetMe = Regex("^забудь\\s+(мене|моє\\s+обличчя)")
    private val whoAmI = Regex("^(хто\\s+я|ти\\s+мене\\s+знаєш|ти\\s+мене\\s+пам.ятаєш|як\\s+мене\\s+звати)")
    private val notNames = setOf("не", "ні", "ніяк", "нічого", "неважливо", "секрет", "відстань", "стоп", "так", "що")

    /** Ім'я з фрази знайомства ("мене звати Тарас") або з відповіді на "як тебе звати?" ("Тарас", "я Тарас"). */
    // "де ми", "де я", "де ти зараз", "де ми знаходимось"
    private val where = Regex("^де\\s+(ми|я|ти)(\\s+(зараз|знаходимо?ся|знаходишся|знаходимось))?$")

    // "кота звати Мурчик", "його звати Мурчик", "це наш кіт Мурчик", "назви кота Мурчик"
    private val namePet = Regex(
        "^(?:(?:кота|котика|кицю|кішку|кицьку|собаку|пса|песика|цуценя)\\s+звати|(?:його|її)\\s+звати|" +
            "це\\s+(?:наш|наша|мій|моя)?\\s*(?:кіт|котик|кицька|кішка|киця|пес|песик|собака)|" +
            "назви\\s+(?:кота|кицю|собаку|песика)|кличуть\\s+(?:кота|кицю|собаку|песика))\\s+(\\p{L}[\\p{L}'’-]*)",
    )

    /** Ім'я тварини з фрази ("кота звати Мурчик"). */
    fun parsePetName(phrase: String): String? =
        namePet.find(normalize(phrase))?.groupValues?.get(1)
            ?.takeIf { it !in notNames && it.length >= 2 }?.replaceFirstChar { it.uppercase() }

    // "це не Костя, це Ніна", "я не Бетмен, я Тарас", "мене звати не Костя, а Ніна", "не Костя а Ніна"
    private val correction = Regex(
        "(?:^|\\s)(?:це|я|мене\\s+звати)?\\s*не\\s+(\\p{L}[\\p{L}'’-]*)\\s+(?:а\\s+)?(?:це\\s+|я\\s+)?(\\p{L}[\\p{L}'’-]*)$",
    )

    /** "це не Костя, це Ніна" → ("Костя", "Ніна"). */
    fun parseCorrection(phrase: String): Pair<String, String>? {
        val m = correction.find(normalize(phrase)) ?: return null
        val (wrong, right) = m.destructured
        if (right in notNames || right.length < 2 || wrong == right) return null
        return wrong.replaceFirstChar { it.uppercase() } to right.replaceFirstChar { it.uppercase() }
    }

    // Кінець розмови: "дякую, все", "бувай", "на сьогодні все", "можеш відпочити"
    private val endConversation = Regex(
        "^(?:(?:дякую|спасибі|дякс)(?:\\s+(?:все|тобі|це\\s+все|більше\\s+нічого))*|все\\s+дякую|бувай|па-па|па|до\\s+побачення|" +
            "на\\s+сьогодні\\s+все|це\\s+все|більше\\s+нічого|можеш\\s+відпочити|відпочивай)$",
    )

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
            endConversation.matches(text) -> LocalCommand.END_CONVERSATION
            text in stop -> LocalCommand.STOP
            findRobot.containsMatchIn(text) -> LocalCommand.FIND_ROBOT
            installUpdate.containsMatchIn(text) -> LocalCommand.INSTALL_UPDATE
            checkUpdate.containsMatchIn(text) -> LocalCommand.CHECK_UPDATE
            namePet.containsMatchIn(text) -> LocalCommand.NAME_PET
            correction.containsMatchIn(text) && parseCorrection(text) != null -> LocalCommand.CORRECT_NAME
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
