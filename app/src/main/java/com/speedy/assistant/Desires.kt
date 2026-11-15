package com.speedy.assistant

import com.speedy.assistant.voice.Chirp

/**
 * "Бажання" Спіді, які вона висловлює звуком, а не словами: хоче на зарядку,
 * хоче уваги, коли поруч хтось є, але з нею давно не говорили. Кожне бажання —
 * не частіше за свій інтервал, а "нудьгу" вночі не показуємо.
 */
class Desires(
    private val attentionAfterMs: Long = 20 * 60_000,
    private val attentionCooldownMs: Long = 30 * 60_000,
    private val chargeCooldownMs: Long = 15 * 60_000,
    private val quietHours: IntRange = 22..23,
    private val quietMorning: IntRange = 0..7,
) {
    private var lastAttention = Long.MIN_VALUE / 2
    private var lastCharge = Long.MIN_VALUE / 2

    /**
     * @param now час у мс (монотонний)
     * @param hour година доби 0..23
     * @param lastInteraction коли востаннє з нею говорили (той самий годинник, що й [now])
     */
    fun check(
        now: Long,
        hour: Int,
        someoneNear: Boolean,
        lastInteraction: Long,
        batteryPercent: Int,
        charging: Boolean,
    ): Chirp? {
        if (batteryPercent in 0 until LOW_BATTERY && !charging && now - lastCharge >= chargeCooldownMs) {
            lastCharge = now
            return Chirp.LOW_BATTERY
        }
        val quiet = hour in quietHours || hour in quietMorning
        if (!quiet && someoneNear && now - lastInteraction >= attentionAfterMs &&
            now - lastAttention >= attentionCooldownMs
        ) {
            lastAttention = now
            return Chirp.ATTENTION
        }
        return null
    }

    companion object {
        const val LOW_BATTERY = 20
    }
}
