package com.speedy.assistant.home

import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Розумний дім (Tuya Cloud): список пристроїв у пам'яті (оновлюється не частіше ніж раз на [LIST_TTL_MS]),
 * команди й стан. Ключі — з налаштувань ([credentials]: дата-центр, Access ID, Access Secret).
 */
class SmartHome(
    private val http: OkHttpClient,
    private val credentials: () -> Triple<String, String, String>,
    /** Підтримку Tuya ввімкнено в налаштуваннях. */
    private val enabled: () -> Boolean = { true },
) {
    private var cloud: TuyaCloud? = null
    private var cloudKey: Triple<String, String, String>? = null
    @Volatile var devices: List<HomeDevice> = emptyList()
        private set
    private var loadedAt = Long.MIN_VALUE / 2
    private var failedAt = Long.MIN_VALUE / 2
    private val specs = HashMap<String, Map<String, JSONObject>>()

    val configured: Boolean get() = enabled() && credentials().let { it.second.isNotBlank() && it.third.isNotBlank() }

    private fun cloud(): TuyaCloud? {
        if (!enabled()) return null
        val key = credentials()
        if (key.second.isBlank() || key.third.isBlank()) return null
        if (key != cloudKey) {
            cloud = TuyaCloud(http, TuyaRegion.of(key.first), key.second, key.third)
            cloudKey = key
            devices = emptyList()
            loadedAt = Long.MIN_VALUE / 2
            specs.clear()
        }
        return cloud
    }

    /** Ключі змінили — наступного разу заново. */
    fun invalidate() {
        cloudKey = null
    }

    /** Пристрої зі станом: з пам'яті, якщо свіжі; не вдалось нещодавно — не мучимо хмару на кожну фразу. */
    suspend fun devices(maxAgeMs: Long = LIST_TTL_MS): List<HomeDevice> {
        val c = cloud() ?: return emptyList()
        val now = SystemClock.uptimeMillis()
        if (now - loadedAt < maxAgeMs && devices.isNotEmpty()) return devices
        if (now - failedAt < RETRY_MS && devices.isEmpty()) return devices
        return try {
            c.devices().also {
                devices = it
                loadedAt = now
                Log.i(TAG, "пристроїв: ${it.size} — ${it.joinToString { d -> "${d.name} (${d.category})" }}")
            }
        } catch (e: java.io.IOException) {
            failedAt = now
            Log.w(TAG, "список пристроїв: ${e.message}")
            devices
        }
    }

    /** Перевірка з налаштувань: скільки пристроїв видно або чому не вдалось. */
    suspend fun check(region: String, id: String, secret: String): String {
        if (id.isBlank() || secret.isBlank()) return "Введи Access ID і Access Secret"
        return try {
            val list = TuyaCloud(http, TuyaRegion.of(region), id.trim(), secret.trim()).devices()
            if (list.isEmpty()) "Зв'язок є, але пристроїв не видно: прив'яжи застосунок Smart Life до проєкту (Devices → Link App Account)"
            else "Знайдено ${list.size}: ${list.take(6).joinToString { it.name }}${if (list.size > 6) "…" else ""}"
        } catch (e: TuyaException) {
            Log.w(TAG, "перевірка: ${e.code} ${e.message}")
            when (e.code) {
                1004 -> "Невірний Access Secret (sign invalid)"
                1005, 2009 -> "Невірний Access ID"
                1106, 28841105, 28841101 -> "Проєкту бракує дозволу: підпиши IoT Core у Service API (${e.message})"
                else -> "Tuya: ${e.message} (${e.code}). Перевір дата-центр."
            }
        } catch (e: java.io.IOException) {
            "Немає зв'язку з Tuya: ${e.message}"
        }
    }

    /** Свіжий стан цих пристроїв (для "яка температура…"). */
    suspend fun refresh(targets: List<HomeDevice>): List<HomeDevice> {
        val c = cloud() ?: return targets
        val fresh = targets.map { d ->
            try { d.copy(status = c.status(d.id)) } catch (e: java.io.IOException) { Log.w(TAG, "стан ${d.name}: ${e.message}"); d }
        }
        devices = devices.map { d -> fresh.firstOrNull { it.id == d.id } ?: d }
        return fresh
    }

    /** Масштаби числових кодів (температура 215 → 21.5) — з опису пристрою, раз на запуск. */
    suspend fun scales(d: HomeDevice): Map<String, Int> = spec(d).mapValues { it.value.optInt("scale", 0) }

    private suspend fun spec(d: HomeDevice): Map<String, JSONObject> {
        specs[d.id]?.let { return it }
        val c = cloud() ?: return emptyMap()
        return try {
            c.specs(d.id).also { specs[d.id] = it }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "опис ${d.name}: ${e.message}")
            emptyMap()
        }
    }

    /** Увімкнути / вимкнути. null — вдалось; інакше чому ні (коротко, для відповіді). */
    suspend fun power(d: HomeDevice, on: Boolean): String? {
        val code = d.switchCode ?: return "не вмикається"
        return send(d, code, on)
    }

    suspend fun brightness(d: HomeDevice, percent: Int): String? {
        val code = d.brightCode ?: return "без яскравості"
        val range = spec(d)[code]
        val min = range?.optInt("min", -1)?.takeIf { it >= 0 } ?: if (code == "bright_value_v2") 10 else 25
        val max = range?.optInt("max", -1)?.takeIf { it > min } ?: if (code == "bright_value_v2") 1000 else 255
        // Вимкнена лампа яскравість не покаже — спершу ввімкнути.
        if (d.isOn == false) d.switchCode?.let { send(d, it, true)?.let { why -> return why } }
        return send(d, code, min + (max - min) * percent.coerceIn(1, 100) / 100)
    }

    suspend fun cover(d: HomeDevice, open: Boolean): String? {
        val code = listOf("control", "mach_operate").firstOrNull { it in d.status } ?: "control"
        return send(d, code, if (code == "mach_operate") (if (open) "FZ" else "ZZ") else (if (open) "open" else "close"))
    }

    private suspend fun send(d: HomeDevice, code: String, value: Any): String? {
        if (!d.online) return "не в мережі"
        val c = cloud() ?: return "не налаштовано"
        return try {
            if (c.command(d.id, code, value)) {
                Log.i(TAG, "${d.name}: $code = $value")
                devices = devices.map { if (it.id == d.id) it.copy(status = it.status + (code to value)) else it }
                null
            } else "не відповів"
        } catch (e: TuyaException) {
            Log.w(TAG, "${d.name}: $code — ${e.code} ${e.message}")
            "не вийшло"
        } catch (e: java.io.IOException) {
            Log.w(TAG, "${d.name}: ${e.message}")
            "немає зв'язку"
        }
    }

    /** Для моделі: пристрої з останнього списку (без запиту до хмари). */
    fun summary(max: Int = 15): String = devices.take(max).joinToString("; ") { HomeText.brief(it) }

    companion object {
        private const val TAG = "SmartHome"
        const val LIST_TTL_MS = 2 * 60_000L
        const val RETRY_MS = 5 * 60_000L
    }
}
