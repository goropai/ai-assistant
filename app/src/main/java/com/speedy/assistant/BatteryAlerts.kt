package com.speedy.assistant

/**
 * Коли попереджати про розряд: один раз на кожному порозі (10%, 5%, 2%),
 * поки телефон не поставили на зарядку.
 */
class BatteryAlerts(private val thresholds: List<Int> = listOf(10, 5, 2)) {
    private var announced: Int? = null

    /** @return поріг, про який треба сказати зараз, або null. */
    fun update(level: Int, charging: Boolean): Int? {
        if (charging) {
            announced = null
            return null
        }
        // Найнижчий поріг, до якого вже дійшли.
        val reached = thresholds.filter { level <= it }.minOrNull() ?: return null
        if (announced != null && reached >= announced!!) return null
        announced = reached
        return reached
    }

    companion object {
        /** "1 відсоток", "3 відсотки", "10 відсотків". */
        fun percentWord(n: Int): String {
            val lastTwo = n % 100
            val last = n % 10
            return when {
                lastTwo in 11..14 -> "відсотків"
                last == 1 -> "відсоток"
                last in 2..4 -> "відсотки"
                else -> "відсотків"
            }
        }
    }
}
