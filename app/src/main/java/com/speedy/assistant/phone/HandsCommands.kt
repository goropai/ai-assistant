package com.speedy.assistant.phone

/** Голосові команди сповіщень і керування застосунками — розбір без моделі. */
sealed interface HandsCommand {
    data object News : HandsCommand
    data object ReadNotes : HandsCommand
    data class Tap(val text: String) : HandsCommand
    data class Scroll(val down: Boolean) : HandsCommand
    data object Back : HandsCommand
    data object Home : HandsCommand
    data class Type(val text: String) : HandsCommand
    data object Screen : HandsCommand
    data object ComeBack : HandsCommand
    data object AllowNotes : HandsCommand
    data object AllowHands : HandsCommand
}

object HandsCommands {
    private val news = Regex("^(?:що\\s+нового|які\\s+(?:в\\s+мене\\s+)?(?:сповіщення|повідомлення)|хто\\s+(?:мені\\s+)?(?:писав|писала|дзвонив))")
    private val read = Regex("^(?:прочитай|зачитай)\\s+(?:мені\\s+)?(?:сповіщення|повідомлення)")
    private val tap = Regex("^(?:натисни|тисни|клацни|тапни)\\s+(?:на\\s+)?(?:кнопку\\s+)?[«\"]?(.{1,40}?)[»\"]?$")
    private val scroll = Regex("^(?:прокрути|гортай|погортай)(?:\\s+(вниз|донизу|далі|вгору|нагору|назад))?$")
    private val back = Regex("^(?:назад|крок\\s+назад|поверни\\s+назад)$")
    private val home = Regex("^(?:додому|на\\s+головний(?:\\s+екран)?|головний\\s+екран)$")
    private val type = Regex("^(?:напиши|введи|надрукуй)\\s+(?:текст\\s+)?[«\"]?(.{1,200}?)[»\"]?$")
    private val typeOriginal = Regex("^(?:напиши|введи|надрукуй)\\s+(?:текст\\s+)?[«\"]?(.{1,200}?)[»\"]?[.!]?$", RegexOption.IGNORE_CASE)
    private val screen = Regex("^(?:що\\s+(?:зараз\\s+)?на\\s+екрані|прочитай\\s+екран|що\\s+там\\s+написано)")
    private val comeBack = Regex("^(?:повернись|повертайся|вернись)(?:\\s+(?:до\\s+мене|сюди|назад))?$")
    private val allowNotes = Regex("^(?:дозволь|увімкни)\\s+(?:тобі\\s+)?(?:читати\\s+)?(?:сповіщення|доступ\\s+до\\s+сповіщень)")
    private val allowHands = Regex("^(?:дозволь|увімкни)\\s+(?:тобі\\s+)?(?:керувати\\s+(?:телефоном|застосунками|програмами)|керування)")

    /**
     * @param otherAppOpen чи відкритий зараз інший застосунок: "напиши", "натисни", "назад"
     *   мають сенс лише тоді (інакше "напиши вірш" — прохання до моделі).
     */
    fun parse(phrase: String, otherAppOpen: Boolean): HandsCommand? {
        val t = phrase.lowercase().replace(Regex("[,!?]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (allowNotes.containsMatchIn(t)) return HandsCommand.AllowNotes
        if (allowHands.containsMatchIn(t)) return HandsCommand.AllowHands
        if (read.containsMatchIn(t)) return HandsCommand.ReadNotes
        if (news.containsMatchIn(t)) return HandsCommand.News
        if (!otherAppOpen) return null
        if (comeBack.matches(t)) return HandsCommand.ComeBack
        if (back.matches(t)) return HandsCommand.Back
        if (home.matches(t)) return HandsCommand.Home
        if (screen.containsMatchIn(t)) return HandsCommand.Screen
        scroll.find(t)?.let { m -> return HandsCommand.Scroll(m.groupValues[1] !in listOf("вгору", "нагору", "назад")) }
        // "напиши" — текст як сказано: з регістром і комами (розбір вище їх прибирає).
        if (type.containsMatchIn(t)) {
            typeOriginal.find(phrase.trim())?.let { return HandsCommand.Type(it.groupValues[1].trim()) }
        }
        tap.find(t)?.let { return HandsCommand.Tap(it.groupValues[1].trim()) }
        return null
    }
}
