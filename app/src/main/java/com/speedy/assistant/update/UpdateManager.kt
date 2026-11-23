package com.speedy.assistant.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "UpdateManager"

/** Завантаження майже стоїть — кидаємо (Спіді на час завантаження зайнята й не слухає). */
class SlowDownloadException(message: String) : IOException(message)

/**
 * Темп завантаження: що писати в журнал і чи не стоїть. За останнє [windowMs] прийшло менше [minBytes] —
 * занадто повільно. [now] — мс (у тестах — свій годинник).
 */
class DownloadPace(
    private val startMs: Long,
    private val windowMs: Long = 60_000,
    private val minBytes: Long = 256 * 1024,
    private val logEveryMs: Long = 30_000,
) {
    private var total = 0L
    private var windowStart = startMs
    private var windowBytes = 0L
    private var lastLog = startMs

    val bytes: Long get() = total
    val startedAt: Long get() = startMs

    /** Прийшло [n] байт; true — занадто повільно (кидаємо). */
    fun add(n: Int, now: Long): Boolean {
        total += n
        windowBytes += n
        if (now - windowStart < windowMs) return false
        val slow = windowBytes < minBytes
        windowStart = now
        windowBytes = 0
        return slow
    }

    /** Чи час написати в журнал (раз на [logEveryMs]). */
    fun shouldLog(now: Long): Boolean = (now - lastLog >= logEveryMs).also { if (it) lastLog = now }

    /** Середня швидкість, КБ/с. */
    fun kbPerSecond(now: Long): Long = if (now <= startMs) 0 else total * 1000 / 1024 / (now - startMs)
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String,
)

/**
 * Оновлення без Play Store: читає маніфест update.json, завантажує APK і відкриває
 * системний інсталятор. Тихо встановити не можна — Android завжди просить підтвердження,
 * а новий APK має бути підписаний тим самим ключем.
 *
 * Формат update.json (генерує задача `gradlew updateManifest`):
 *   {"versionCode": 2, "versionName": "0.2.0", "apkUrl": "speedy-0.2.0.apk",
 *    "sha256": "…", "notes": "що нового"}
 * apkUrl може бути відносним до адреси маніфесту.
 */
class UpdateManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val manifestUrl: String,
    private val currentVersionCode: Int,
) {
    val isConfigured: Boolean get() = manifestUrl.isNotBlank()

    private val dir: File get() = File(context.cacheDir, "updates")

    /**
     * Завантажені APK, чия версія вже встановлена (оновились) чи старша, — видалити (~45 МБ кожен).
     * Раніше лежали до наступного завантаження. Викликати при запуску.
     */
    fun cleanup() {
        dir.listFiles()?.filter { f ->
            val code = Regex("speedy-(\\d+)\\.apk").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            code == null || code <= currentVersionCode
        }?.forEach { f -> if (f.delete()) android.util.Log.i("UpdateManager", "видалено завантаження ${f.name}") }
    }

    /** @return інформацію про новішу версію або null, якщо встановлена найновіша. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()
        val json = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("update.json HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
        val info = UpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.optString("versionName", json.getInt("versionCode").toString()),
            apkUrl = manifestUrl.toHttpUrl().resolve(json.getString("apkUrl"))?.toString()
                ?: throw IOException("Некоректний apkUrl"),
            sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
            notes = json.optString("notes"),
        )
        info.takeIf { it.versionCode > currentVersionCode }
    }

    /**
     * Завантажити APK; [onHalf] — один раз, коли завантажено половину (якщо розмір відомий), з часом від початку (мс).
     * @throws SlowDownloadException завантаження майже стоїть ([DownloadPace]).
     */
    suspend fun download(info: UpdateInfo, onHalf: (elapsedMs: Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // старі завантаження
        val target = File(dir, "speedy-${info.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")

        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK HTTP ${response.code}")
            val body = response.body ?: throw IOException("Порожній APK")
            val size = body.contentLength()
            val pace = DownloadPace(android.os.SystemClock.uptimeMillis())
            var halfSaid = false
            fun mb(b: Long) = "%.1f".format(b / 1048576.0)
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (pace.shouldLog(now)) {
                            android.util.Log.i(TAG, "завантажую ${info.versionName}: ${mb(pace.bytes)} з ${if (size > 0) mb(size) else "?"} МБ, ${pace.kbPerSecond(now)} КБ/с")
                        }
                        if (pace.add(read, now)) {
                            android.util.Log.w(TAG, "завантаження стоїть: ${mb(pace.bytes)} МБ за ${(now - pace.startedAt) / 1000} с — кидаю")
                            target.delete()
                            throw SlowDownloadException("APK качається занадто повільно")
                        }
                        if (!halfSaid && size > 0 && pace.bytes * 2 >= size) { halfSaid = true; onHalf(now - pace.startedAt) }
                    }
                }
            }
            android.util.Log.i(TAG, "завантажено ${info.versionName}: ${mb(pace.bytes)} МБ")
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && !info.sha256.equals(actual, ignoreCase = true)) {
            target.delete()
            throw IOException("Контрольна сума APK не збігається")
        }
        target
    }

    /** Чи дозволено системою встановлювати APK не з магазину. */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }

    /** Відкриває налаштування, де вмикаються "Невідомі джерела". */
    fun openInstallPermissionSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        startSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Запускає системний інсталятор. Користувач має підтвердити встановлення. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startSafely(intent)
    }

    private fun startSafely(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IOException("Немає системного екрана для цієї дії", e)
        }
    }
}
