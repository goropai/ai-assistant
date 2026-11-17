package com.speedy.assistant

import com.speedy.assistant.util.writeTextAtomic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun replacesWholeFileAndLeavesNoTemp() {
        val f = File(tmp.root, "sub/faces.json")
        f.writeTextAtomic("""{"a":1}""")
        f.writeTextAtomic("""{"b":"довший вміст, ніж був"}""")
        assertEquals("""{"b":"довший вміст, ніж був"}""", f.readText())
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun interruptedWriteKeepsOldFile() {
        // Обірваний попередній запис лишив тимчасовий файл — основний цілий і читається.
        val f = File(tmp.root, "profiles.json").apply { writeTextAtomic("""{"ok":true}""") }
        File(f.path + ".tmp").writeText("""{"обірв""")
        assertEquals("""{"ok":true}""", f.readText())
        f.writeTextAtomic("""{"ok":2}""")
        assertEquals("""{"ok":2}""", f.readText())
    }
}
