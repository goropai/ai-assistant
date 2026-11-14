package com.speedy.assistant

/** Команди, які виконуються одразу, без звернення до LLM. */
enum class LocalCommand { STOP, CHECK_UPDATE, INSTALL_UPDATE }

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

    fun match(command: String): LocalCommand? {
        val text = command.lowercase().replace(Regex("[,.!?;:]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            text in stop -> LocalCommand.STOP
            installUpdate.containsMatchIn(text) -> LocalCommand.INSTALL_UPDATE
            checkUpdate.containsMatchIn(text) -> LocalCommand.CHECK_UPDATE
            else -> null
        }
    }
}
