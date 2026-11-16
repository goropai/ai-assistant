package com.speedy.assistant.media

/** Голосові команди фільмів, аудіокниг і музики — розбір без моделі. */
sealed interface MediaCommand {
    /**
     * "увімкни фільм Дюна", "постав аудіокнигу Кобзар". [kind] — зі слова "фільм"/"аудіокнигу";
     * без нього ("увімкни Дюну") — лише якщо такий файл є, інакше — прохання до моделі.
     */
    data class Play(val title: String?, val kind: MediaKind?) : MediaCommand
    /** "продовж дивитись", "наступна серія", "продовж аудіокнигу". */
    data class Continue(val kind: MediaKind?) : MediaCommand
    data object Pause : MediaCommand
    /** "які є фільми / аудіокниги". */
    data class List(val kind: MediaKind?) : MediaCommand
}

object MediaCommands {
    private const val VIDEO = "фільм\\p{L}*|кіно|кінофільм\\p{L}*|мультик\\p{L}*|мультфільм\\p{L}*|серіал\\p{L}*|відео\\p{L}*|серію|серії|кліп\\p{L}*"
    private const val AUDIO = "аудіокниг\\p{L}*|аудіо|пісн\\p{L}*|пісеньк\\p{L}*|музик\\p{L}*|альбом\\p{L}*|подкаст\\p{L}*|трек\\p{L}*"
    private const val VERB = "увімкни|включи|ввімкни|постав|запусти|грай|програй|покажи|хочу\\s+подивитис[ья]|хочу\\s+послухати|давай\\s+подивимос[ья]|давай\\s+послухаємо|подивимос[ья]|послухаємо"

    private val play = Regex("^(?:$VERB)\\s+(?:мені\\s+|нам\\s+)?(?:будь\\s+ласка\\s+)?(?:($VIDEO|$AUDIO)(?:\\s+|$))?(.*)$")
    private val next = Regex("^(?:(?:увімкни|включи|постав|давай|покажи)\\s+)?наступн\\p{L}+\\s+(?:серію|серія|частину|частина|епізод)$")
    private val continuePlay = Regex("^(?:продовж(?:уй|и|имо)?|давай\\s+далі)\\s+(?:дивитис[ья]|перегляд|слухати|($VIDEO|$AUDIO))$")
    private val pause = Regex("^(?:пауза|на\\s+паузу|постав\\s+на\\s+паузу|зупини\\s+(?:$VIDEO|$AUDIO)|вимкни\\s+(?:$VIDEO|$AUDIO))$")
    private val list = Regex("^(?:які|що\\s+за)\\s+(?:в\\s+тебе\\s+|у\\s+тебе\\s+|в\\s+нас\\s+|у\\s+нас\\s+)?(?:є\\s+)?($VIDEO|$AUDIO)$|^що\\s+(?:є\\s+)?(?:подивитис[ья]|послухати)$")
    /** "увімкни світло / телевізор / Wi-Fi" — не медіа. */
    private val notMedia = Regex("^(?:світло|телевізор|wi-?fi|вай\\s?фай|блютуз|bluetooth|будильник|таймер|звук|гучність|ліхтарик|режим|англійськ|книг|книжк)")

    fun parse(phrase: String): MediaCommand? {
        val t = phrase.lowercase().replace(Regex("[,!?«»\"]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (next.matches(t)) return MediaCommand.Continue(MediaKind.VIDEO)
        continuePlay.find(t)?.let { m ->
            return MediaCommand.Continue(
                when {
                    m.groupValues[1].isNotEmpty() -> kindOf(m.groupValues[1])
                    t.contains("дивит") || t.contains("перегляд") -> MediaKind.VIDEO
                    else -> MediaKind.AUDIO
                }
            )
        }
        if (pause.matches(t)) return MediaCommand.Pause
        list.find(t)?.let { m ->
            return MediaCommand.List(m.groupValues[1].takeIf { it.isNotEmpty() }?.let(::kindOf) ?: if ("подив" in t) MediaKind.VIDEO else MediaKind.AUDIO)
        }
        play.find(t)?.let { m ->
            val kind = m.groupValues[1].takeIf { it.isNotEmpty() }?.let(::kindOf)
            val title = m.groupValues[2].trim().ifEmpty { null }
            if (kind == null && (title == null || notMedia.containsMatchIn(title) || t.startsWith("покажи"))) return null
            return MediaCommand.Play(title, kind)
        }
        return null
    }

    private fun kindOf(word: String): MediaKind =
        if (Regex("^(?:$AUDIO)$").matches(word)) MediaKind.AUDIO else MediaKind.VIDEO
}
