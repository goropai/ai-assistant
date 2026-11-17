package com.speedy.assistant.settings

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone

/**
 * Денний ліміт розмов для дитини: кожна репліка додає час від попередньої (якщо пауза ≤ хвилини),
 * урок — повністю. Облік за іменем; новий день — з нуля, а також через 4 години, коли дитина до Спіді
 * не зверталась (і відмови "я втомилася" — теж звертання). Скидання — з налаштувань (за PIN).
 */
class ChildLimit(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private var day = 0
    private val usedMs = HashMap<String, Long>()
    private val lastAt = HashMap<String, Long>()
    /** Коли дитина востаннє зверталась (і коли відмовили) — для скидання через [RESET_AFTER_MS]. */
    private val seenAt = HashMap<String, Long>()

    init {
        try {
            load()?.let { JSONObject(it) }?.let { json ->
                day = json.optInt("day")
                json.optJSONObject("used")?.let { u -> u.keys().forEach { usedMs[it] = u.getLong(it) } }
                json.optJSONObject("seen")?.let { s -> s.keys().forEach { seenAt[it] = s.getLong(it) } }
            }
        } catch (e: Exception) {
            usedMs.clear()
            seenAt.clear()
        }
    }

    /** Дитина звернулась, але ліміт вичерпано: це теж "дитина поруч" — 4 години рахуємо від цього. */
    @Synchronized
    fun seen(name: String, now: Long) {
        rollover(now)
        seenAt[name] = now
        persist()
    }

    /** Репліка дитини о [now]: додаємо час розмови. */
    @Synchronized
    fun talk(name: String, now: Long) {
        rollover(now)
        seenAt[name] = now
        val last = lastAt[name]
        val delta = if (last != null && now - last in 0..GAP_MS) now - last else TURN_MS
        usedMs[name] = (usedMs[name] ?: 0) + delta
        lastAt[name] = now
        persist()
    }

    /** Урок чи інша тривала дія: додати її тривалість повністю. */
    @Synchronized
    fun add(name: String, durationMs: Long, now: Long) {
        rollover(now)
        usedMs[name] = (usedMs[name] ?: 0) + durationMs.coerceAtLeast(0)
        lastAt[name] = now
        seenAt[name] = now
        persist()
    }

    /** Чи вичерпано ліміт ([limitMinutes] ≤ 0 — без обмеження). */
    @Synchronized
    fun exhausted(name: String, limitMinutes: Int, now: Long): Boolean {
        rollover(now)
        return limitMinutes > 0 && (usedMs[name] ?: 0) >= limitMinutes * 60_000L
    }

    @Synchronized
    fun usedMinutes(now: Long): Map<String, Int> {
        rollover(now)
        return usedMs.mapValues { (it.value / 60_000).toInt() }
    }

    /** Скинути денний ліміт (усім дітям) — з налаштувань, скільки завгодно разів. */
    @Synchronized
    fun reset() {
        usedMs.clear()
        lastAt.clear()
        seenAt.clear()
        persist()
    }

    private fun rollover(now: Long) {
        val today = Calendar.getInstance(zone).apply { timeInMillis = now }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
        if (today != day) {
            day = today
            usedMs.clear()
            lastAt.clear()
            seenAt.clear()
            persist()
            return
        }
        // Дитини не було 4 години — її ліміт знову повний.
        val away = seenAt.filterValues { now - it >= RESET_AFTER_MS }.keys
        if (away.isNotEmpty()) {
            away.forEach { usedMs.remove(it); lastAt.remove(it); seenAt.remove(it) }
            persist()
        }
    }

    private fun persist() = save(
        JSONObject().put("day", day).put("used", JSONObject(usedMs as Map<*, *>)).put("seen", JSONObject(seenAt as Map<*, *>)).toString()
    )

    companion object {
        /** Пауза, довша за цю, — не розмова (репліка рахується як [TURN_MS]). */
        const val GAP_MS = 60_000L
        const val TURN_MS = 10_000L
        /** Стільки без дитини — і її ліміт скидається сам. */
        const val RESET_AFTER_MS = 4 * 3600_000L
    }
}

/** 4-значний PIN батьків: зберігаємо лише хеш із сіллю. */
object Pin {
    fun valid(pin: String) = pin.length == 4 && pin.all(Char::isDigit)

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun matches(pin: String, salt: String, hash: String) = valid(pin) && hash(pin, salt) == hash
}
