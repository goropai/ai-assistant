package com.speedy.assistant

import com.speedy.assistant.english.Answers
import com.speedy.assistant.english.Curriculum
import com.speedy.assistant.english.EnglishLesson
import com.speedy.assistant.english.EnglishProgress
import com.speedy.assistant.english.Heard
import com.speedy.assistant.english.LessonIO
import com.speedy.assistant.english.Level
import com.speedy.assistant.english.Pronounce
import com.speedy.assistant.english.Vocabulary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnglishTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = EnglishProgress.DAY_MS

    private val vocab = Vocabulary.parse(File("src/main/assets/english/words.json").readText())
    private val curriculum = Curriculum.parse(File("src/main/assets/english/lessons.json").readText())

    /** Учень: повторює фрази, відповідає на питання зразком, знає слова (або каже "не знаю"). */
    /** [knowsUpTo] — найвищий рівень слів, які учень знає на перевірці рівня. */
    private inner class FakeStudent(
        val good: Boolean,
        val knowsUpTo: Level = Level.B1,
        val confidence: Float = 0.9f,
    ) : LessonIO {
        val said = mutableListOf<String>()
        private var lastEnglish = ""
        private var lastUk = ""
        override suspend fun say(uk: String) { said += uk; lastUk = uk }
        override suspend fun sayEnglish(en: String) { said += "EN:$en"; lastEnglish = en; lastUk = "" }
        override suspend fun listen(english: Boolean): Heard? {
            if (!good) return Heard(listOf("не знаю"))
            if (!english) return Heard(listOf(vocab.find(lastEnglish)?.uk?.first() ?: ""))
            // Питання діалогу — відповідаємо зразком; "Як англійською «X»?" — слово; інакше повторюємо почуте.
            val question = Level.entries.flatMap { curriculum.units(it) }.flatMap { it.dialogue }.firstOrNull { it.q == lastEnglish }
            val uk = Regex("«(.+)»").find(lastUk)?.groupValues?.get(1)
            val word = uk?.let { u -> Level.entries.flatMap { vocab.words(it) }.firstOrNull { u in it.uk } }
            if (word != null && word.level.ordinal > knowsUpTo.ordinal) return Heard(listOf("не знаю"))
            val text = question?.sample ?: word?.en ?: lastEnglish
            return Heard(listOf(text), confidence)
        }
        override suspend fun cheer() {}
        override suspend fun oops() {}
    }

    private fun lesson(student: FakeStudent, progress: EnglishProgress, name: String, at: Long = 0L, child: Boolean? = null) =
        EnglishLesson(vocab, curriculum, progress, student, name, now = { at }, child = child)

    @Test
    fun contentLoaded() {
        Level.entries.forEach {
            assertTrue("$it words", vocab.words(it).size >= 35)
            assertEquals("$it units", 5, curriculum.units(it).size)
        }
        assertEquals(
            listOf(false to "Кажемо", true to "I like", false to "— мені подобається."),
            Curriculum.segments("Кажемо {I like} — мені подобається."),
        )
    }

    @Test
    fun answersAreLenient() {
        assertTrue(Answers.matches(listOf("Cat."), listOf("cat")))
        assertTrue(Answers.matches(listOf("kitchin"), listOf("kitchen")))
        assertTrue(Answers.matches(listOf("look forward to"), listOf("to look forward to")))
        assertTrue(Answers.matches(listOf("Кішка"), listOf("кіт", "кішка")))
        assertTrue(!Answers.matches(listOf("dog"), listOf("cat")))
        // Скорочення = повна форма.
        assertEquals("she is my friend", Answers.clean("She's my friend."))
        assertEquals("i am not tired", Answers.clean("I'm not tired"))
        assertTrue(Pronounce.score(Heard(listOf("she's my friend"), 0.95f), "She is my friend.").score >= 85)
    }

    @Test
    fun pronunciationByEar() {
        val clear = Pronounce.score(Heard(listOf("I drink coffee every morning"), 0.95f), "I drink coffee every morning.")
        assertTrue(clear.score >= 85)
        val unsure = Pronounce.score(Heard(listOf("I drink copy every morning"), 0.6f), "I drink coffee every morning.")
        assertTrue(unsure.score in 65..84)
        assertEquals("coffee", unsure.weakWord)
        val altOnly = Pronounce.score(Heard(listOf("ice ring", "I drink coffee every morning"), 0.3f), "I drink coffee every morning.")
        assertTrue(altOnly.score in 40..64)
        assertTrue(Pronounce.score(Heard(listOf("hello there")), "I drink coffee every morning.").score < 40)
        // Артикль розпізнавач не пише — "green apple" на "A green apple." зараховується.
        assertTrue(Pronounce.score(Heard(listOf("green apple"), 0.8f), "A green apple.").score >= 85)
    }

    @Test
    fun spacedRepetition() {
        val p = EnglishProgress(null)
        p.answer("Тарас", "cat", true, 0)
        assertTrue(p.due("Тарас", day / 2, 10).isEmpty())
        assertEquals(listOf("cat"), p.due("Тарас", day, 10))
        p.answer("Тарас", "cat", false, day * 3)
        assertEquals(listOf("cat"), p.due("Тарас", day * 4, 10))
    }

    @Test
    fun goodStudentPassesUnitAndProgressPersists() = runBlocking {
        val file = File(tmp.root, "english.json")
        val progress = EnglishProgress(file)
        val student = FakeStudent(good = true, knowsUpTo = Level.A2)
        lesson(student, progress, "Тарас", child = false).run()
        val loaded = EnglishProgress(file)
        assertEquals(Level.A2, loaded.learner("Тарас").level) // перевірка: знає до A2 включно
        assertEquals(1, loaded.unitIndex("Тарас", Level.A2)) // перша тема зарахована
        val l = loaded.learner("Тарас").lessons.single()
        assertEquals(l.total, l.score)
        assertTrue(l.pronunciation!! >= 85)
        assertTrue(student.said.contains("Урок 1: Минулий час — Past Simple."))
        assertTrue(student.said.last().contains("Вимова сьогодні"))
        // Наступного разу — друга тема.
        val next = FakeStudent(good = true)
        lesson(next, loaded, "Тарас", day).run()
        assertTrue(next.said.contains("Урок 2: Плани — going to."))
    }

    @Test
    fun placementTest() = runBlocking {
        val p = EnglishProgress(null)
        lesson(FakeStudent(good = true, knowsUpTo = Level.A1), p, "Ніна", child = false).run()
        assertEquals(Level.A1, p.learner("Ніна").level)
        lesson(FakeStudent(good = true), p, "Тарас", child = false).run()
        assertEquals(Level.B1, p.learner("Тарас").level)
        // Вік невідомий і не знає навіть дитячих слів — дитячий рівень.
        lesson(FakeStudent(good = false), p, "Гість", child = null).run()
        assertEquals(Level.KIDS, p.learner("Гість").level)
    }

    @Test
    fun weakStudentRepeatsUnit() = runBlocking {
        val progress = EnglishProgress(null).apply { setLevel("Ніна", Level.A1) }
        lesson(FakeStudent(good = false), progress, "Ніна").run()
        assertEquals(0, progress.unitIndex("Ніна", Level.A1))
    }

    @Test
    fun childGetsKidsTopicAndOwnProgress() = runBlocking {
        val progress = EnglishProgress(null)
        // Дитина за обличчям — дитячі уроки, навіть якщо слова складніших рівнів "знає".
        val kid = FakeStudent(good = true)
        lesson(kid, progress, "Даринка", child = true).run()
        assertEquals(Level.KIDS, progress.learner("Даринка").level)
        assertTrue(kid.said.contains("Урок 1: Привітання."))
        assertNull(progress.learner("Тарас").level)
    }

    @Test
    fun stopWordEndsLesson() = runBlocking {
        val progress = EnglishProgress(null).apply { setLevel("Ніна", Level.A1) }
        val io = object : LessonIO {
            val said = mutableListOf<String>()
            override suspend fun say(uk: String) { said += uk }
            override suspend fun sayEnglish(en: String) { said += en }
            override suspend fun listen(english: Boolean) = Heard(listOf("досить"))
            override suspend fun cheer() {}
            override suspend fun oops() {}
        }
        EnglishLesson(vocab, curriculum, progress, io, "Ніна", now = { 0L }).run()
        // Після "досить" — ні діалогу, ні слів, тема не зарахована.
        assertTrue(io.said.none { it.startsWith("Тепер поговоримо") || it.startsWith("І трохи слів") })
        assertEquals(0, progress.unitIndex("Ніна", Level.A1))
    }

    @Test
    fun newTopicGeneratedOnceAndCached() = runBlocking {
        val progress = EnglishProgress(null).apply { setLevel("Тарас", Level.A1) }
        repeat(5) { progress.completeUnit("Тарас", Level.A1, it) } // усі вбудовані теми пройдено
        val store = com.speedy.assistant.english.UnitStore(File(tmp.root, "units.json"))
        var calls = 0
        val modelAnswer = """{"title":"Погода","theory":"Про погоду: {It is sunny}.",
            "phrases":[{"en":"It is sunny.","uk":"Сонячно."},{"en":"It is cold.","uk":"Холодно."},{"en":"I like rain.","uk":"Я люблю дощ."}],
            "dialogue":[{"q":"Is it cold today?","hint":"Сьогодні холодно?","expect":["yes","no","it is"],"sample":"Yes, it is cold."},
                        {"q":"Do you like snow?","hint":"Тобі подобається сніг?","expect":["yes","no","i like"],"sample":"Yes, I like snow."}]}"""
        val gen: suspend (Level, List<String>) -> Pair<org.json.JSONObject, com.speedy.assistant.english.Unit>? = { level, done ->
            calls++
            assertTrue(done.contains("Питання")) // модель знає пройдені теми
            com.speedy.assistant.english.UnitGenerator.parse(modelAnswer, level)
        }
        val s1 = FakeStudent(good = true)
        EnglishLesson(vocab, curriculum, progress, s1, "Тарас", now = { 0L }, extra = store, generate = gen).run()
        assertTrue(s1.said.contains("Урок 6: Погода."))
        // Ніна на тому ж рівні — тема з кешу, без моделі.
        val p2 = EnglishProgress(null).apply { setLevel("Ніна", Level.A1); repeat(5) { completeUnit("Ніна", Level.A1, it) } }
        val s2 = FakeStudent(good = true)
        EnglishLesson(vocab, curriculum, p2, s2, "Ніна", now = { 0L }, extra = com.speedy.assistant.english.UnitStore(File(tmp.root, "units.json")), generate = gen).run()
        assertTrue(s2.said.contains("Урок 6: Погода."))
        assertEquals(1, calls)
        assertNull(com.speedy.assistant.english.UnitGenerator.parse("{\"title\":\"x\"}", Level.A1)) // зламаний шаблон
    }

        @Test
    fun levelAdvice() {
        val p = EnglishProgress(null).apply { setLevel("Тарас", Level.A1) }
        repeat(3) { p.finishLesson("Тарас", EnglishProgress.Lesson(it * day, 10, 10, 4)) }
        assertEquals(Level.A2, p.levelAdvice("Тарас"))
        assertEquals(3, p.streak("Тарас", 2 * day))
        assertEquals(Level.KIDS, Level.fromSpoken("це для дитини"))
    }
}
