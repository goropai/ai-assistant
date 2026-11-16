package com.speedy.assistant

import com.speedy.assistant.books.BookCommand
import com.speedy.assistant.books.BookCommands
import com.speedy.assistant.books.Books
import com.speedy.assistant.books.Bookshelf
import com.speedy.assistant.books.Fb2Book
import com.speedy.assistant.books.Lang
import com.speedy.assistant.books.NoTextException
import com.speedy.assistant.books.Place
import com.speedy.assistant.books.Sentences
import com.speedy.assistant.books.TextBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BooksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sentencesSplitAtEndsAndParagraphs() {
        val s = Sentences.split("Жив собі кіт. Він любив молоко! «Чи ж так?» — спитав пес…\nНовий абзац без крапки")
        assertEquals(listOf("Жив собі кіт.", "Він любив молоко!", "«Чи ж так?» — спитав пес…", "Новий абзац без крапки"), s)
        // "3.5" і "т.д.," посеред речення — не кінець.
        assertEquals(1, Sentences.split("Ціна 3.5 гривні і т.д., отак").size)
    }

    @Test
    fun chunksKeepSentenceIndexAndResume() {
        val sentences = (0 until 10).map { "Речення номер $it, досить довге для перевірки." }
        val chunks = Sentences.chunks(sentences, target = 100)
        assertEquals(0, chunks.first().first)
        assertEquals(sentences.joinToString(" "), chunks.joinToString(" ") { it.text })
        // Продовження з 7-го речення — перший фрагмент саме з нього.
        val resumed = Sentences.chunks(sentences, from = 7, target = 100)
        assertEquals(7, resumed.first().first)
        assertTrue(resumed.first().text.startsWith("Речення номер 7"))
        // Задовге речення ріжемо, а не віддаємо синтезатору цілим.
        val long = Sentences.chunks(listOf(List(60) { "слово" }.joinToString(", ")), max = 100)
        assertTrue(long.size > 1 && long.all { it.text.length <= 100 && it.first == 0 })
    }

    @Test
    fun textBookChaptersByHeadingsOrSize() {
        val book = TextBook.parse("Передмова.\n\nРозділ 1\nПочаток.\n\nРозділ 2\nКінець.")
        assertEquals(3, book.chapters)
        assertEquals("Розділ 2", book.chapter(2).title)
        assertEquals("Розділ 2\nКінець.", book.chapter(2).text)
        val plain = TextBook.parse(List(400) { "Абзац номер $it без заголовків, але досить довгий текст." }.joinToString("\n"))
        assertTrue(plain.chapters > 1)
    }

    @Test
    fun decodesUtf8AndCp1251() {
        assertEquals("Кобзар", Books.decode("Кобзар".toByteArray(Charsets.UTF_8)))
        assertEquals("Кобзар", Books.decode("Кобзар".toByteArray(charset("windows-1251"))))
    }

    @Test
    fun fb2SectionsTitleAndNotesSkipped() {
        val xml = """<?xml version="1.0" encoding="windows-1251"?>
            <FictionBook><description><title-info><book-title>Казки</book-title></title-info></description>
            <body><title><p>Казки</p></title>
              <section><title><p>Ріпка</p></title><p>Посадив дід ріпку.</p><p>Виросла ріпка &#8212; велика.</p></section>
              <section><title><p>Колобок</p></title><p>Жили собі дід та баба.</p></section>
            </body>
            <body name="notes"><section><p>Примітка, яку не читаємо.</p></section></body>
            <binary id="x">QUJD</binary></FictionBook>"""
        val book = Fb2Book.parse(xml.toByteArray(charset("windows-1251")))
        assertEquals("Казки", book.title)
        assertEquals(listOf("Казки", "Ріпка", "Колобок"), (0 until book.chapters).map { book.chapter(it).title })
        assertEquals("Ріпка\nПосадив дід ріпку.\nВиросла ріпка — велика.", book.chapter(1).text)
        assertTrue((0 until book.chapters).none { "Примітка" in book.chapter(it).text })
    }

    @Test
    fun epubSpineOrder() {
        val file = tmp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<package><metadata><dc:title>Лісова пісня</dc:title></metadata>
                <manifest><item href="Text/b.xhtml" id="b" media-type="application/xhtml+xml"/><item id="a" href="Text/a.xhtml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""")
            put("OEBPS/Text/a.xhtml", "<html><head><title>x</title></head><body><h1>Пролог</h1><p>Старий ліс.</p></body></html>")
            put("OEBPS/Text/b.xhtml", "<html><body><h2>Дія перша</h2><p>Весна&nbsp;рання.</p></body></html>")
        }
        Books.open(file).use { book ->
            assertEquals("Лісова пісня", book.title)
            assertEquals(2, book.chapters)
            assertEquals("Пролог", book.chapter(0).title)
            assertEquals("Пролог\nСтарий ліс.", book.chapter(0).text)
            assertEquals("Дія перша\nВесна рання.", book.chapter(1).text)
        }
    }

    @Test
    fun pdfPageLinesBecomeParagraphs() {
        val page = "Розділ перший.\nЖили собі дід та ба-\nба, і була в них\nкурочка Ряба.\n17\nНовий абзац."
        assertEquals("Розділ перший.\nЖили собі дід та баба, і була в них курочка Ряба.\nНовий абзац.", com.speedy.assistant.books.PdfBook.clean(page))
        assertTrue(Books.isBook(File("Statement.PDF")))
    }

    @Test
    fun scannedFallbackOnlyForPdfWithoutText() {
        val opened = mutableListOf<String>()
        val scans = object : com.speedy.assistant.books.ScanReader {
            override val downloadMb = 0
            override fun open(file: File): com.speedy.assistant.books.Book {
                opened += file.name
                throw com.speedy.assistant.books.OcrNotReadyException()
            }
            override suspend fun prepare() {}
        }
        val shelf = Bookshelf({ emptyList() }, null, scans = scans)
        // FB2 без тексту — не скан: розпізнавання не пробуємо.
        val fb2 = tmp.newFile("empty.fb2").apply { writeText("<FictionBook/>") }
        try { shelf.open(fb2); throw AssertionError() } catch (e: NoTextException) {}
        assertTrue(opened.isEmpty())
    }

    @Test(expected = NoTextException::class)
    fun fb2WithoutBody() {
        Fb2Book.parse("<FictionBook><description/></FictionBook>".toByteArray())
    }

    @Test
    fun findsBooksEverywhereAndMatchesTitle() {
        val root = tmp.newFolder("sdcard")
        File(root, "Download/books").mkdirs()
        File(root, "Documents/Діти").mkdirs()
        File(root, "Android/data/com.app").mkdirs()
        File(root, "Download/books/Harry_Potter_i_filosofskyi_kamin.epub").writeText("x")
        File(root, "Documents/Діти/Кобзар.fb2").writeText("x")
        File(root, "Documents/Shevchenko.Kobzar.fb2.zip").writeText("x")
        File(root, "Android/data/com.app/hidden.epub").writeText("x")
        File(root, "Documents/notes.txt").writeText("коротка нотатка")
        File(root, "Documents/Тіні забутих предків.txt").writeText("а".repeat(30_000))
        val shelf = Bookshelf({ listOf(root, root) }, null)
        assertEquals(
            setOf("Harry_Potter_i_filosofskyi_kamin.epub", "Кобзар.fb2", "Shevchenko.Kobzar.fb2.zip", "Тіні забутих предків.txt"),
            shelf.all().map { it.name }.toSet(),
        )
        assertEquals("Harry_Potter_i_filosofskyi_kamin.epub", shelf.find("гаррі поттера")?.name)
        assertEquals("Тіні забутих предків.txt", shelf.find("тіні забутих предків")?.name)
        assertTrue(shelf.find("кобзаря")?.name in setOf("Кобзар.fb2", "Shevchenko.Kobzar.fb2.zip"))
        assertNull(shelf.find("війна і мир"))
    }

    @Test
    fun knownBooksFoundWithoutScanning() {
        val json = File(tmp.root, "books.json")
        val stone = tmp.newFile("hp1.epub")
        val chamber = tmp.newFile("Harry_Potter_i_taiemna_kimnata.epub")
        var scanned = false
        val shelf = Bookshelf({ scanned = true; listOf(tmp.root) }, json)
        shelf.save(stone, null, Place(2, 5, 17, "Гаррі Поттер і філософський камінь"))
        // Назва з метаданих книги (ім'я файлу "hp1" нічого не каже) — і без обходу сховища.
        assertEquals(stone, shelf.find("гаррі поттер і філософський камінь"))
        assertTrue(!scanned)
        // Інша частина: прочитана підходить лише частково — шукаємо по сховищу.
        assertEquals(chamber, shelf.find("гаррі поттер і таємна кімната"))
        assertTrue(scanned)
    }

    @Test
    fun detectsLanguage() {
        assertEquals(Lang.EN, Lang.detect("The quick brown fox jumps over the lazy dog."))
        assertEquals(Lang.UK, Lang.detect("Їжак ішов лісом і співав пісню."))
        assertEquals(Lang.RU, Lang.detect("Съешь же ещё этих мягких французских булок."))
        // Російська книга з кодом: фрагмент коду — англійською, рядок без "своїх" літер — мовою розділу.
        assertEquals(Lang.EN, Lang.detect("val list = mutableListOf<String>()", Lang.RU))
        assertEquals(Lang.RU, Lang.detect("Рассмотрим функцию map", Lang.RU))
        assertNull(Lang.detect("42 + 7"))
    }

    @Test
    fun englishTitlesHeardInUkrainian() {
        val root = tmp.newFolder("tech")
        listOf("Clean_Code.pdf", "Learning_Python_5th_Edition.pdf", "JavaScript.The.Definitive.Guide.epub", "Kotlin_in_Action.pdf")
            .forEach { File(root, it).writeText("x") }
        val shelf = Bookshelf({ listOf(root) }, null)
        assertEquals("Clean_Code.pdf", shelf.find("клін код")?.name)
        assertEquals("Learning_Python_5th_Edition.pdf", shelf.find("пітон")?.name)
        assertEquals("JavaScript.The.Definitive.Guide.epub", shelf.find("джаваскрипт")?.name)
        assertEquals("Kotlin_in_Action.pdf", shelf.find("котлін ін екшн")?.name)
    }

    @Test
    fun progressSurvivesRestart() {
        val json = File(tmp.root, "books.json")
        val book = tmp.newFile("kobzar.fb2")
        Bookshelf({ emptyList() }, json).save(book, "Костя", Place(3, 17, 10, "Кобзар"))
        val again = Bookshelf({ emptyList() }, json)
        assertEquals(Place(3, 17, 10, "Кобзар"), again.place(book))
        assertEquals(book.path, again.last("Костя")?.path)
        assertEquals(book.path, again.last("Ніна")?.path) // Ніна ще нічого не слухала — остання взагалі
        assertEquals("Кобзар", again.title(book))
    }

    @Test
    fun bookCommands() {
        assertEquals(BookCommand.Read("кобзар", true), BookCommands.parse("Спіді почитай мені книгу Кобзар".removePrefix("Спіді ")))
        assertEquals(BookCommand.Read("гаррі поттера", false), BookCommands.parse("почитай Гаррі Поттера"))
        assertEquals(BookCommand.Read(null, true), BookCommands.parse("почитай книжку"))
        assertEquals(BookCommand.Read(null, false), BookCommands.parse("почитай мені"))
        assertEquals(BookCommand.Continue(false), BookCommands.parse("продовж читати"))
        assertEquals(BookCommand.Continue(false), BookCommands.parse("читай далі"))
        assertEquals(BookCommand.Continue(true), BookCommands.parse("продовжуй"))
        assertEquals(BookCommand.List, BookCommands.parse("які в тебе є книги?"))
        assertEquals(BookCommand.Which, BookCommands.parse("яку книгу ми читаємо"))
        assertEquals(BookCommand.FromStart, BookCommands.parse("читай з початку"))
        assertEquals(BookCommand.ChapterStart, BookCommands.parse("почитай з початку розділу"))
        assertEquals(BookCommand.NextChapter, BookCommands.parse("наступний розділ"))
        assertEquals(BookCommand.PreviousChapter, BookCommands.parse("попередня сторінка"))
        // Не книги: сповіщення, екран, музика, казка від моделі.
        assertNull(BookCommands.parse("прочитай сповіщення"))
        assertNull(BookCommands.parse("прочитай що на екрані"))
        assertNull(BookCommands.parse("увімкни музику"))
        assertNull(BookCommands.parse("почитай казку"))
    }
}
