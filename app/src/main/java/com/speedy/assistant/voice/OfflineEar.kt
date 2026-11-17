package com.speedy.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Офлайн-слух: Vosk з українською моделлю vosk-model-small-uk-v3-small (Apache 2.0, 144 МБ).
 * Модель не в APK — завантажується один раз з alphacephei.com і перевіряється за SHA-256.
 * (Модель nano на мікрофоні Galaxy J5 майже нічого не розбирала й не знала слова "спіді".)
 */
class OfflineEar(private val context: Context) {
    private val dir = File(context.filesDir, "vosk-uk")
    @Volatile private var model: Model? = null

    val ready: Boolean get() = model != null
    /** Розпакована саме поточна модель (позначка містить її SHA-256 — стара модель замінюється). */
    val downloaded: Boolean get() = File(dir, READY_MARK).let { it.exists() && it.readText() == SHA256 }

    /** Завантажити (якщо ще немає) і підняти модель. Довго — викликати у фоні. */
    suspend fun prepare(http: OkHttpClient) = withContext(Dispatchers.IO) {
        if (model != null) return@withContext
        if (!downloaded) download(http)
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val root = dir.listFiles()?.firstOrNull { it.isDirectory } ?: throw IOException("Vosk: модель не розпакована")
        model = Model(root.absolutePath)
        Log.i(TAG, "офлайн-слух готовий")
    }

    /** Новий розпізнавач для одного сеансу (не потокобезпечний — один потік). */
    fun recognizer(): Recognizer? = model?.let { Recognizer(it, RATE.toFloat()).apply { setMaxAlternatives(3) } }

    /** Розпізнавач із моделлю мовця — щоб отримати відбиток голосу (вектор "spk"). */
    fun speakerRecognizer(spk: org.vosk.SpeakerModel): Recognizer? = model?.let { Recognizer(it, RATE.toFloat(), spk) }

    private fun download(http: OkHttpClient) {
        // Архів, покладений вручну (adb push у папку застосунку) — без інтернету.
        val pushed = File(context.getExternalFilesDir(null), ZIP_NAME)
        if (pushed.exists()) {
            Log.i(TAG, "знайшла архів моделі на телефоні")
            if (sha256(pushed) == SHA256) {
                unzip(pushed)
                pushed.delete()
                return
            }
            Log.w(TAG, "архів на телефоні пошкоджений — завантажую")
            pushed.delete()
        }
        Log.i(TAG, "завантажую модель (${SIZE / 1_000_000} МБ)")
        val zip = File(context.cacheDir, "vosk-uk.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        val long = http.newBuilder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // сайт моделей з телефона відповідає повільно
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.MINUTES).build()
        long.newCall(Request.Builder().url(URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Vosk HTTP ${response.code}")
            response.body!!.byteStream().use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != SHA256) {
            zip.delete()
            throw IOException("Vosk: контрольна сума не збігається")
        }
        unzip(zip)
        zip.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(zip: File) {
        dir.deleteRecursively()
        dir.mkdirs()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val out = File(dir, entry.name).canonicalFile
                if (!out.path.startsWith(dir.canonicalPath)) continue // захист від "../"
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
        File(dir, READY_MARK).writeText(SHA256)
        Log.i(TAG, "модель розпакована")
    }

    companion object {
        private const val TAG = "OfflineEar"
        const val RATE = 16000
        private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip"
        private const val SHA256 = "ee05b6a8c796c53c61cd1c821d38e897e5bd67b4d691d8797ec8f9e5100d4247"
        private const val SIZE = 143_914_407L
        private const val READY_MARK = ".ready"
        /** Ім'я архіву для ручного встановлення: adb push … /sdcard/Android/data/<пакет>/files/ */
        const val ZIP_NAME = "vosk-model-small-uk-v3-small.zip"

        /** Варіанти розпізнаного тексту з результату Vosk (alternatives або text). */
        fun phrases(json: String): List<String> {
            val o = JSONObject(json)
            val alts = o.optJSONArray("alternatives")
            val list = if (alts != null) (0 until alts.length()).map { alts.getJSONObject(it).optString("text") }
            else listOf(o.optString("text"))
            return list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
    }
}
