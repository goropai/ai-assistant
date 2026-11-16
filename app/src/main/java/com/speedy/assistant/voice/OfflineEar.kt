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
 * Офлайн-слух: Vosk з українською моделлю vosk-model-small-uk-v3-nano (Apache 2.0, 78 МБ).
 * Модель не в APK — завантажується один раз з alphacephei.com і перевіряється за SHA-256.
 * Слова "спіді" в словнику моделі немає — вона чує "спід і" (див. [WakeWord]).
 */
class OfflineEar(private val context: Context) {
    private val dir = File(context.filesDir, "vosk-uk")
    @Volatile private var model: Model? = null

    val ready: Boolean get() = model != null
    val downloaded: Boolean get() = File(dir, READY_MARK).exists()

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

    private fun download(http: OkHttpClient) {
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
        zip.delete()
        File(dir, READY_MARK).writeText(SHA256)
        Log.i(TAG, "модель розпакована")
    }

    companion object {
        private const val TAG = "OfflineEar"
        const val RATE = 16000
        private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip"
        private const val SHA256 = "d6b50be0a2d20f891720f3a83c98290bb2d396fd666142dbf716e8ad302f8233"
        private const val SIZE = 77_622_640L
        private const val READY_MARK = ".ready"

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
