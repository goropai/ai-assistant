package com.speedy.assistant.books

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Скан: тексту немає, а словника для розпізнавання ще немає — спершу [ScanReader.prepare]. */
class OcrNotReadyException : Exception()

/** Читання сканів: книга з розпізнаванням сторінок і словник, який для цього потрібен. */
interface ScanReader {
    /** @throws OcrNotReadyException словник ще не завантажено. */
    fun open(file: File): Book
    /** Завантажити словник (якщо ще немає). Довго — у фоні. */
    suspend fun prepare()
}

/**
 * Tesseract з українським словником tessdata_fast (Apache 2.0, 3,8 МБ): не в APK — завантажується,
 * коли вперше трапився скан, і перевіряється за SHA-256. Один екземпляр на всі книги.
 */
class Ocr(private val context: Context, private val http: OkHttpClient) : ScanReader {
    private val root = File(context.filesDir, "tesseract")
    private val data = File(root, "tessdata/$LANG.traineddata")
    private var tess: TessBaseAPI? = null
    private val lock = Any()

    val ready: Boolean get() = data.exists() && data.length() == SIZE

    override fun open(file: File): Book {
        if (!ready) throw OcrNotReadyException()
        return ScannedPdfBook(file, this, File(context.filesDir, "ocr"))
    }

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        if (ready) return@withContext
        data.parentFile?.mkdirs()
        // Покладений вручну (adb push у папку застосунку) — без інтернету.
        val pushed = File(context.getExternalFilesDir(null), "$LANG.traineddata")
        if (pushed.exists() && sha256(pushed) == SHA256) {
            pushed.copyTo(data, overwrite = true)
            pushed.delete()
            return@withContext
        }
        Log.i(TAG, "завантажую словник ($LANG, ${SIZE / 1_000_000} МБ)")
        val tmp = File(data.path + ".part")
        http.newCall(Request.Builder().url(URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("tessdata HTTP ${response.code}")
            response.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        if (sha256(tmp) != SHA256) {
            tmp.delete()
            throw IOException("tessdata: контрольна сума не збігається")
        }
        if (!tmp.renameTo(data)) throw IOException("tessdata: не вдалося зберегти")
        Log.i(TAG, "словник готовий")
    }

    /**
     * Текст сторінки; "" — тексту немає (малюнок, порожня сторінка: низька впевненість —
     * це сміття на кшталт "| ~ ., '", а не слова). Повільно (десятки секунд) — не в головному потоці.
     */
    fun recognize(page: Bitmap): String = synchronized(lock) {
        val api = tess ?: TessBaseAPI().also {
            if (!it.init(root.absolutePath, LANG, TessBaseAPI.OEM_LSTM_ONLY)) {
                it.recycle()
                throw IOException("Tesseract не запустився")
            }
            it.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess = it
        }
        stopping = false
        api.setImage(page)
        // Розпізнавання — через hOCR: лише там бібліотека перевіряє stop(); getUTF8Text потім
        // віддає вже готовий результат. Перервали — результат неповний, не беремо.
        api.getHOCRText(0)
        if (stopping) {
            api.clear()
            return@synchronized ""
        }
        val text = api.getUTF8Text().orEmpty()
        val confidence = api.meanConfidence()
        api.clear()
        Log.d(TAG, "сторінка: ${text.length} симв., впевненість $confidence")
        if (confidence < MIN_CONFIDENCE) "" else text
    }

    @Volatile private var stopping = false

    /** Перервати розпізнавання, що триває (книгу закрили). */
    fun stop() {
        stopping = true
        tess?.stop()
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

    companion object {
        private const val TAG = "Ocr"
        private const val LANG = "ukr"
        /** Закріплено на коміті tessdata_fast, щоб контрольна сума не "поїхала" з новою версією. */
        private const val URL = "https://github.com/tesseract-ocr/tessdata_fast/raw/923915d4ced2a7235221788285785a29c4a42d4a/ukr.traineddata"
        private const val SHA256 = "d59e53e2bded32f4445f124b4b00240fcac7e8044c003ab822ccb94f0b3db59b"
        private const val SIZE = 3_825_102L
        private const val MIN_CONFIDENCE = 40
    }
}
