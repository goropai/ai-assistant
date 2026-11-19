package com.speedy.assistant.phone

/** Прямокутник у пікселях екрана (без android.graphics.Rect — щоб перевіряти в JVM-тестах). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width.toLong() * height
    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/**
 * Де стояти міні-Спіді, коли в іншому застосунку грає відео: не перекривати його — посунутись під
 * відео (або над ним); на весь екран (чи місця немає) — лишитись, але напівпрозорою.
 */
object MiniPlacement {
    /** Відео займає стільки екрана — повноекранне. */
    const val FULLSCREEN_SHARE = 0.8
    /** Прозорість поверх повноекранного відео. */
    const val SEE_THROUGH = 0.45f

    data class Place(val top: Int, val alpha: Float)

    /**
     * @param mini де міні-Спіді хоче стояти (куди її поставили)
     * @param video вікно відео; null — відео немає
     * @param screen увесь екран; [topInset] — рядок стану (вище не ставимо)
     */
    fun place(mini: Box, video: Box?, screen: Box, topInset: Int, margin: Int): Place {
        if (video == null || !mini.intersects(video)) return Place(mini.top, 1f)
        if (video.area >= screen.area * FULLSCREEN_SHARE) return Place(mini.top, SEE_THROUGH)
        val below = video.bottom + margin
        if (below + mini.height <= screen.bottom) return Place(below, 1f)
        val above = video.top - margin - mini.height
        if (above >= screen.top + topInset) return Place(above, 1f)
        return Place(mini.top, SEE_THROUGH)
    }
}
