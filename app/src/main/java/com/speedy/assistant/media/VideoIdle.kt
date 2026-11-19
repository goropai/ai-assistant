package com.speedy.assistant.media

/**
 * Спіді у фоні, в іншому застосунку дивились відео (NewPipe, YouTube, VLC…), а воно зупинилось (скінчилось,
 * пауза) і [idleMs] ніхто нічого не робить — час повернути Спіді на екран. Застосунки, де відео не грало,
 * не чіпаємо: людина могла просто відкрити щось своє.
 */
class VideoIdle(private val idleMs: Long = IDLE_MS) {
    private var app: String? = null
    private var watching = false
    private var quietSince = 0L

    fun reset() {
        app = null
        watching = false
    }

    /**
     * @param top застосунок на екрані
     * @param playing там зараз грає відео (медіасесія цього застосунку)
     * @param userActedAt коли людина востаннє щось натискала чи гортала в ньому
     * @return true — повертатися (стан скидається)
     */
    fun update(now: Long, top: String?, playing: Boolean, userActedAt: Long): Boolean {
        if (top != app) {
            app = top
            watching = false
        }
        if (playing) {
            watching = true
            quietSince = now
            return false
        }
        if (!watching) return false
        quietSince = maxOf(quietSince, userActedAt)
        if (now - quietSince < idleMs) return false
        reset()
        return true
    }

    companion object {
        const val IDLE_MS = 30_000L
    }
}
