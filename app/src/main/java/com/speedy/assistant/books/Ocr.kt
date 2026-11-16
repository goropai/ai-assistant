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

/** Скан: тексту немає, а словників для розпізнавання ще немає — спершу [ScanReader.prepare]. */
class OcrNotReadyException : Exception()

/** Читання сканів: книга з розпізнаванням сторінок і словники, які для цього потрібні. */
interface ScanReader {
    /** Скільки мегабайтів доведеться завантажити ([prepare]); 0 — усе вже є. */
    val downloadMb: Int
    /** @throws OcrNotReadyException словники ще не завантажено. */
    fun open(file: File): Book
    /** Завантажити словники (яких ще немає). Довго — у фоні. */
    suspend fun prepare()
}

/**
 * Tesseract зі словниками tessdata_fast (Apache 2.0): українська, російська, англійська, по ~4 МБ.
 * Не в APK — завантажуються, коли вперше трапився скан, і перевіряються за SHA-256.
 * Один екземпляр Tesseract на всі книги; набір мов перемикається, коли змінився.
 */
class Ocr(private val context: Context, private val http: OkHttpClient) : ScanReader {
    private class Dictionary(val lang: Lang, val sha256: String, val size: Long)

    private val root = File(context.filesDir, "tesseract")
    private var tess: TessBaseAPI? = null
    private var tessLangs = ""
    private val lock = Any()
    @Volatile private var stopping = false

    private fun file(d: Dictionary) = File(root, "tessdata/${d.lang.tess}.traineddata")
    private fun ready(d: Dictionary) = file(d).let { it.exists() && it.length() == d.size }

    val ready: Boolean get() = DICTIONARIES.all(::ready)

    override val downloadMb: Int
        get() = ((DICTIONARIES.filterNot(::ready).sumOf { it.size } + 999_999) / 1_000_000).toInt()

    override fun open(file: File): Book {
        if (!ready) throw OcrNotReadyException()
        return ScannedPdfBook(file, this, File(context.filesDir, "ocr"))
    }

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        for (d in DICTIONARIES) if (!ready(d)) download(d)
    }

    private fun download(d: Dictionary) {
        val target = file(d)
        target.parentFile?.mkdirs()
        // Покладений вручну (adb push у папку застосунку) — без інтернету.
        val pushed = File(context.getExternalFilesDir(null), target.name)
        if (pushed.exists() && sha256(pushed) == d.sha256) {
            pushed.copyTo(target, overwrite = true)
            pushed.delete()
            return
        }
        Log.i(TAG, "завантажую словник ${d.lang.tess} (${d.size / 1_000_000} МБ)")
        val tmp = File(target.path + ".part")
        http.newCall(Request.Builder().url(BASE_URL + target.name).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("tessdata HTTP ${response.code}")
            response.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        if (sha256(tmp) != d.sha256) {
            tmp.delete()
            throw IOException("tessdata ${d.lang.tess}: контрольна сума не збігається")
        }
        if (!tmp.renameTo(target)) throw IOException("tessdata: не вдалося зберегти")
    }

    /**
     * Текст сторінки мовами [langs] ("ukr+eng"); "" — тексту немає (малюнок, порожня сторінка:
     * низька впевненість — це сміття на кшталт "| ~ ., '", а не слова). Повільно — не в головному потоці.
     */
    fun recognize(page: Bitmap, langs: String): String = synchronized(lock) {
        val api = tess?.takeIf { tessLangs == langs } ?: run {
            tess?.recycle()
            tess = null
            TessBaseAPI().also {
                if (!it.init(root.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY)) {
                    it.recycle()
                    throw IOException("Tesseract не запустився ($langs)")
                }
                it.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                tess = it
                tessLangs = langs
            }
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
        Log.d(TAG, "сторінка ($langs): ${text.length} симв., впевненість $confidence")
        if (confidence < MIN_CONFIDENCE) "" else text
    }

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
        /** Закріплено на коміті tessdata_fast, щоб контрольні суми не "поїхали" з новою версією. */
        private const val BASE_URL = "https://github.com/tesseract-ocr/tessdata_fast/raw/923915d4ced2a7235221788285785a29c4a42d4a/"
        private val DICTIONARIES = listOf(
            Dictionary(Lang.UK, "d59e53e2bded32f4445f124b4b00240fcac7e8044c003ab822ccb94f0b3db59b", 3_825_102L),
            Dictionary(Lang.RU, "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225", 3_861_738L),
            Dictionary(Lang.EN, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2", 4_113_088L),
        )
        private const val MIN_CONFIDENCE = 40

        /** Усі три — щоб визначити мову скану на першій сторінці з текстом. */
        val ALL = DICTIONARIES.joinToString("+") { it.lang.tess }

        /** Словники для книги цією мовою: англійські терміни в технічній літературі — завжди. */
        fun langsFor(lang: Lang) = if (lang == Lang.EN) "eng" else "${lang.tess}+eng"
    }
}
