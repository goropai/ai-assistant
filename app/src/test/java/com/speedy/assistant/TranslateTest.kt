package com.speedy.assistant

import com.speedy.assistant.books.BookCommand
import com.speedy.assistant.books.BookCommands
import com.speedy.assistant.books.Bookshelf
import com.speedy.assistant.books.Lang
import com.speedy.assistant.books.Place
import com.speedy.assistant.translate.TranslateCommand
import com.speedy.assistant.translate.TranslateCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranslateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun phraseCommands() {
        assertEquals(TranslateCommand.Phrase("я люблю каву", Lang.UK, Lang.EN), TranslateCommands.parse("Переклади на англійську я люблю каву"))
        assertEquals(TranslateCommand.Phrase("кіт", Lang.UK, Lang.RU), TranslateCommands.parse("як російською буде кіт"))
        // Фразу іншою мовою Спіді почує окремо, після "кажи англійською".
        assertEquals(TranslateCommand.Phrase(null, Lang.EN, Lang.UK), TranslateCommands.parse("переклади з англійської"))
        assertEquals(TranslateCommand.Phrase(null, Lang.RU, Lang.UK), TranslateCommands.parse("переклади з російської на українську"))
        assertEquals(TranslateCommand.Phrase(null, Lang.EN, Lang.RU), TranslateCommands.parse("переклади з англійської на російську"))
        assertEquals(TranslateCommand.Phrase(null, Lang.UK, Lang.EN), TranslateCommands.parse("переклади на англійську"))
        assertNull(TranslateCommands.parse("переклади цю статтю"))
    }

    @Test
    fun bookTranslationCommands() {
        assertEquals(TranslateCommand.Book(true), TranslateCommands.parse("читай українською"))
        assertEquals(TranslateCommand.Book(true), TranslateCommands.parse("перекладай українською"))
        assertEquals(TranslateCommand.Book(false), TranslateCommands.parse("читай в оригіналі"))
        assertEquals(TranslateCommand.Book(false), TranslateCommands.parse("не перекладай"))
        assertEquals(BookCommand.Read("клін код", true, translate = true), BookCommands.parse("почитай книгу клін код українською"))
        assertEquals(BookCommand.Read("кобзар", true, translate = false), BookCommands.parse("почитай книгу кобзар"))
    }

    @Test
    fun translateFlagPersists() {
        val json = File(tmp.root, "books.json")
        val book = tmp.newFile("clean_code.pdf")
        Bookshelf({ emptyList() }, json).save(book, null, Place(4, 2, 300, "Clean Code", translate = true))
        assertEquals(true, Bookshelf({ emptyList() }, json).place(book)?.translate)
    }
}
