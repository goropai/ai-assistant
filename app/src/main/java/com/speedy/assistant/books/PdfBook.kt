package com.speedy.assistant.books

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF із текстовим шаром: розділ — сторінка, текст дістаємо, коли до неї дійшли (документ
 * відкритий, поки читаємо; на слабкому телефоні — тимчасові файли замість пам'яті).
 * Скани без тексту не читаємо ([NoTextException]): розпізнавання тексту тут немає.
 */
class PdfBook(file: File) : Book {
    private val doc = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
    private val stripper = PDFTextStripper().apply { sortByPosition = true }
    override val pages = true
    override val chapters = doc.numberOfPages
    override val title = doc.documentInformation?.title?.trim()?.takeIf { it.length >= 2 }

    init {
        try {
            // Перші сторінки бувають порожні (обкладинка) — дивимось кілька.
            val sample = (0 until minOf(chapters, 8)).sumOf { pageText(it).length }
            if (sample < MIN_TEXT) throw NoTextException()
        } catch (e: Exception) {
            doc.close()
            throw e
        }
    }

    override fun chapter(index: Int) = Chapter(null, clean(pageText(index)))

    private fun pageText(index: Int): String = synchronized(stripper) {
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(doc)
    }

    override fun close() = doc.close()

    companion object {
        private const val MIN_TEXT = 40

        /**
         * Рядки сторінки → абзаци: перенос слова ("сло-\nво") склеюємо, рядок без кінця речення
         * приєднуємо до наступного; номери сторінок окремим рядком — геть.
         */
        fun clean(page: String): String = page.replace("\r\n", "\n").replace('\r', '\n')
            .lines().map { it.trim() }.filterNot { it.matches(Regex("\\d{1,4}|-\\s*\\d{1,4}\\s*-")) }
            .joinToString("\n")
            .replace(Regex("(\\p{L})[-\\u00AD]\\n(\\p{Ll})"), "$1$2")
            .replace(Regex("(?<=[^.!?…:»”\"\\n])\\n(?=[^\\n])"), " ")
            .replace(Regex("\\n{2,}"), "\n").trim()
    }
}
