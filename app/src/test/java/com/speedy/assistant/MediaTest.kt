package com.speedy.assistant

import com.speedy.assistant.books.StorageScan
import com.speedy.assistant.media.MediaCommand
import com.speedy.assistant.media.MediaCommands
import com.speedy.assistant.media.MediaFiles
import com.speedy.assistant.media.MediaKind
import com.speedy.assistant.media.MediaPlace
import com.speedy.assistant.media.MediaShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class MediaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Файл потрібного розміру без запису вмісту (медіа менші за поріг — не медіа). */
    private fun media(dir: File, name: String, mb: Int = 5) = File(dir, name).also { f ->
        f.parentFile.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(mb * 1_000_000L) }
    }

    @Test
    fun commands() {
        assertEquals(MediaCommand.Play("дюна", MediaKind.VIDEO), MediaCommands.parse("Увімкни фільм Дюна"))
        assertEquals(MediaCommand.Play("кобзар", MediaKind.AUDIO), MediaCommands.parse("постав аудіокнигу Кобзар"))
        assertEquals(MediaCommand.Play("гаррі поттер", null), MediaCommands.parse("увімкни Гаррі Поттер"))
        assertEquals(MediaCommand.Play(null, MediaKind.VIDEO), MediaCommands.parse("увімкни мультик"))
        assertEquals(MediaCommand.Continue(MediaKind.VIDEO), MediaCommands.parse("наступна серія"))
        assertEquals(MediaCommand.Continue(MediaKind.VIDEO), MediaCommands.parse("продовж дивитись"))
        assertEquals(MediaCommand.Continue(MediaKind.AUDIO), MediaCommands.parse("продовж аудіокнигу"))
        assertEquals(MediaCommand.Pause, MediaCommands.parse("пауза"))
        assertEquals(MediaCommand.List(MediaKind.VIDEO), MediaCommands.parse("які в тебе є фільми?"))
        // Не медіа: світло, книги (їх читає сама Спіді), "покажи" без "фільм".
        assertNull(MediaCommands.parse("увімкни світло"))
        assertNull(MediaCommands.parse("увімкни книгу Кобзар"))
        assertNull(MediaCommands.parse("покажи фото"))
    }

    @Test
    fun foldersBecomeSeriesInNaturalOrder() {
        val root = tmp.newFolder("sd")
        listOf("10.mp3", "2.mp3", "1.mp3").forEach { media(File(root, "Audiobooks/Kobzar"), it, 1) }
        media(File(root, "Movies"), "Dune.2021.mkv")
        File(root, "Movies/trailer.mp4").writeText("малий — не медіа")
        val items = MediaShelf(StorageScan({ listOf(root) }, MediaFiles::isMedia), null).all()
        val book = items.single { it.tracks.size > 1 }
        assertEquals("Kobzar", book.title)
        assertEquals(MediaKind.AUDIO, book.kind)
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), book.tracks.map { it.name })
        assertEquals(setOf("Kobzar", "1", "2", "10", "Dune 2021"), items.map { it.title }.toSet())
    }

    @Test
    fun findsByTitleAndRemembersPlace() {
        val root = tmp.newFolder("sd")
        media(File(root, "Movies"), "Dune.2021.mkv")
        listOf("01.mp3", "02.mp3").forEach { media(File(root, "Аудіокниги/Тіні забутих предків"), it, 1) }
        val json = File(tmp.root, "media.json")
        val shelf = MediaShelf(StorageScan({ listOf(root) }, MediaFiles::isMedia), json)
        val dune = shelf.find("дюна", MediaKind.VIDEO)!!
        assertEquals("Dune.2021.mkv", File(dune.key).name)
        val book = shelf.find("тіні забутих предків", MediaKind.AUDIO)!!
        assertEquals(2, book.tracks.size)
        shelf.save(book, MediaPlace(1, 90_000))
        // Після перезапуску: місце, остання і пошук серед відомих без обходу.
        var scanned = false
        val again = MediaShelf(StorageScan({ scanned = true; listOf(root) }, MediaFiles::isMedia), json)
        assertEquals(MediaPlace(1, 90_000), again.place(book))
        assertEquals(book.key, again.last()?.key)
        assertEquals(book.key, again.find("тіні забутих предків")?.key)
        assertEquals(false, scanned)
    }
}
