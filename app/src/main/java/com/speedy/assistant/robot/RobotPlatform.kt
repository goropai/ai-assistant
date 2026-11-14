package com.speedy.assistant.robot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class Direction { FORWARD, BACKWARD, LEFT, RIGHT;

    companion object {
        fun parse(value: String): Direction? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Колісна платформа. Реалізації: Wi-Fi (ESP32 тощо) та USB-serial (Arduino тощо). */
interface RobotPlatform {
    val name: String
    suspend fun isConnected(): Boolean
    /** @return true, якщо платформа прийняла команду. */
    suspend fun move(direction: Direction, durationMs: Long, speed: Int = 60): Boolean
    suspend fun stop(): Boolean

    companion object {
        /** Обмеження безпеки: одна команда руху — не довше 3 с. */
        const val MAX_MOVE_MS = 3000L
    }
}

object NoRobot : RobotPlatform {
    override val name = "none"
    override suspend fun isConnected() = false
    override suspend fun move(direction: Direction, durationMs: Long, speed: Int): Boolean {
        Log.i("NoRobot", "move $direction ${durationMs}ms (платформа не підключена)")
        return false
    }
    override suspend fun stop() = false
}

/**
 * Простий HTTP-протокол для мікроконтролера в тій самій Wi-Fi мережі:
 *   GET /move?dir=forward&ms=800&speed=60
 *   GET /stop
 *   GET /status   -> 200 OK
 * Мікроконтролер сам зупиняє мотори через `ms` — навіть якщо зв'язок пропаде.
 */
class WifiRobotPlatform(
    private val http: OkHttpClient,
    baseUrl: String,
) : RobotPlatform {
    private val base = baseUrl.trimEnd('/')
    override val name = "wifi:$base"

    override suspend fun isConnected(): Boolean = get("/status")

    override suspend fun move(direction: Direction, durationMs: Long, speed: Int): Boolean {
        val ms = durationMs.coerceIn(0, RobotPlatform.MAX_MOVE_MS)
        return get("/move?dir=${direction.name.lowercase()}&ms=$ms&speed=${speed.coerceIn(0, 100)}")
    }

    override suspend fun stop(): Boolean = get("/stop")

    private suspend fun get(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(base + path).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w("WifiRobot", "$path failed: ${e.message}")
            false
        }
    }
}

/**
 * Платформа з автопошуком: ручна адреса з налаштувань має пріоритет, інакше — знайдена
 * через [RobotDiscovery] (запам'ятовується). Якщо знайдена плата не відповідає
 * (роутер видав нову IP), шукаємо ще раз і повторюємо команду.
 */
class AutoRobotPlatform(
    private val http: OkHttpClient,
    private val discovery: RobotDiscovery,
    private val manualUrl: () -> String,
    private val cachedUrl: () -> String,
    private val saveFoundUrl: (String) -> Unit,
) : RobotPlatform {
    override val name get() = currentUrl()?.let { "wifi:$it" } ?: "none"

    private fun currentUrl(): String? =
        manualUrl().ifBlank { cachedUrl() }.takeIf { it.isNotBlank() }

    /** Примусовий пошук (голосова команда, старт застосунку). */
    suspend fun rediscover(): String? {
        if (manualUrl().isNotBlank()) return manualUrl()
        val found = discovery.discover() ?: return null
        saveFoundUrl(found)
        return found
    }

    override suspend fun isConnected(): Boolean = withPlatform { it.isConnected() }

    override suspend fun move(direction: Direction, durationMs: Long, speed: Int): Boolean =
        withPlatform { it.move(direction, durationMs, speed) }

    override suspend fun stop(): Boolean = withPlatform { it.stop() }

    private suspend fun withPlatform(action: suspend (RobotPlatform) -> Boolean): Boolean {
        val url = currentUrl() ?: rediscover() ?: return false
        if (action(WifiRobotPlatform(http, url))) return true
        if (manualUrl().isNotBlank()) return false
        val fresh = rediscover()?.takeIf { it != url } ?: return false
        return action(WifiRobotPlatform(http, fresh))
    }
}

/*
 * TODO USB: підключити бібліотеку usb-serial-for-android (com.github.mik3y), відкрити
 * UsbSerialPort на 115200 і слати рядки того самого протоколу:
 *   "M <dir> <ms> <speed>\n"  та  "S\n"
 * Потрібен USB OTG і запит дозволу через UsbManager.requestPermission.
 */
