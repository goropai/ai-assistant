package com.speedy.assistant.books

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * PDF-скан: сторінку малює вбудований PdfRenderer (~300 dpi), текст розпізнає Tesseract.
 * Поки Спіді читає сторінку (1,5–2 хв), наступна вже розпізнається у фоні; готовий текст
 * лишається в files/ocr — удруге ця книга читається без розпізнавання.
 * PdfRenderer не потокобезпечний — усе з ним в одному фоновому потоці.
 */
class ScannedPdfBook(file: File, private val ocr: Ocr, cacheRoot: File) : Book {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ocr").apply { priority = Thread.MIN_PRIORITY } }
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
    private val cache = File(cacheRoot, key(file)).apply { mkdirs() }
    private val pending = ConcurrentHashMap<Int, Future<String>>()
    @Volatile private var closed = false
    private val langFile = File(cache, "lang")
    /** Мова книги (визначена на першій сторінці з текстом); null — ще не відомо. */
    @Volatile var language: Lang? = runCatching { Lang.valueOf(langFile.readText().trim()) }.getOrNull()
        private set

    override val title: String? = null
    override val chapters = renderer.pageCount
    override val pages = true
    override val scanned = true

    /** Чекає розпізнавання (якщо сторінка ще не готова) — переривається через Thread.interrupt. */
    override fun chapter(index: Int): Chapter {
        val text = page(index).get()
        if (index + 1 < chapters) page(index + 1) // наступну — заздалегідь
        return Chapter(null, text)
    }

    /** Чи ця сторінка вже розпізнана (не доведеться чекати). */
    fun isReady(index: Int) = cached(index).exists()

    private fun page(index: Int): Future<String> {
        val file = cached(index)
        if (file.exists()) return CompletableFuture.completedFuture(file.readText())
        return pending.computeIfAbsent(index) { worker.submit(Callable { recognize(index) }) }
    }

    private fun recognize(index: Int): String {
        try {
            cached(index).takeIf { it.exists() }?.let { return it.readText() }
            if (closed) return ""
            val started = System.currentTimeMillis()
            val bitmap = renderer.openPage(index).use { p ->
                val scale = minOf(DPI / 72f, MAX_SIDE / maxOf(p.width, p.height).toFloat())
                Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE) // PdfRenderer малює на прозорому — Tesseract бачив би чорне
                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                }
            }
            val langs = language?.let(Ocr::langsFor) ?: Ocr.ALL
            val text = try { PdfBook.clean(ocr.recognize(bitmap, langs)) } finally { bitmap.recycle() }
            if (closed) return "" // перервано — неповне в кеш не пишемо
            // Мова книги — за першою сторінкою, де досить тексту; далі лише її словники (швидше).
            if (language == null && text.count { it.isLetter() } >= LANG_SAMPLE) {
                Lang.detect(text)?.let { lang ->
                    language = lang
                    langFile.writeText(lang.name)
                    Log.i(TAG, "мова скану: $lang")
                }
            }
            cached(index).writeText(text)
            Log.i(TAG, "сторінка ${index + 1}/$chapters: ${text.length} симв. за ${System.currentTimeMillis() - started} мс")
            return text
        } finally {
            pending.remove(index)
        }
    }

    override fun close() {
        closed = true
        ocr.stop()
        pending.values.forEach { it.cancel(false) }
        worker.execute {
            renderer.close()
            fd.close()
        }
        worker.shutdown()
    }

    private fun cached(index: Int) = File(cache, "$index.txt")

    companion object {
        private const val TAG = "ScannedPdfBook"
        private const val DPI = 300f
        /** Довша сторона сторінки, пікселів: більше — повільніше й не влізе в пам'ять J5. */
        private const val MAX_SIDE = 2400f
        /** Скільки літер треба, щоб упевнено визначити мову (титульна сторінка — замало). */
        private const val LANG_SAMPLE = 300

        /** Тека кешу — за шляхом, розміром і датою файлу (змінився файл — розпізнаємо заново). */
        fun key(file: File): String = MessageDigest.getInstance("SHA-1")
            .digest("${file.absolutePath}|${file.length()}|${file.lastModified()}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }
}
