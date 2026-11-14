package com.speedy.assistant.face

enum class Mood {
    NEUTRAL,
    HAPPY,
    SAD,
    ANGRY,
    SURPRISED,
    SLEEPY,
    THINKING,
    LISTENING;

    val wireName: String get() = name.lowercase()

    companion object {
        fun parse(value: String?): Mood =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: NEUTRAL
    }
}

/**
 * Геометрія одного ока у відносних одиницях (1.0 = базовий розмір ока).
 * Повіки — це чорні фігури поверх білого ока, тож на OLED вони "безкоштовні".
 */
data class EyeShape(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Радіус заокруглення як частка меншої сторони ока. */
    val roundness: Float = 0.3f,
    /** Яку частку ока закриває верхня повіка (0..1). */
    val topLid: Float = 0f,
    /** Нахил верхньої повіки в градусах для лівого ока (праве — дзеркально). >0 — "сердито", <0 — "сумно". */
    val lidAngle: Float = 0f,
    /** Наскільки "щічка" знизу підпирає око (0..1) — дає усмішку очима. */
    val bottomLid: Float = 0f,
    /** Напрям погляду, -1..1. */
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
) {
    fun withGaze(x: Float, y: Float) = copy(gazeX = x, gazeY = y)

    companion object {
        fun of(mood: Mood): EyeShape = when (mood) {
            Mood.NEUTRAL -> EyeShape()
            Mood.HAPPY -> EyeShape(scaleX = 1.05f, roundness = 0.35f, bottomLid = 0.45f, gazeY = -0.05f)
            Mood.SAD -> EyeShape(scaleY = 0.9f, topLid = 0.4f, lidAngle = -20f, gazeY = 0.2f)
            Mood.ANGRY -> EyeShape(scaleY = 0.9f, roundness = 0.25f, topLid = 0.35f, lidAngle = 25f)
            Mood.SURPRISED -> EyeShape(scaleX = 1.2f, scaleY = 1.25f, roundness = 0.45f)
            Mood.SLEEPY -> EyeShape(topLid = 0.6f, bottomLid = 0.1f, gazeY = 0.15f)
            Mood.THINKING -> EyeShape(scaleX = 0.95f, scaleY = 0.85f, topLid = 0.15f, gazeX = 0.3f, gazeY = -0.3f)
            Mood.LISTENING -> EyeShape(scaleX = 1.1f, scaleY = 1.1f, roundness = 0.35f)
        }

        fun lerp(a: EyeShape, b: EyeShape, t: Float): EyeShape {
            fun l(x: Float, y: Float) = x + (y - x) * t
            return EyeShape(
                scaleX = l(a.scaleX, b.scaleX),
                scaleY = l(a.scaleY, b.scaleY),
                roundness = l(a.roundness, b.roundness),
                topLid = l(a.topLid, b.topLid),
                lidAngle = l(a.lidAngle, b.lidAngle),
                bottomLid = l(a.bottomLid, b.bottomLid),
                gazeX = l(a.gazeX, b.gazeX),
                gazeY = l(a.gazeY, b.gazeY),
            )
        }
    }
}
