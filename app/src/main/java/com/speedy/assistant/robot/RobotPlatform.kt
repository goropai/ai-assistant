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
    suspend fun move(direction: Direction, durationMs: Long, speed: Int = 60)
    suspend fun stop()

    companion object {
        /** Обмеження безпеки: одна команда руху — не довше 3 с. */
        const val MAX_MOVE_MS = 3000L
    }
}

object NoRobot : RobotPlatform {
    override val name = "none"
    override suspend fun isConnected() = false
    override suspend fun move(direction: Direction, durationMs: Long, speed: Int) {
        Log.i("NoRobot", "move $direction ${durationMs}ms (платформа не підключена)")
    }
    override suspend fun stop() {}
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

    override suspend fun move(direction: Direction, durationMs: Long, speed: Int) {
        val ms = durationMs.coerceIn(0, RobotPlatform.MAX_MOVE_MS)
        get("/move?dir=${direction.name.lowercase()}&ms=$ms&speed=${speed.coerceIn(0, 100)}")
    }

    override suspend fun stop() {
        get("/stop")
    }

    private suspend fun get(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(base + path).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w("WifiRobot", "$path failed: ${e.message}")
            false
        }
    }
}

/*
 * TODO USB: підключити бібліотеку usb-serial-for-android (com.github.mik3y), відкрити
 * UsbSerialPort на 115200 і слати рядки того самого протоколу:
 *   "M <dir> <ms> <speed>\n"  та  "S\n"
 * Потрібен USB OTG і запит дозволу через UsbManager.requestPermission.
 */
