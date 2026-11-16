package com.speedy.assistant.vision

import com.speedy.assistant.LocalCommand
import com.speedy.assistant.LocalCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FacesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vec(vararg v: Float) = floatArrayOf(*v)
    private val adult = vec(1f, 0f, 0f)
    private val adultAgain = vec(0.9f, 0.1f, 0f)
    private val child = vec(0f, 1f, 0f)

    @Test
    fun rememberMatchAndPersist() {
        val file = File(tmp.root, "faces.json")
        FaceBook(file).remember("Тарас", listOf(adult))
        val book = FaceBook(file) // з диска
        assertEquals("Тарас", book.match(adultAgain)?.first)
        assertNull(book.match(child))
        assertTrue(book.forget("Тарас"))
        assertNull(FaceBook(file).match(adult))
    }

    @Test
    fun recognizedAfterTwoFramesThenCooldown() {
        val book = FaceBook(null).apply { remember("Тарас", listOf(adult)) }
        val t = FaceIdTracker(book)
        val face = FaceObservation(adultAgain, 0.1f)
        assertTrue(t.update(0, listOf(face)).isEmpty())
        assertEquals(listOf(PresenceEvent.Recognized("Тарас")), t.update(1500, listOf(face)))
        assertTrue(t.update(3000, listOf(face)).isEmpty())
        assertEquals(listOf("Тарас"), t.presentNames(3000))
    }

    @Test
    fun strangerAskedOnceAndCanBeIntroduced() {
        val people = People(FaceBook(null))
        val face = FaceObservation(child, 0.1f)
        people.update(0, listOf(face))
        assertEquals(listOf(PresenceEvent.Stranger), people.update(1500, listOf(face)))
        assertEquals(People.Introduction.Remembered, people.introduce("Валя", 2000))
        // Тепер це знайоме обличчя.
        people.update(3000, listOf(face))
        assertEquals(listOf(PresenceEvent.Recognized("Валя")), people.update(4500, listOf(face)))
    }

    @Test
    fun tinyBackgroundFaceIsNotAsked() {
        val people = People(FaceBook(null))
        val far = FaceObservation(child, 0.01f)
        people.update(0, listOf(far))
        assertTrue(people.update(1500, listOf(far)).isEmpty())
        assertEquals(People.Introduction.NoFace, people.introduce("Хтось", 2000))
    }

    @Test
    fun jokeNameCanBeCorrected() {
        val file = File(tmp.root, "faces.json")
        val people = People(FaceBook(file))
        val face = FaceObservation(child, 0.1f)
        people.update(0, listOf(face))
        people.update(1500, listOf(face))
        assertEquals(People.Introduction.Remembered, people.introduce("Бетмен", 2000))
        people.update(3000, listOf(face))
        people.update(4500, listOf(face))
        assertEquals(listOf("Бетмен"), people.presentNames(4500))

        assertTrue(people.rename("Бетмен", "Валя", 5000))
        assertEquals(listOf("Валя"), people.presentNames(5000))
        // Те саме обличчя тепер — Валя, і після перезапуску теж; повторно не вітаємо.
        assertTrue(people.update(6000, listOf(face)).isEmpty())
        assertEquals("Валя", FaceBook(file).match(child)?.first)
        assertNull(FaceBook(file).match(child)?.takeIf { it.first == "Бетмен" })
    }

    @Test
    fun misrecognizedPersonKeepsOtherFace() {
        val book = FaceBook(null).apply { remember("Костя", listOf(adult)) }
        val people = People(book)
        // Ніну (схожа, але не та сама) сплутали з Костею.
        val other = vec(0.75f, 0.66f, 0f)
        val face = FaceObservation(other, 0.1f)
        people.update(0, listOf(face))
        assertEquals(listOf(PresenceEvent.Recognized("Костя")), people.update(1500, listOf(face)))
        assertTrue(people.reassign("Ніна", 2000))
        assertEquals("Ніна", book.match(other)?.first)
        assertEquals("Костя", book.match(adult)?.first) // Костя лишився собою
    }

    @Test
    fun jokeNameDisappearsAfterCorrection() {
        val book = FaceBook(null).apply { remember("Бетмен", listOf(adult)) }
        val people = People(book)
        val face = FaceObservation(adultAgain, 0.1f)
        people.update(0, listOf(face))
        people.update(1500, listOf(face))
        assertTrue(people.reassign("Тарас", 2000))
        assertEquals("Тарас", book.match(adult)?.first)
        assertTrue("Бетмен" !in book.names)
    }

    @Test
    fun newFaceCheckedAgainstAllKnown() {
        val book = FaceBook(null).apply { remember("Тарас", listOf(adult)) }
        val people = People(book)
        // Тарас під іншим кутом/світлом: схожість 0.55 — нижче порогу впізнавання (0.6), тож він
        // "незнайомець", але перевірка перед записом (0.5) знаходить Тараса — нове ім'я не створюється.
        val almostOleg = vec(0.55f, 0.835f, 0f)
        people.update(0, listOf(FaceObservation(almostOleg, 0.1f)))
        people.update(1500, listOf(FaceObservation(almostOleg, 0.1f)))
        people.update(3000, listOf(FaceObservation(almostOleg, 0.1f)))
        val result = people.introduce("Каву", 3500)
        assertEquals(People.Introduction.LooksLike("Тарас"), result)
        assertTrue("Каву" !in book.names)
    }

    @Test
    fun blurryInconsistentFramesNotRemembered() {
        val people = People(FaceBook(null))
        // Кадри, що не схожі між собою (рух, розмиття) — не один образ.
        people.update(0, listOf(FaceObservation(vec(1f, 0f, 0f), 0.1f)))
        people.update(1500, listOf(FaceObservation(vec(0f, 1f, 0f), 0.1f)))
        people.update(3000, listOf(FaceObservation(vec(0f, 0f, 1f), 0.1f)))
        assertEquals(People.Introduction.Unclear, people.introduce("Хтось", 3500))
    }

    @Test
    fun speakerIsTheClosestFace() {
        val people = People(FaceBook(null).apply { remember("Тарас", listOf(adult)) })
        // Тарас позаду (мале обличчя), незнайома дитина поруч (велике) — говорить не Тарас.
        people.update(0, listOf(FaceObservation(adult, 0.03f), FaceObservation(child, 0.2f)))
        assertEquals(listOf("Тарас"), people.presentNames(0))
        assertNull(people.speaker(0))
        // Тарас підійшов ближче — тепер він співрозмовник.
        people.update(1500, listOf(FaceObservation(adult, 0.25f), FaceObservation(child, 0.05f)))
        assertEquals("Тарас", people.speaker(1500))
    }

    @Test
    fun childFaceNeverMatchesAdultProfile() {
        // Відбиток дитини дуже схожий на татів (родинна схожість), але очі — по-дитячому низько.
        val people = People(FaceBook(null).apply { remember("Тарас", listOf(adult)) }, ageOf = { if (it == "Тарас") "ADULT" else "UNKNOWN" })
        val daughter = FaceObservation(adultAgain, 0.2f, eyeLevel = 0.50f)
        people.update(0, listOf(daughter))
        assertEquals(listOf(PresenceEvent.Stranger), people.update(1500, listOf(daughter)))
        assertTrue(people.presentNames(1500).isEmpty())
        // Сам Тарас (доросла ознака) — впізнається.
        val dad = FaceObservation(adultAgain, 0.2f, eyeLevel = 0.41f)
        people.update(3000, listOf(dad))
        assertEquals(listOf("Тарас"), people.presentNames(3000))
    }

        @Test
    fun namesFromPhrases() {
        assertEquals(LocalCommand.INTRODUCE, LocalCommands.match("мене звати Тарас"))
        assertEquals("Тарас", LocalCommands.parseName("Мене звати Тарас"))
        assertEquals("Тарас", LocalCommands.parseName("запам'ятай мене, я Тарас"))
        assertEquals("Валя", LocalCommands.parseName("Валя", answer = true))
        assertEquals("Валя", LocalCommands.parseName("я Валя", answer = true))
        assertNull(LocalCommands.parseName("не скажу", answer = true))
        assertNull(LocalCommands.parseName("спіть", answer = true)) // офлайн-слух так чує "Спіді"
        assertNull(LocalCommands.parseName("розкажи мені щось цікаве про космос", answer = true))
        assertEquals("Тарас", LocalCommands.parseName("насправді мене звати Тарас"))
        assertEquals("Тарас", LocalCommands.parseName("називай мене Тарас"))
        assertEquals("Тарас", LocalCommands.parseName("моє справжнє ім'я Тарас"))
        assertEquals("Бетмен" to "Тарас", LocalCommands.parseCorrection("я не Бетмен, я Тарас"))
        assertEquals("Костя" to "Ніна", LocalCommands.parseCorrection("Це не Костя, це Ніна"))
        assertEquals("Костя" to "Ніна", LocalCommands.parseCorrection("мене звати не Костя а Ніна"))
        assertEquals(LocalCommand.CORRECT_NAME, LocalCommands.match("це не Костя це Ніна"))
        assertEquals(LocalCommand.FORGET_NAME, LocalCommands.match("забудь Каву"))
        assertEquals("каву", LocalCommands.parseForgetName("Спіді забудь Каву".removePrefix("Спіді ")))
        assertEquals(LocalCommand.FORGET_ME, LocalCommands.match("забудь мене"))
        assertEquals(LocalCommand.LIST_PEOPLE, LocalCommands.match("кого ти знаєш?"))
        val people = People(FaceBook(null).apply { remember("Костя", listOf(adult)); remember("Каву", listOf(child)) })
        assertEquals("Костя", people.findName("костю"))
        assertEquals("Каву", people.findName("каву"))
        val people2 = People(FaceBook(null).apply { remember("Ніна", listOf(adult)); remember("Микита", listOf(child)) })
        assertEquals("Ніна", people2.findName("Ніну"))
        assertEquals("Микита", people2.findName("Мікіта"))
        // Імена-звертання до Спіді прибираються самі.
        val people3 = People(FaceBook(null).apply { remember("Спіть", listOf(adult)); remember("Тарас", listOf(child)) })
        assertEquals(listOf("Тарас"), people3.names())
        // Звичайні фрази з "не" — не виправлення імені.
        assertEquals(null, LocalCommands.parseCorrection("ні котики не п'ють каву"))
        assertEquals(null, LocalCommands.parseCorrection("я не в червоному"))
        assertEquals(null, LocalCommands.parseCorrection("але я не в червоному ти помилився"))
        assertEquals("Костя" to "Ніна", LocalCommands.parseCorrection("це не Костя а це Ніна"))
        assertEquals(LocalCommand.INTRODUCE, LocalCommands.match("називай мене Тарас"))
        assertEquals(LocalCommand.WHO_AM_I, LocalCommands.match("Як мене звати?"))
        assertEquals(LocalCommand.FORGET_ME, LocalCommands.match("забудь мене"))
    }
}
