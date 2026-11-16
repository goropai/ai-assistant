package com.speedy.assistant.voice

/**
 * "Вартовий" звуку: за рівнем гучності кадрів по 20 мс вирішує, чи хтось заговорив.
 * Фоновий шум відстежується повільно (лише на тихих кадрах), мовлення — це кадри,
 * гучніші за шум на [marginDb], що йдуть майже поспіль.
 * Чиста логіка без Android — щоб перевіряти тестами.
 */
class SoundGate(
    private val marginDb: Float = 12f,
    /** Скільки гучних кадрів із останніх [window] потрібно для спрацювання (~100 мс із 160). */
    private val needed: Int = 5,
    private val window: Int = 8,
    /** Найтихіший рівень, який вважаємо шумом (тиша в кімнаті не опускає поріг до нуля). */
    private val minFloorDb: Float = 30f,
) {
    private var floor = Float.NaN
    private val recent = ArrayDeque<Boolean>()

    /** @param db рівень кадру (20·log10 RMS для 16-бітних відліків). @return true — заговорили. */
    fun feed(db: Float): Boolean {
        if (floor.isNaN()) floor = maxOf(db, minFloorDb)
        val loud = db > floor + marginDb
        if (!loud) floor = maxOf(minFloorDb, floor * 0.95f + db * 0.05f)
        recent.addLast(loud)
        if (recent.size > window) recent.removeFirst()
        if (recent.count { it } >= needed) {
            recent.clear()
            return true
        }
        return false
    }

    fun reset() = recent.clear()

    companion object {
        fun levelDb(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
            val rms = kotlin.math.sqrt(sum / count)
            return (20 * kotlin.math.log10(rms.coerceAtLeast(1.0))).toFloat()
        }
    }
}
