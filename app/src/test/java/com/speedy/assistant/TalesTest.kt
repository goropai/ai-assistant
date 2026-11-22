package com.speedy.assistant

import com.speedy.assistant.books.Tales
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalesTest {
    @Test
    fun parse() {
        assertEquals("кота пана коцького", Tales.parse("знайди казку про кота пана Коцького"))
        assertEquals("колобка", Tales.parse("Спіді, почитай мені казку про колобка"))
        assertEquals("рукавичка", Tales.parse("прочитай казочку «Рукавичка»"))
        // Без назви — хай розповідає модель; книги й аудіо — не сюди.
        assertNull(Tales.parse("розкажи казку"))
        assertNull(Tales.parse("почитай книгу Кобзар"))
        assertNull(Tales.parse("увімкни аудіокнигу пан коцький"))
    }

    @Test
    fun found() {
        val body = "Жив собі в лісі кіт. ".repeat(30)
        val f = Tales.found("**Пан Коцький**\n\n$body\nhttps://example.com")!!
        assertEquals("Пан Коцький", f.title)
        assertEquals(body.trim(), f.text)
        assertNull(Tales.found("НЕМАЄ"))
        assertNull(Tales.found("Пан Коцький\nКоротко."))
        assertNull(Tales.found(null))
    }

    @Test
    fun extractFromPage() {
        val para = "Жив собі в одного чоловіка кіт, та такий старий, що вже й мишей не ловив, тож господар завіз його в ліс."
        val html = """
            <html><head><title>Пан Коцький — українська народна казка | Казки</title></head><body>
            <nav><a>Головна</a> <a>Казки</a> <a>Меню сайту</a></nav>
            <h1>Пан Коцький</h1>
            ${(1..8).joinToString("") { "<p>$para Пан Коцький сидить під деревом і думає, що робити далі.</p>" }}
            <p>— Няв!</p>
            <p>$para І зажили вони з лисичкою щасливо.</p>
            <div class="comments">Коментарі (3): Дуже гарна казка, дякую автору сайту за публікацію!</div>
            <footer>© 2026 Казки для дітей. Усі права захищено.</footer>
            </body></html>
        """.trimIndent()
        val text = Tales.extract(html, "кота пана коцького")!!
        assertEquals(true, text.startsWith(para))
        assertEquals(true, "— Няв!" in text)
        assertEquals(true, text.endsWith("І зажили вони з лисичкою щасливо."))
        assertEquals(false, "Коментарі" in text || "©" in text || "Головна" in text)
        assertEquals("Пан Коцький", Tales.pageTitle(html))
        // Інша казка на сторінці — не та (немає слів із запиту).
        assertNull(Tales.extract(html.replace("Коцький", "Котик"), "кота пана коцького"))
        // Замало тексту — анотація, не казка.
        assertNull(Tales.extract("<p>$para Пан Коцький.</p>", "пан коцький"))
        // Реклама й рядок латиницею посеред казки не обривають текст.
        val withAd = html.replaceFirst("</h1>", "</h1>\n<p>$para Початок казки про пана Коцького.</p>\n<div>Advertisement</div>\n<div>Реклама</div>")
        assertEquals(true, Tales.extract(withAd, "кота пана коцького")!!.contains("Початок казки"))
        assertEquals(false, Tales.extract(withAd, "кота пана коцького")!!.contains("Advertisement"))
        assertEquals("windows-1251", Tales.metaCharset("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1251\">"))
    }

    @Test
    fun extractSongsAndEnding() {
        // Як на сайтах казок: буквиця, пісенька короткими рядками, короткий кінець, підпис сайту.
        val long = "Біжить колобок дорогою, коли це назустріч йому зайчик, та такий швидкий, що й не наздогнати."
        val song = listOf("Я по засіку метений,", "Я із борошна спечений,", "Я від баби втік,", "Я від діда втік,", "То й від тебе втечу!")
        val html = "<p><span class=\"bukv\">Б</span>ули собі дід та баба та дожились уже до того, що й хліба нема.</p>" +
            (1..3).joinToString("") { "<p>$long</p><p>— Колобок, колобок, я тебе з'їм!</p>" + song.joinToString("") { s -> "<p>$s</p>" } } +
            "<p>$long</p><p>А лисичка — гам його! Та й з'їла!</p><p>&copy; Упорядник, 2006-2026</p>"
        val text = Tales.extract(html, "колобок")!!
        assertEquals(true, text.startsWith("Були собі дід"))
        assertEquals(true, "То й від тебе втечу!" in text)
        assertEquals(true, text.endsWith("Та й з'їла!"))
        assertEquals("Колобок", Tales.pageTitle("<title>Українська казка: Колобок</title>"))
        assertEquals("Колобок", Tales.pageTitle("<title>Колобок (народна казка) — Автор Невідомий, читати повністю текст твору онлайн. УкрЛіб</title>"))
    }

    @Test
    fun fileName() {
        assertEquals("Пан Коцький.txt", Tales.fileName("пан Коцький?"))
        assertEquals("Котик і півник.txt", Tales.fileName("котик  і півник"))
    }
}
