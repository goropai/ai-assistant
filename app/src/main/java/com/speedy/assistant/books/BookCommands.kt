package com.speedy.assistant.books

/** Голосові команди книг — розбір без моделі. */
sealed interface BookCommand {
    /**
     * "почитай книгу Кобзар". [title] null — назви не сказали. [explicit] — сказали "книгу";
     * інакше ("почитай Кобзаря") це може бути й прохання до моделі, якщо такої книги немає.
     */
    data class Read(val title: String?, val explicit: Boolean) : BookCommand
    /** "продовж читати"; [bare] — просто "продовжуй"/"далі": книга — лише якщо щойно читали. */
    data class Continue(val bare: Boolean) : BookCommand
    data object List : BookCommand
    data object Which : BookCommand
    data object FromStart : BookCommand
    data object ChapterStart : BookCommand
    data object NextChapter : BookCommand
    data object PreviousChapter : BookCommand
}

object BookCommands {
    private const val BOOK = "(?:книг\\p{L}*|книжк\\p{L}*|книжечк\\p{L}*)"
    private const val PART = "(?:розділ\\p{L}*|глав\\p{L}*|частин\\p{L}*|сторінк\\p{L}*)"

    private val continueReading = Regex("^(?:продовж(?:уй|и|имо)?|читай\\s+далі|далі\\s+читай|почитай\\s+ще|читай\\s+ще)\\s+(?:читати|читання|$BOOK|казку)$|^(?:читай|почитай)\\s+(?:далі|ще)$|^(?:продовж(?:уй|и|имо)?)\\s+$BOOK$")
    private val bare = Regex("^(?:продовж(?:уй|и|имо)?|далі|читай|давай\\s+далі)$")
    private val list = Regex("^(?:які|що\\s+за)\\s+(?:в\\s+тебе\\s+|у\\s+тебе\\s+|в\\s+нас\\s+|у\\s+нас\\s+)?(?:є\\s+)?$BOOK|^(?:список|перелік)\\s+книг|^що\\s+(?:є\\s+)?почитати$")
    private val which = Regex("^(?:яку|що\\s+за)\\s+$BOOK\\s+(?:ми\\s+|ти\\s+)?(?:читаємо|читали|читаєш|читала)|^що\\s+ми\\s+(?:читаємо|читали)|^де\\s+ми\\s+зупинил\\p{L}*")
    private val fromStart = Regex("^(?:читай|почитай|почни|прочитай)\\s+(?:$BOOK\\s+)?(?:з|із|від)\\s+(?:самого\\s+)?початку(?:\\s+$BOOK)?$|^(?:почни|почнімо)\\s+$BOOK\\s+(?:з|із)\\s+початку$")
    private val chapterStart = Regex("(?:з|із|від)\\s+початку\\s+$PART|^повтори\\s+$PART|^(?:читай|почитай)\\s+$PART\\s+(?:з|із|від)\\s+початку")
    private val next = Regex("^(?:(?:читай|давай|переходь\\s+на|перейди\\s+на|увімкни)\\s+)?наступн\\p{L}+\\s+$PART$")
    private val previous = Regex("^(?:(?:читай|давай|повернись\\s+на|поверни|перейди\\s+на)\\s+)?попередн\\p{L}+\\s+$PART$")
    private val read = Regex("^(?:(?:по|про|за)?читай(?:те)?|хочу\\s+послухати|давай\\s+почитаємо|почитаймо|увімкни)\\s+(?:(?:мені|нам)(?:\\s+|$))?(?:будь\\s+ласка\\s+)?(?:($BOOK)\\s*)?(.*)$")
    /** "прочитай сповіщення / що на екрані" — це не книги. */
    private val notBook = Regex("^(?:сповіщ|повідомл|смс|sms|екран|що\\s|це$|те\\s+що|напис|лист|пошт|вірш|казк|музик|пісн|радіо|аудіо|фільм|мульт|серіал|відео)")

    fun parse(phrase: String): BookCommand? {
        val t = phrase.lowercase().replace(Regex("[,!?«»\"]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (chapterStart.containsMatchIn(t)) return BookCommand.ChapterStart
        if (next.matches(t)) return BookCommand.NextChapter
        if (previous.matches(t)) return BookCommand.PreviousChapter
        if (fromStart.matches(t)) return BookCommand.FromStart
        if (continueReading.matches(t)) return BookCommand.Continue(bare = false)
        if (bare.matches(t)) return BookCommand.Continue(bare = true)
        if (which.containsMatchIn(t)) return BookCommand.Which
        if (list.containsMatchIn(t)) return BookCommand.List
        read.find(t)?.let { m ->
            val explicit = m.groupValues[1].isNotEmpty()
            val title = m.groupValues[2].replace(Regex("\\s+(?:вголос|будь\\s+ласка)$"), "").trim()
            // "увімкни" — лише з "книгу" (інакше це музика, світло, телевізор).
            if (!explicit && (t.startsWith("увімкни") || notBook.containsMatchIn(title))) return null
            return BookCommand.Read(title.ifEmpty { null }, explicit)
        }
        return null
    }
}
