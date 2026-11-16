package com.speedy.assistant.vision

import com.speedy.assistant.LocalCommand
import com.speedy.assistant.LocalCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PetsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Гістограма з 16 кошиків: уся маса в кількох. */
    private fun sig(vararg bins: Pair<Int, Float>) = FloatArray(16).apply { bins.forEach { (i, v) -> this[i] = v } }
    private val ginger = sig(1 to 0.7f, 2 to 0.2f, 14 to 0.1f)      // рудий
    private val gingerDim = sig(1 to 0.5f, 2 to 0.3f, 13 to 0.2f)   // той самий при іншому світлі
    private val black = sig(12 to 0.8f, 13 to 0.2f)                  // чорний

    @Test
    fun namedCatIsRecognizedAndPersists() {
        val file = File(tmp.root, "pets.json")
        val pets = Pets(file)
        assertNull(pets.observe(Kind.CAT, ginger, now = 0))
        assertTrue(pets.name("Мурчик", now = 1000))
        assertEquals("Мурчик", Pets(file).observe(Kind.CAT, gingerDim, now = 0))
    }

    @Test
    fun cannotNameWithoutPetInFrame() {
        val pets = Pets(null)
        assertFalse(pets.name("Мурчик", now = 0))
        pets.observe(Kind.CAT, ginger, now = 0)
        assertFalse(pets.name("Мурчик", now = Pets.RECENT_MS + 1))
    }

    @Test
    fun twoCatsOfDifferentColour() {
        val pets = Pets(null)
        pets.observe(Kind.CAT, ginger, 0); pets.name("Рудик", 0)
        pets.observe(Kind.CAT, black, 0); pets.name("Чорниш", 0)
        assertEquals("Рудик", pets.identify(Kind.CAT, gingerDim))
        assertEquals("Чорниш", pets.identify(Kind.CAT, black))
        // Собак не знаємо, але чорну "собаку" з кольором Чорниша модель, певно, сплутала з ним.
        assertEquals("Чорниш", pets.identify(Kind.DOG, black))
    }

    @Test
    fun renamingSamePet() {
        val pets = Pets(null)
        pets.observe(Kind.CAT, ginger, 0); pets.name("Барсик", 0)
        pets.observe(Kind.CAT, gingerDim, 0); pets.name("Мурчик", 0)
        assertEquals("Мурчик", pets.identify(Kind.CAT, ginger))
    }

    @Test
    fun catMistakenForDogStillRecognized() {
        val pets = Pets(null)
        pets.observe(Kind.CAT, ginger, 0); pets.name("Барсик", 0)
        assertEquals("Барсик", pets.identify(Kind.DOG, ginger))
        assertNull(pets.identify(Kind.DOG, black))
    }

    @Test
    fun petNamePhrases() {
        assertEquals(LocalCommand.NAME_PET, LocalCommands.match("кота звати Мурчик"))
        assertEquals("Мурчик", LocalCommands.parsePetName("Кота звати Мурчик"))
        assertEquals("Мурчик", LocalCommands.parsePetName("його звати Мурчик"))
        assertEquals("Мурчик", LocalCommands.parsePetName("це наш кіт Мурчик"))
        assertEquals("Лайка", LocalCommands.parsePetName("собаку звати Лайка"))
        // Людське знайомство — не тварина.
        assertEquals(LocalCommand.INTRODUCE, LocalCommands.match("мене звати Тарас"))
    }
}
