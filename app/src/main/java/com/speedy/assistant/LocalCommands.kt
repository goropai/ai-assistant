package com.speedy.assistant

/** Команди, які виконуються одразу, без звернення до LLM. */
enum class LocalCommand { STOP, FIND_ROBOT, CHECK_UPDATE, INSTALL_UPDATE }

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

    fun match(command: String): LocalCommand? {
        val text = command.lowercase().replace(Regex("[,.!?;:]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            text in stop -> LocalCommand.STOP
            findRobot.containsMatchIn(text) -> LocalCommand.FIND_ROBOT
            installUpdate.containsMatchIn(text) -> LocalCommand.INSTALL_UPDATE
            checkUpdate.containsMatchIn(text) -> LocalCommand.CHECK_UPDATE
            else -> null
        }
    }
}
