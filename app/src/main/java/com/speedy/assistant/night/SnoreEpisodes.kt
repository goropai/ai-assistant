package com.speedy.assistant.night

/**
 * Оцінки "хропіння" від моделі (кадр кожні пів секунди) → епізоди. Хропіння — ритмічне: звук на
 * вдиху кожні 3–6 с, тож поодинокий кадр (кашель, скрип) — ще не хропіння. Початок — коли за
 * [windowMs] набралось [startHits] позитивних кадрів; кінець — [endQuietMs] без жодного.
 */
class SnoreEpisodes(
    private val threshold: Float = 0.3f,
    private val startHits: Int = 3,
    private val windowMs: Long = 15_000,
    private val endQuietMs: Long = 20_000,
) {
    sealed interface Event {
        /** Почалось — [since]: перший позитивний кадр серії (запис бере кілька секунд до нього). */
        data class Started(val since: Long) : Event
        data class Ended(val start: Long, val end: Long) : Event
    }

    private val hits = ArrayDeque<Long>()
    private var start = -1L
    private var lastHit = 0L

    val snoring get() = start >= 0

    fun feed(at: Long, score: Float): Event? {
        if (score >= threshold) {
            lastHit = at
            hits.addLast(at)
        }
        while (hits.isNotEmpty() && at - hits.first() > windowMs) hits.removeFirst()
        if (start < 0) {
            if (hits.size >= startHits) {
                start = hits.first()
                return Event.Started(start)
            }
            return null
        }
        if (at - lastHit >= endQuietMs) {
            val ended = Event.Ended(start, lastHit)
            start = -1
            hits.clear()
            return ended
        }
        return null
    }

    /** Ніч закінчилась посеред хропіння — закрити епізод. */
    fun finish(): Event.Ended? = if (start >= 0) Event.Ended(start, lastHit).also { start = -1; hits.clear() } else null
}
