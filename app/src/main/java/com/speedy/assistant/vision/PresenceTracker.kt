package com.speedy.assistant.vision

enum class Kind(val label: String, val ua: String) {
    PERSON("person", "людина"),
    CAT("cat", "кіт"),
    DOG("dog", "собака");

    companion object {
        fun fromLabel(label: String): Kind? = entries.firstOrNull { it.label == label }
    }
}

/** Об'єкт у кадрі; cx, cy — центр у частках кадру (0..1), кадр уже повернутий вертикально. */
data class Sighting(val kind: Kind, val score: Float, val cx: Float, val cy: Float, val area: Float)

sealed interface PresenceEvent {
    /** Хтось з'явився (після відсутності) — привід привітатися. */
    data class Arrived(val kind: Kind) : PresenceEvent

    /** Куди дивитися очима: -1..1 у координатах екрана (x вправо, y вниз). */
    data class Look(val x: Float, val y: Float) : PresenceEvent

    /** Усі пішли. */
    data object Lost : PresenceEvent
}

/**
 * Перетворює покадрові розпізнавання на події. Захист від "мерехтіння" моделі:
 * присутність підтверджується кількома кадрами поспіль, зникнення — паузою;
 * вітання для кожного виду не частіше за [greetCooldownMs].
 */
class PresenceTracker(
    private val confirmFrames: Int = 2,
    private val loseAfterMs: Long = 10_000,
    private val greetCooldownMs: Long = 10 * 60_000,
    /** Фронтальна камера дивиться назустріч глядачу: лівий край кадру — праворуч на екрані. */
    private val mirrorX: Boolean = true,
) {
    private val streak = mutableMapOf<Kind, Int>()
    private val lastSeen = mutableMapOf<Kind, Long>()
    private val lastGreeted = mutableMapOf<Kind, Long>()
    private val present = mutableSetOf<Kind>()

    fun update(now: Long, sightings: List<Sighting>): List<PresenceEvent> {
        val events = mutableListOf<PresenceEvent>()
        val seen = sightings.map { it.kind }.toSet()
        val wasAnyone = present.isNotEmpty()

        for (kind in Kind.entries) {
            if (kind in seen) {
                lastSeen[kind] = now
                val frames = (streak[kind] ?: 0) + 1
                streak[kind] = frames
                if (kind !in present && frames >= confirmFrames) {
                    present += kind
                    val greeted = lastGreeted[kind]
                    if (greeted == null || now - greeted >= greetCooldownMs) {
                        lastGreeted[kind] = now
                        events += PresenceEvent.Arrived(kind)
                    }
                }
            } else {
                streak[kind] = 0
                val last = lastSeen[kind]
                if (kind in present && (last == null || now - last >= loseAfterMs)) present -= kind
            }
        }

        // Дивимось на найбільшу людину в кадрі, інакше — на тварину.
        val target = sightings.filter { it.kind == Kind.PERSON }.maxByOrNull { it.area }
            ?: sightings.maxByOrNull { it.area }
        if (target != null && target.kind in present) {
            val x = target.cx * 2f - 1f
            events += PresenceEvent.Look(if (mirrorX) -x else x, target.cy * 2f - 1f)
        }

        if (wasAnyone && present.isEmpty()) events += PresenceEvent.Lost
        return events
    }

    /** Для "великого мозку": хто зараз поруч. */
    fun summary(): String = present.sortedBy { it.ordinal }.joinToString(", ") { it.ua }
}
