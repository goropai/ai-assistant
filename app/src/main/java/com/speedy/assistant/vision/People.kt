package com.speedy.assistant.vision

/**
 * Пам'ять про людей: спільна для потоку камери ([PresenceWatcher]) і головного потоку
 * ([com.speedy.assistant.Assistant]), тому всі методи синхронізовані.
 * Час — той самий монотонний годинник, що й у камери (SystemClock.uptimeMillis).
 */
class People(private val book: FaceBook) {
    private val tracker = FaceIdTracker(book)

    @Synchronized
    fun update(now: Long, faces: List<FaceObservation>): List<PresenceEvent> = tracker.update(now, faces)

    @Synchronized
    fun presentNames(now: Long): List<String> = tracker.presentNames(now)

    /** Прив'язати ім'я до щойно баченого незнайомого обличчя. false — обличчя не бачили. */
    @Synchronized
    fun introduce(name: String, now: Long): Boolean {
        val samples = tracker.recentStranger(now)
        if (samples.isEmpty()) return false
        book.remember(name, samples)
        tracker.clearStranger()
        return true
    }

    @Synchronized
    fun forget(name: String): Boolean = book.forget(name)

    /**
     * Людина в кадрі — насправді [name] (назвалась жартома або її сплутали з кимось).
     * false — обличчя щойно не було видно.
     */
    @Synchronized
    fun reassign(name: String, now: Long): Boolean {
        val faces = tracker.recentFaces(now)
        if (faces.isEmpty()) return false
        val before = tracker.presentNames(now)
        book.reassign(name, faces)
        before.forEach { tracker.renamed(it, name, now) }
        return true
    }

    /** Знайомий, що зараз поруч, назвався інакше — міняємо ім'я, обличчя лишаються. */
    @Synchronized
    fun rename(old: String, new: String, now: Long): Boolean = book.rename(old, new).also {
        if (it) tracker.renamed(old, new, now)
    }
}
