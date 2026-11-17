package com.speedy.assistant

/** Команди, які виконуються одразу, без звернення до LLM (і без витрати токенів). */
enum class LocalCommand { CHANGE_PIN, FIND_PHONE, ENGLISH_LESSON, ENGLISH_PROGRESS, ENGLISH_LEVEL, ENGLISH_PLACEMENT, AGE_CHILD, AGE_ADULT, END_CONVERSATION, REMIND, LIST_REMINDERS, CANCEL_REMINDERS, STOP, FIND_ROBOT, CHECK_UPDATE, INSTALL_UPDATE, TIME, BATTERY, INTRODUCE, FORGET_ME, FORGET_NAME, LIST_PEOPLE, WHO_AM_I, ABOUT_ME, WEATHER, WHERE, NAME_PET, CORRECT_NAME }

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
        // Окремим словом: "поклич мене нагадуванням" — не "клич мене <ім'я>".
        "(?<![\\p{L}])(мене\\s+звати|моє\\s+(?:справжнє\\s+)?ім.я|запам.ятай\\s+мене\\s+я|познайомся\\s+я|" +
            "називай\\s+мене|клич\\s+мене|звертайся\\s+до\\s+мене)\\s+(\\p{L}[\\p{L}'’-]*)",
    )
    // "забудь Каву", "забудь ім'я Каву", "видали Каву" — помилкові імена
    private val forgetName = Regex("^(?:забудь|видали|зітри)\\s+(?:ім.я\\s+|людину\\s+)?(\\p{L}[\\p{L}'’-]*)$")
    private val listPeople = Regex("^(?:кого\\s+ти\\s+(?:знаєш|пам.ятаєш)|яких\\s+людей\\s+ти\\s+знаєш|кого\\s+ти\\s+запам.ятала)")

    /** Ім'я з "забудь Каву" (не "забудь мене"). */
    fun parseForgetName(phrase: String): String? =
        forgetName.find(normalize(phrase))?.groupValues?.get(1)?.takeIf { it != "мене" && it != "все" && it != "це" }

    private val forgetMe = Regex("^забудь\\s+(мене|моє\\s+обличчя)")
    private val aboutMe = Regex("що\\s+ти\\s+(?:про\\s+мене\\s+)?(?:знаєш|пам.ятаєш)(?:\\s+про\\s+мене)?|розкажи\\s+(?:що\\s+)?(?:ти\\s+)?знаєш\\s+про\\s+мене")
    private val whoAmI = Regex("^(хто\\s+я|ти\\s+мене\\s+знаєш|ти\\s+мене\\s+пам.ятаєш|як\\s+мене\\s+звати)")
    private val notNames = setOf(
        "не", "ні", "ніяк", "нічого", "неважливо", "секрет", "відстань", "стоп", "так", "що",
        // звичайні слова з коротких відповідей ("мене знаєш", "а ти") — не ім'я
        "мене", "мені", "мій", "моя", "ти", "тебе", "тобі", "а", "і", "й", "знаєш", "знаю", "хочу", "хочеш",
        "можна", "привіт", "добре", "ага", "угу", "так", "ну", "це", "та", "ой", "да", "ще", "вже", "тут",
        // прохання замість імені ("будь ласка, навчи мене…")
        "будь", "ласка", "навчи", "давай", "дай", "покажи", "розкажи",
        // звертання до самої Спіді (як його чують розпізнавачі) — не ім'я людини
        "спіді", "спіди", "спиди", "спід", "спіть", "спить", "спікер", "speedy",
    )

    /** Ім'я з фрази знайомства ("мене звати Тарас") або з відповіді на "як тебе звати?" ("Тарас", "я Тарас"). */
    // "де ми", "де я", "де ти зараз", "де ми знаходимось"
    private val where = Regex("^де\\s+(ми|я|ти)(\\s+(зараз|знаходимо?ся|знаходишся|знаходимось))?$")
    // "Де ти?", "де телефон?", "озвись" — знайти телефон (вібрація й звук), а не "де ми знаходимось".
    private val findPhone = Regex("^(?:де\\s+ти|ти\\s+де|де\\s+(?:мій\\s+)?телефон|знайди\\s+(?:мій\\s+)?телефон|озвись|відгукнись|подай\\s+голос)$")

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
    // Лише явні форми з початку фрази: "це не Костя, (а|це) Ніна", "я не Костя, (а) я Ніна",
    // "мене звати не Костя, а Ніна". Раніше ловило будь-яке "…не X Y" ("котики не п'ють каву").
    private val correction = Regex(
        "^(?:" +
            "це\\s+не\\s+(\\p{L}[\\p{L}'’-]*)\\s+(?:а|це|а\\s+це)\\s+(\\p{L}[\\p{L}'’-]*)" +
            "|я\\s+не\\s+(\\p{L}[\\p{L}'’-]*)\\s+(?:а\\s+)?я\\s+(\\p{L}[\\p{L}'’-]*)" +
            "|мене\\s+звати\\s+не\\s+(\\p{L}[\\p{L}'’-]*)\\s+а\\s+(\\p{L}[\\p{L}'’-]*)" +
            ")$",
    )

    /** "це не Костя, це Ніна" → ("Костя", "Ніна"). */
    fun parseCorrection(phrase: String): Pair<String, String>? {
        val m = correction.find(normalize(phrase)) ?: return null
        // Три форми — три пари груп; заповнена лише одна.
        val groups = m.groupValues.drop(1)
        val pair = groups.chunked(2).firstOrNull { it[0].isNotEmpty() } ?: return null
        val (wrong, right) = pair[0] to pair[1]
        if (right in notNames || right.length < 2 || wrong == right) return null
        return wrong.replaceFirstChar { it.uppercase() } to right.replaceFirstChar { it.uppercase() }
    }

    // Кінець розмови: "дякую, все", "бувай", "на сьогодні все", "можеш відпочити"
    private val endConversation = Regex(
        "^(?:(?:дякую|спасибі|дякс)(?:\\s+(?:все|тобі|це\\s+все|більше\\s+нічого))*|все\\s+дякую|бувай|па-па|па|до\\s+побачення|" +
            "на\\s+сьогодні\\s+все|це\\s+все|більше\\s+нічого|можеш\\s+відпочити|відпочивай)$",
    )

    // "навчи мене англійської" — і як її чує розпізнавач дитини: "навчись будь ласка мене англійської",
    // "будь ласка навчиш мене англійської"; між дієсловом і "англійськ" — до кількох слів-заповнювачів.
    private val englishLesson = Regex(
        "(?:урок|заняття)\\s+англійськ|англійськ\\p{L}*\\s+(?:урок|заняття)" +
            "|(?:^|\\s)(?:навчи|навчиш|навчись|навчіть|навчити|навчай|научи|научиш|повчи|повчиш|повчимо|вчи|вчити|вчимо|вивчати|вивчаймо|вивчимо|" +
            "позаймаємося|позаймайся|займемося|потренуємо|потренуймо)" +
            "(?:\\s+(?:мене|нас|мені|будь|ласка|трохи|ще|разом|зі|з|мною|нами|у|в|по))*\\s+англійськ",
    )
    private val englishProgress = Regex("(?:прогрес|успіхи)\\s+(?:в|з|у)\\s+англійськ|як\\s+(?:в\\s+мене|у\\s+мене|я)\\s+(?:з|в|у)\\s+англійськ")
    private val englishPlacement = Regex("(?:перевір|визнач|протестуй)\\p{L}*\\s+(?:мій\\s+)?рівень\\s+(?:з\\s+)?англійськ")
    private val ageChild = Regex("^(?:це|я|він|вона)\\s+(?:ще\\s+)?(?:дитина|малюк|маленьк\\p{L}*)$")
    private val ageAdult = Regex("^(?:це|я|він|вона)\\s+(?:вже\\s+)?(?:дорослий|доросла|дорослі)$")
    private val englishLevel = Regex("рівень\\s+(?:з\\s+)?англійськ")

    private val remind = Regex("^нагадай\\s")
    // "зміни пін-код", "поміняй пін", "постав новий пін", "хочу змінити пароль"
    private val changePin = Regex("(?:зміни|змінити|змініть|поміняй|поміняти|постав|встанови)\\s+(?:новий\\s+)?(?:пін|pin|пароль|код\\s+батьків)")
    private val listReminders = Regex("^(?:які|що)\\s+(?:в\\s+мене\\s+|у\\s+мене\\s+)?(?:є\\s+)?нагадуван\\p{L}*|^мої\\s+нагадуван\\p{L}*")
    private val cancelReminders = Regex("^(?:скасуй|видали|забудь|очисти)\\s+(?:всі\\s+|усі\\s+)?нагадуван\\p{L}*")

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
            changePin.containsMatchIn(text) -> LocalCommand.CHANGE_PIN
            englishPlacement.containsMatchIn(text) -> LocalCommand.ENGLISH_PLACEMENT
            ageChild.matches(text) -> LocalCommand.AGE_CHILD
            ageAdult.matches(text) -> LocalCommand.AGE_ADULT
            englishLevel.containsMatchIn(text) && com.speedy.assistant.english.Level.fromSpoken(text.substringAfter("англійськ")) != null -> LocalCommand.ENGLISH_LEVEL
            englishProgress.containsMatchIn(text) -> LocalCommand.ENGLISH_PROGRESS
            englishLesson.containsMatchIn(text) -> LocalCommand.ENGLISH_LESSON
            endConversation.matches(text) -> LocalCommand.END_CONVERSATION
            remind.containsMatchIn(text) -> LocalCommand.REMIND
            listReminders.containsMatchIn(text) -> LocalCommand.LIST_REMINDERS
            cancelReminders.containsMatchIn(text) -> LocalCommand.CANCEL_REMINDERS
            text in stop -> LocalCommand.STOP
            findRobot.containsMatchIn(text) -> LocalCommand.FIND_ROBOT
            installUpdate.containsMatchIn(text) -> LocalCommand.INSTALL_UPDATE
            checkUpdate.containsMatchIn(text) -> LocalCommand.CHECK_UPDATE
            namePet.containsMatchIn(text) -> LocalCommand.NAME_PET
            correction.containsMatchIn(text) && parseCorrection(text) != null -> LocalCommand.CORRECT_NAME
            introduce.containsMatchIn(text) -> LocalCommand.INTRODUCE
            forgetMe.containsMatchIn(text) -> LocalCommand.FORGET_ME
            parseForgetName(text) != null -> LocalCommand.FORGET_NAME
            listPeople.containsMatchIn(text) -> LocalCommand.LIST_PEOPLE
            aboutMe.containsMatchIn(text) && "мене" in text -> LocalCommand.ABOUT_ME
            whoAmI.containsMatchIn(text) -> LocalCommand.WHO_AM_I
            findPhone.matches(text) -> LocalCommand.FIND_PHONE
            where.containsMatchIn(text) -> LocalCommand.WHERE
            com.speedy.assistant.weather.WeatherText.isWeatherQuestion(text) -> LocalCommand.WEATHER
            time.containsMatchIn(text) -> LocalCommand.TIME
            // Лише короткі питання: "постав мене на зарядку, а потім…" — уже до моделі.
            battery.containsMatchIn(text) && text.split(' ').size <= 5 -> LocalCommand.BATTERY
            else -> null
        }
    }
}
