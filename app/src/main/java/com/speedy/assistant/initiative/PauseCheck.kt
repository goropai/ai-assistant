package com.speedy.assistant.initiative

/**
 * Пауза в розмові: Спіді відповіла, а далі тиша (розпізнавач загубив фразу, звук пропав). Замість мовчати
 * вона кілька разів перевіряє, чи співрозмовник ще тут: камерами (передня — постійно, задня — знімком) і
 * коротким записом звуку (чи там говорять). Підтвердилось — продовжує розмову, ні — тихо завершує.
 */
object PauseCheck {
    /** Скільки тиші після відповіді, перш ніж перевіряти. */
    const val PAUSE_MS = 20_000L
    /** Скільки часу перевіряти. */
    const val CHECK_MS = 60_000L
    /** Як часто перевіряти (запис звуку — частина цього часу, решту слухає розпізнавач). */
    const val TICK_MS = 9_000L
    /** Скільки секунд звуку записувати за раз. */
    const val RECORD_SECONDS = 4
    /** Видно людину стільки перевірок (без мовлення) — продовжити розмову. */
    const val PRESENT_TICKS = 2

    enum class Next {
        /** Чути мовлення — відповісти на нього (звук іде моделі). */
        ANSWER_SPEECH,
        /** Співрозмовник тут, але мовчить (чи звук моделі не передати) — одна коротка репліка. */
        CONTINUE,
        /** Ще перевіряти. */
        WAIT,
        /** Час вийшов, нікого — тихо завершити. */
        END,
    }

    /**
     * @param speech у цьому записі чути мовлення
     * @param hearsAudio модель приймає звук (Gemini; Claude — ні)
     * @param presentTicks скільки перевірок (разом із цією) підтвердили присутність камерою чи голосом
     * @param timeLeft ще є час на наступну перевірку
     */
    fun next(speech: Boolean, hearsAudio: Boolean, presentTicks: Int, timeLeft: Boolean): Next = when {
        speech && hearsAudio -> Next.ANSWER_SPEECH
        presentTicks >= PRESENT_TICKS -> Next.CONTINUE
        timeLeft -> Next.WAIT
        else -> Next.END
    }
}
