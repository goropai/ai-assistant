package com.speedy.assistant.voice

import android.content.Context
import android.util.Log
import com.speedy.assistant.data.JsonVectorFile
import com.speedy.assistant.data.VoiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.SpeakerModel
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/**
 * Відбиток голосу (тембр): x-vector моделі мовця Vosk (vosk-model-spk-0.4, Apache 2.0, 14 МБ) зі
 * звуку фрази. Модель не в APK — завантажується через Wi-Fi, як і модель офлайн-слуху (без неї
 * відбитка немає: розпізнавач Vosk потрібен і тут). Нікуди не надсилається.
 */
class VoicePrints(private val context: Context, private val ear: OfflineEar) {
    private val dir = File(context.filesDir, "vosk-spk")
    @Volatile private var spk: SpeakerModel? = null

    val ready: Boolean get() = spk != null && ear.ready
    val downloaded: Boolean get() = File(dir, READY_MARK).let { it.exists() && it.readText() == SHA256 }

    suspend fun prepare(http: OkHttpClient) = withContext(Dispatchers.IO) {
        if (spk != null) return@withContext
        if (!downloaded) download(http)
        val root = dir.listFiles()?.firstOrNull { it.isDirectory } ?: throw IOException("модель мовця не розпакована")
        spk = SpeakerModel(root.absolutePath)
        Log.i(TAG, "відбиток голосу готовий")
    }

    /** Вектор голосу фрази; null — моделі ще немає або замало мовлення. Повільно — не в головному потоці. */
    fun embed(pcm: ShortArray): FloatArray? {
        val model = spk ?: return null
        val recognizer = ear.speakerRecognizer(model) ?: return null
        return try {
            recognizer.acceptWaveForm(pcm, pcm.size)
            val json = JSONObject(recognizer.finalResult)
            val frames = json.optInt("spk_frames")
            val v = json.optJSONArray("spk")
            Log.d(TAG, "відбиток: ${pcm.size / 16} мс звуку, $frames кадрів мовлення")
            if (v == null || frames < MIN_FRAMES) null else FloatArray(v.length()) { v.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Log.w(TAG, "відбиток не вийшов: ${e.message}")
            null
        } finally {
            recognizer.close()
        }
    }

    private fun download(http: OkHttpClient) {
        Log.i(TAG, "завантажую модель мовця (14 МБ)")
        val zip = File(context.cacheDir, "vosk-spk.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("модель мовця: HTTP ${response.code}")
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
        if (digest.digest().joinToString("") { "%02x".format(it) } != SHA256) {
            zip.delete()
            throw IOException("модель мовця: контрольна сума не збігається")
        }
        dir.deleteRecursively(); dir.mkdirs()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val out = File(dir, entry.name).canonicalFile
                if (!out.path.startsWith(dir.canonicalPath)) continue // захист від "../"
                if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
        zip.delete()
        File(dir, READY_MARK).writeText(SHA256)
    }

    private companion object {
        const val TAG = "VoicePrints"
        const val URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
        const val SHA256 = "a74d8f51144484813e16af689bb0f916b7a111e2347f467c4933c1166097b5a7"
        const val READY_MARK = ".ready"
        /** Менше кадрів мовлення — відбиток випадковий. */
        const val MIN_FRAMES = 40
    }
}

/**
 * Знайомі голоси: ім'я → кілька відбитків. Як [com.speedy.assistant.vision.FaceBook], але для голосу:
 * свіжі відбитки витісняють найстаріші, зберігаємо лише змінених.
 */
class VoiceBook(private val store: VoiceStore?) {
    /** JSON-файл (тести). */
    constructor(file: File) : this(JsonVectorFile(file))

    private val voices = LinkedHashMap<String, MutableList<FloatArray>>()

    init {
        store?.loadVoices()?.forEach { (name, v) -> voices[name] = v.toMutableList() }
    }

    val names: Set<String> get() = voices.keys

    /** Найсхожіший знайомий голос і схожість (косинус); null — ще жодного. */
    @Synchronized
    fun match(v: FloatArray): Pair<String, Float>? =
        voices.flatMap { (name, samples) -> samples.map { name to cosine(it, v) } }.maxByOrNull { it.second }

    @Synchronized
    fun enroll(name: String, v: FloatArray) {
        val samples = voices.getOrPut(name) { mutableListOf() }
        samples += v
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
        store?.saveVoices(mapOf(name to samples.toList()))
    }

    @Synchronized
    fun forget(name: String) {
        if (voices.remove(name) != null) store?.saveVoices(mapOf(name to emptyList()))
    }

    @Synchronized
    fun rename(old: String, new: String) {
        val from = voices.remove(old) ?: return
        val to = voices.getOrPut(new) { mutableListOf() }
        to += from
        while (to.size > MAX_SAMPLES) to.removeAt(0)
        store?.saveVoices(mapOf(old to emptyList(), new to to.toList()))
    }

    companion object {
        const val MAX_SAMPLES = 12
        /** Від цієї схожості голос сам каже, хто говорить (сильніше за обличчя поруч). */
        const val STRONG = 0.6f

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return dot / (sqrt(na) * sqrt(nb)).coerceAtLeast(1e-10f)
        }
    }
}
