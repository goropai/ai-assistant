package com.speedy.assistant.robot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class Direction { FORWARD, BACKWARD, LEFT, RIGHT;

    companion object {
        fun parse(value: String): Direction? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

enum class MoveResult {
    OK,
    /** Платформа відмовилась їхати вперед: датчик бачить перешкоду. */
    BLOCKED,
    /** Платформа не відповідає. */
    UNREACHABLE,
}

data class RobotStatus(
    /** Відстань до перешкоди попереду, см; null — датчика немає або попереду вільно (>4 м). */
    val distanceCm: Int?,
    val moving: Boolean,
    /** Остання подія: forward/left/…/stop/obstacle (обов'язково "obstacle", якщо зупинилась сама). */
    val last: String,
) {
    companion object {
        fun parse(json: String): RobotStatus? = try {
            val o = JSONObject(json)
            RobotStatus(
                distanceCm = o.optInt("distance_cm", -1).takeIf { it > 0 },
                moving = o.optBoolean("moving"),
                last = o.optString("last", ""),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Колісна платформа. Реалізації: Wi-Fi (ESP32 тощо) та USB-serial (Arduino тощо). */
interface RobotPlatform {
    val name: String
    suspend fun status(): RobotStatus?
    suspend fun move(direction: Direction, durationMs: Long, speed: Int = 60): MoveResult
    /** @return true, якщо платформа прийняла команду. */
    suspend fun stop(): Boolean

    companion object {
        /** Обмеження безпеки: одна команда руху — не довше 3 с. */
        const val MAX_MOVE_MS = 3000L
    }
}

object NoRobot : RobotPlatform {
    override val name = "none"
    override suspend fun status(): RobotStatus? = null
    override suspend fun move(direction: Direction, durationMs: Long, speed: Int): MoveResult {
        Log.i("NoRobot", "move $direction ${durationMs}ms (платформа не підключена)")
        return MoveResult.UNREACHABLE
    }
    override suspend fun stop() = false
}

/**
 * Простий HTTP-протокол для мікроконтролера в тій самій Wi-Fi мережі:
 *   GET /move?dir=forward&ms=800&speed=60  -> 200, або 409 якщо попереду перешкода
 *   GET /stop
 *   GET /status   -> {"distance_cm":42,"moving":false,"last":"obstacle",...}
 * Мікроконтролер сам зупиняє мотори через `ms` — навіть якщо зв'язок пропаде.
 */
class WifiRobotPlatform(
    private val http: OkHttpClient,
    baseUrl: String,
) : RobotPlatform {
    private val base = baseUrl.trimEnd('/')
    override val name = "wifi:$base"

    override suspend fun status(): RobotStatus? =
        get("/status")?.takeIf { it.first == 200 }?.let { RobotStatus.parse(it.second) }

    override suspend fun move(direction: Direction, durationMs: Long, speed: Int): MoveResult {
        val ms = durationMs.coerceIn(0, RobotPlatform.MAX_MOVE_MS)
        val response = get("/move?dir=${direction.name.lowercase()}&ms=$ms&speed=${speed.coerceIn(0, 100)}")
        return when (response?.first) {
            200 -> MoveResult.OK
            409 -> MoveResult.BLOCKED
            else -> MoveResult.UNREACHABLE
        }
    }

    override suspend fun stop(): Boolean = get("/stop")?.first == 200

    /** @return код і тіло відповіді або null, якщо плата не відповіла. */
    private suspend fun get(path: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(base + path).build()).execute().use {
                it.code to it.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.w("WifiRobot", "$path failed: ${e.message}")
            null
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

    override suspend fun status(): RobotStatus? = retrying(null, { it == null }) { it.status() }

    override suspend fun move(direction: Direction, durationMs: Long, speed: Int): MoveResult =
        retrying(MoveResult.UNREACHABLE, { it == MoveResult.UNREACHABLE }) { it.move(direction, durationMs, speed) }

    override suspend fun stop(): Boolean = retrying(false, { !it }) { it.stop() }

    private suspend fun <T> retrying(
        unreachable: T,
        failed: (T) -> Boolean,
        action: suspend (RobotPlatform) -> T,
    ): T {
        val url = currentUrl() ?: rediscover() ?: return unreachable
        val first = action(WifiRobotPlatform(http, url))
        if (!failed(first) || manualUrl().isNotBlank()) return first
        val fresh = rediscover()?.takeIf { it != url } ?: return first
        return action(WifiRobotPlatform(http, fresh))
    }
}

/*
 * TODO USB: підключити бібліотеку usb-serial-for-android (com.github.mik3y), відкрити
 * UsbSerialPort на 115200 і слати рядки того самого протоколу:
 *   "M <dir> <ms> <speed>\n"  та  "S\n"
 * Потрібен USB OTG і запит дозволу через UsbManager.requestPermission.
 */
