package com.speedy.assistant.vision

/**
 * Перетворює покадрові обличчя на події "впізнала Тараса" / "бачу незнайомця".
 * Як і [PresenceTracker], захищається від мерехтіння: подія — лише після кількох кадрів
 * поспіль, і для кожної людини не частіше за [cooldownMs].
 */
class FaceIdTracker(
    private val book: FaceBook,
    private val confirmFrames: Int = 2,
    private val cooldownMs: Long = 10 * 60_000,
    private val forgetAfterMs: Long = 10_000,
    /** Незнайомця питаємо, лише коли обличчя досить велике (людина поруч, а не на фоні). */
    private val strangerMinArea: Float = 0.04f,
) {
    private val streak = mutableMapOf<String, Int>()
    private var strangerStreak = 0
    private val lastEvent = mutableMapOf<String, Long>()
    private val lastSeen = mutableMapOf<String, Long>()

    /** Останні відбитки незнайомця — щоб запам'ятати, коли він назве ім'я. */
    var strangerSamples: List<FloatArray> = emptyList()
        private set
    private var strangerSeenAt = Long.MIN_VALUE / 2

    /** Хто з облич останнього кадру — хто (для ознак обличчя в профілі). */
    var lastMatches: List<Pair<String, FaceObservation>> = emptyList()
        private set

    /** Відбитки облич з останніх кадрів (найбільше обличчя) — для виправлення імені. */
    private val recentFaces = ArrayDeque<Pair<Long, FloatArray>>()

    fun recentFaces(now: Long, maxAgeMs: Long = 30_000): List<FloatArray> =
        recentFaces.filter { now - it.first <= maxAgeMs }.map { it.second }

    fun update(now: Long, faces: List<FaceObservation>): List<PresenceEvent> {
        faces.maxByOrNull { it.area }?.let {
            recentFaces.addLast(now to it.embedding)
            while (recentFaces.size > MAX_SAMPLES) recentFaces.removeFirst()
        }
        val events = mutableListOf<PresenceEvent>()
        val seenNames = mutableSetOf<String>()
        var stranger: FaceObservation? = null
        lastMatches = mutableListOf()
        for (face in faces) {
            val name = book.match(face.embedding)?.first
            if (name != null) { seenNames += name; (lastMatches as MutableList) += name to face }
            else if (face.area >= strangerMinArea && stranger == null) stranger = face
        }

        for (name in seenNames) {
            lastSeen[name] = now
            val frames = (streak[name] ?: 0) + 1
            streak[name] = frames
            if (frames == confirmFrames && cooldownPassed(name, now)) {
                lastEvent[name] = now
                events += PresenceEvent.Recognized(name)
            }
        }
        streak.keys.retainAll(seenNames)

        if (stranger != null) {
            strangerSamples = (strangerSamples + stranger.embedding).takeLast(MAX_SAMPLES)
            strangerSeenAt = now
            strangerStreak++
            if (strangerStreak == confirmFrames && cooldownPassed(STRANGER, now)) {
                lastEvent[STRANGER] = now
                events += PresenceEvent.Stranger
            }
        } else {
            strangerStreak = 0
        }
        return events
    }

    /** Відбитки незнайомця, якщо його бачили щойно (інакше ім'я прив'язувати нема до чого). */
    fun recentStranger(now: Long, maxAgeMs: Long = 60_000): List<FloatArray> =
        if (now - strangerSeenAt <= maxAgeMs) strangerSamples else emptyList()

    /** Після перейменування: людина вже поруч і привітана — без повторного "Привіт, …". */
    fun renamed(old: String, new: String, now: Long) {
        lastSeen.remove(old)?.let { lastSeen[new] = it }
        streak.remove(old)?.let { streak[new] = it }
        lastEvent.remove(old)
        lastEvent[new] = now
    }

    fun clearStranger() {
        strangerSamples = emptyList()
        strangerStreak = 0
    }

    /** Хто з відомих зараз поруч. */
    fun presentNames(now: Long): List<String> = lastSeen.filter { now - it.value <= forgetAfterMs }.keys.toList()

    private fun cooldownPassed(key: String, now: Long): Boolean {
        val last = lastEvent[key] ?: return true
        return now - last >= cooldownMs
    }

    private companion object {
        const val STRANGER = "\u0000stranger"
        const val MAX_SAMPLES = 5
    }
}
