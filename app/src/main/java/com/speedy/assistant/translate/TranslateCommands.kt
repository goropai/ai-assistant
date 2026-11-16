package com.speedy.assistant.translate

import com.speedy.assistant.books.Lang

/** Голосові команди перекладу — розбір без моделі. */
sealed interface TranslateCommand {
    /**
     * "переклади на англійську я люблю каву" — [text] сказано українською (так його й розпізнали).
     * [text] null — "переклади з англійської": Спіді слухає фразу мовою [from].
     */
    data class Phrase(val text: String?, val from: Lang, val to: Lang) : TranslateCommand
    /** "читай українською" — книгу в перекладі; "читай в оригіналі" — як написано. */
    data class Book(val translate: Boolean) : TranslateCommand
}

object TranslateCommands {
    private const val TO = "(?:на\\s+)?(англійськ|російськ|українськ)\\p{L}*"
    private const val FROM = "(?:з|із)\\s+(англійськ|російськ|українськ)\\p{L}*"

    private val bookOn = Regex("^(?:читай|почитай|перекладай|читай\\s+мені)\\s+(?:в\\s+перекладі\\s+)?(?:на\\s+)?українськ\\p{L}*$|^читай\\s+у?\\s*перекладі$")
    private val bookOff = Regex("^(?:читай|почитай)\\s+(?:в|у|мовою)\\s+оригінал\\p{L}*$|^не\\s+перекладай$|^читай\\s+як\\s+написано$")
    // "переклади з англійської (на українську) [фраза]", "переклади на англійську <фраза>"
    private val phrase = Regex("^(?:переклади|перекладіть|перекласти)\\s+(?:мені\\s+)?(?:$FROM\\s*)?(?:$TO)?\\s*(.*)$")
    // "як англійською (буде) собака", "як по-російськи кіт"
    private val howSay = Regex("^як\\s+(?:буде\\s+)?(?:по-?)?(англійськ|російськ|українськ)\\p{L}*\\s+(?:буде\\s+|сказати\\s+|звучить\\s+)?(.+)$")

    fun parse(phrase: String): TranslateCommand? {
        val t = phrase.lowercase().replace(Regex("[,!?«»\"]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (bookOn.matches(t)) return TranslateCommand.Book(true)
        if (bookOff.matches(t)) return TranslateCommand.Book(false)
        howSay.find(t)?.let { m -> return TranslateCommand.Phrase(m.groupValues[2], Lang.UK, lang(m.groupValues[1])) }
        this.phrase.find(t)?.let { m ->
            val from = m.groupValues[1].takeIf { it.isNotEmpty() }?.let(::lang)
            val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::lang)
            val text = m.groupValues[3].trim().ifEmpty { null }
            return when {
                // "переклади з англійської" — фразу скажуть англійською (почуємо окремо).
                from != null && from != Lang.UK -> TranslateCommand.Phrase(text, from, to?.takeIf { it != from } ?: Lang.UK)
                // "переклади на англійську <фраза>" — фраза українською.
                to != null && to != Lang.UK && text != null -> TranslateCommand.Phrase(text, Lang.UK, to)
                // "переклади на англійську" без фрази — почуємо українською.
                to != null && to != Lang.UK -> TranslateCommand.Phrase(null, Lang.UK, to)
                else -> null // "переклади цю статтю" — до моделі
            }
        }
        return null
    }

    private fun lang(stem: String) = when {
        stem.startsWith("англ") -> Lang.EN
        stem.startsWith("рос") -> Lang.RU
        else -> Lang.UK
    }
}
