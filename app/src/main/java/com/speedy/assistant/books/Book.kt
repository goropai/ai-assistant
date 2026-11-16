package com.speedy.assistant.books

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Розділ (або сторінка PDF): назва, якщо відома, і текст абзацами через "\n". */
data class Chapter(val title: String?, val text: String)

/** Книга, що читається розділ за розділом (EPUB і PDF — не весь текст одразу в пам'яті). */
interface Book : Closeable {
    val title: String?
    val chapters: Int
    /** Розділи — сторінки (PDF): "сторінка 5", а не "розділ 5". */
    val pages: Boolean get() = false
    fun chapter(index: Int): Chapter
    override fun close() {}
}

/** Файл є, але тексту в ньому немає (скан без текстового шару). */
class NoTextException : Exception()

object Books {
    /** Які файли вважаємо книгами (".fb2.zip" — за останнім розширенням "zip", див. [isBook]). */
    val EXTENSIONS = setOf("txt", "fb2", "epub")

    fun isBook(file: File): Boolean {
        val name = file.name.lowercase()
        return name.substringAfterLast('.') in EXTENSIONS || name.endsWith(".fb2.zip")
    }

    /** @throws NoTextException тексту немає; інші винятки — файл пошкоджений або не той формат. */
    fun open(file: File): Book {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".epub") -> EpubBook(file)
            name.endsWith(".fb2") -> Fb2Book.parse(file.readBytes())
            name.endsWith(".fb2.zip") -> ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".fb2") }
                    ?: throw NoTextException()
                Fb2Book.parse(zip.getInputStream(entry).use { it.readBytes() })
            }
            else -> TextBook.parse(decode(file.readBytes()))
        }
    }

    /** UTF-8 (з BOM чи без), UTF-16 з BOM, інакше — Windows-1251 (старі українські TXT). */
    fun decode(bytes: ByteArray, declared: String? = null): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) {
            return String(bytes, Charsets.UTF_16)
        }
        declared?.let { cs -> runCatching { return String(bytes, Charset.forName(cs)) } }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    /** Назва з імені файлу: "Shevchenko_Kobzar.fb2.zip" → "Shevchenko Kobzar". */
    fun titleFromName(file: File): String =
        file.name.replace(Regex("(?i)(\\.fb2)?\\.(zip|txt|fb2|epub|pdf)$"), "")
            .replace(Regex("[_.]+"), " ").replace(Regex("\\s+"), " ").trim()
}

/** Розмітка (XHTML, FB2) → текст: блоки — з нового рядка, теги геть, сутності — символами. */
object Markup {
    private val drop = Regex("(?is)<(head|script|style|binary)\\b.*?</\\1\\s*>|<!--.*?-->")
    private val blockEnd = Regex("(?i)</(p|div|h[1-6]|li|title|subtitle|v|stanza|blockquote|tr|section|epigraph|text-author)\\s*>|<br\\s*/?>|<empty-line\\s*/?>")
    private val tag = Regex("<[^>]*>")
    private val entity = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "bdquo" to "„", "shy" to "",
    )

    fun text(markup: String): String {
        val plain = markup.replace(drop, " ").replace(blockEnd, "\n").replace(tag, " ")
        return unescape(plain).replace("\u00AD", "")
            .lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun unescape(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> named[e]
        } ?: m.value
    }

    /** Значення атрибута в одному тезі: attr(`<item id="x" href="a.html">`, "href") → "a.html". */
    fun attr(tag: String, name: String): String? =
        Regex("(?i)\\b$name\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2)?.let(::unescape)
}

/** TXT: розділи за рядками "Розділ 3", "Глава 1", "Chapter 2"; немає таких — частини по ~15 тис. символів. */
class TextBook(override val title: String?, private val parts: List<Chapter>) : Book {
    override val chapters get() = parts.size
    override fun chapter(index: Int) = parts[index]

    companion object {
        private val heading = Regex("(?iu)^(розділ|глава|частина|chapter|part)\\s+[\\p{L}\\d]+.{0,50}$")
        private const val PART = 15_000

        fun parse(text: String, title: String? = null): TextBook {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().map { it.trim() }
            val headings = lines.indices.filter { heading.matches(lines[it]) }
            val parts = if (headings.size >= 2) {
                val starts = (listOf(0) + headings).distinct()
                starts.mapIndexed { i, from ->
                    val until = starts.getOrElse(i + 1) { lines.size }
                    Chapter(lines[from].takeIf { from in headings }, lines.subList(from, until).filter { it.isNotEmpty() }.joinToString("\n"))
                }.filter { it.text.isNotBlank() }
            } else bySize(lines.filter { it.isNotEmpty() })
            return TextBook(title, parts)
        }

        /** Абзаци — частинами по ~[PART] символів (межа завжди між абзацами). */
        fun bySize(paragraphs: List<String>): List<Chapter> {
            val out = mutableListOf<Chapter>()
            val buf = StringBuilder()
            for (p in paragraphs) {
                if (buf.isNotEmpty() && buf.length + p.length > PART) {
                    out += Chapter(null, buf.toString())
                    buf.clear()
                }
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(p)
            }
            if (buf.isNotEmpty()) out += Chapter(null, buf.toString())
            return out
        }
    }
}

/** FB2 (XML): розділи — за заголовками <title>, примітки (<body name="notes">) пропускаємо. */
object Fb2Book {
    private val prolog = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']")
    private val body = Regex("(?is)<body\\b([^>]*)>(.*?)</body\\s*>")
    private val titleTag = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
    private val bookTitle = Regex("(?is)<book-title\\b[^>]*>(.*?)</book-title\\s*>")

    fun parse(bytes: ByteArray): TextBook {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val xml = Books.decode(bytes, prolog.find(head)?.groupValues?.get(1))
        val title = bookTitle.find(xml)?.groupValues?.get(1)?.let { Markup.text(it).replace('\n', ' ') }?.takeIf { it.isNotBlank() }
        val main = body.findAll(xml).firstOrNull { Markup.attr("<x${it.groupValues[1]}>", "name") != "notes" }
            ?.groupValues?.get(2) ?: throw NoTextException()
        // Межі розділів — початки заголовків (перший у <body> — зазвичай назва книги з епіграфом).
        val starts = titleTag.findAll(main).map { it.range.first }.toList()
        val cuts = (listOf(0) + starts).distinct()
        val chapters = cuts.mapIndexed { i, from ->
            val piece = main.substring(from, cuts.getOrElse(i + 1) { main.length })
            val name = titleTag.find(piece)?.takeIf { it.range.first == 0 }?.groupValues?.get(1)
                ?.let { Markup.text(it).replace('\n', ' ') }
            Chapter(name, Markup.text(piece))
        }.filter { it.text.isNotBlank() }
        if (chapters.isEmpty()) throw NoTextException()
        // Великі розділи без заголовків (вся книга одним шматком) — частинами.
        val parts = if (chapters.size == 1 && chapters[0].text.length > 30_000) TextBook.bySize(chapters[0].text.lines()) else chapters
        return TextBook(title, parts)
    }
}

/** EPUB (zip із XHTML): порядок розділів — spine у пакеті OPF; розділ читається, коли до нього дійшли. */
class EpubBook(file: File) : Book {
    private val zip = ZipFile(file)
    private val spine: List<String>
    override val title: String?

    init {
        try {
            val container = entryText("META-INF/container.xml") ?: throw NoTextException()
            val rootTag = Regex("(?is)<rootfile\\b[^>]*>").find(container)?.value ?: throw NoTextException()
            val opfPath = Markup.attr(rootTag, "full-path") ?: throw NoTextException()
            val opf = entryText(opfPath) ?: throw NoTextException()
            val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val items = Regex("(?is)<item\\b[^>]*>").findAll(opf).mapNotNull { m ->
                val id = Markup.attr(m.value, "id") ?: return@mapNotNull null
                val href = Markup.attr(m.value, "href") ?: return@mapNotNull null
                id to resolve(base, java.net.URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8"))
            }.toMap()
            spine = Regex("(?is)<itemref\\b[^>]*>").findAll(opf)
                .filter { Markup.attr(it.value, "linear") != "no" }
                .mapNotNull { Markup.attr(it.value, "idref")?.let(items::get) }.distinct().toList()
            if (spine.isEmpty()) throw NoTextException()
            title = Regex("(?is)<dc:title\\b[^>]*>(.*?)</dc:title\\s*>").find(opf)?.groupValues?.get(1)
                ?.let { Markup.text(it).replace('\n', ' ') }?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    override val chapters get() = spine.size

    override fun chapter(index: Int): Chapter {
        val html = entryText(spine[index]).orEmpty()
        val name = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]\\s*>").find(html)?.groupValues?.get(1)
            ?.let { Markup.text(it).replace('\n', ' ') }?.takeIf { it.isNotBlank() }
        return Chapter(name, Markup.text(html))
    }

    override fun close() = zip.close()

    private fun entryText(path: String): String? {
        val entry = zip.getEntry(path)
            ?: zip.entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }
            ?: return null
        return Books.decode(zip.getInputStream(entry).use { it.readBytes() })
    }

    /** "OEBPS/" + "../Text/ch1.html" → "Text/ch1.html". */
    private fun resolve(base: String, href: String): String {
        val parts = ArrayDeque<String>()
        for (p in (base + href).split('/')) when (p) {
            "", "." -> {}
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(p)
        }
        return parts.joinToString("/")
    }
}
