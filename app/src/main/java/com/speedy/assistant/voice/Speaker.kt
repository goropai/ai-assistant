package com.speedy.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Синтез мовлення через системний TTS. Підвищений pitch дає легкий "підлітковий"
 * тембр. Для кращої якості пізніше можна підключити хмарний TTS — інтерфейс speak()
 * лишиться тим самим.
 */
class Speaker(
    private val context: Context,
    private val pitch: Float,
    private val rate: Float,
    private val preferredVoice: String,
) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private lateinit var tts: TextToSpeech

    /** Синтезатори, які ще можна спробувати (null — системний за замовчуванням). */
    private val candidates = ArrayDeque<String?>().apply { add(null) }
    private var enginesListed = false

    init {
        tryNextEngine()
    }

    /**
     * Системний синтезатор може не знати української (на Android 7.1 Google TTS 3.x її не має,
     * а новіший туди не ставиться) — тоді пробуємо інші встановлені, напр. RHVoice з F-Droid.
     */
    private fun tryNextEngine() {
        val engine = candidates.removeFirstOrNull()
        if (engine == null && enginesListed) {
            Log.e(TAG, "Жоден синтезатор не знає української. Встанови RHVoice (F-Droid) і український голос у ньому.")
            ready.complete(false)
            return
        }
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS && configure()) {
                Log.i(TAG, "Синтезатор: ${tts.defaultEngine.takeIf { engine == null } ?: engine}")
                ready.complete(true)
                return@TextToSpeech
            }
            if (!enginesListed) {
                enginesListed = true
                candidates += tts.engines.map { it.name }.filter { it != tts.defaultEngine }
            }
            tts.shutdown()
            tryNextEngine()
        }, engine)
    }

    private fun configure(): Boolean {
        val locale = Locale("uk", "UA")
        val result = tts.setLanguage(locale)
        if (result < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "Українська недоступна в синтезаторі ($result)")
            return false
        }
        val voices = tts.voices.orEmpty().filter { it.locale.language == "uk" }
        Log.i(TAG, "Доступні голоси: ${voices.map { it.name }}")
        voices.firstOrNull { it.name == preferredVoice }?.let { tts.voice = it }
        ukrainianVoice = tts.voice

        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
        })
        return true
    }

    /** Промовляє текст і повертається, коли мовлення завершилось. */
    private var ukrainianVoice: android.speech.tts.Voice? = null

    /** Мова, якою синтезатор зараз налаштований (перемикаємо лише при зміні — книги говорять фрагментами). */
    private var current: Locale = UKRAINIAN

    /**
     * @param english — англійським голосом (уроки англійської).
     * @param locale — мовою книги (англійська, російська); немає такого голосу — українським.
     */
    suspend fun speak(text: String, english: Boolean = false, locale: Locale? = null) {
        if (text.isBlank() || !ready.await()) return
        val wanted = locale ?: if (english) Locale.US else UKRAINIAN
        if (!use(wanted)) use(UKRAINIAN)
        speakNow(text)
    }

    /** Чи є голос цієї мови в синтезаторі (інакше книгу читатиме український голос). */
    suspend fun supports(locale: Locale): Boolean =
        ready.await() && tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE

    private fun use(locale: Locale): Boolean {
        if (locale == current) return true
        val r = tts.setLanguage(locale)
        if (r < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "Мова $locale недоступна в синтезаторі ($r)")
            return false
        }
        if (locale == UKRAINIAN) ukrainianVoice?.let { tts.voice = it }
        current = locale
        return true
    }

    private suspend fun speakNow(text: String) {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        // Страховка, якщо TTS не надішле onDone: ~80 мс на символ + запас.
        withTimeoutOrNull(5_000L + text.length * 80L) { done.await() }
        pending.remove(id)
    }

    fun stop() {
        if (ready.isCompleted) tts.stop()
    }

    fun shutdown() {
        if (::tts.isInitialized) tts.shutdown()
    }

    private companion object {
        const val TAG = "Speaker"
        val UKRAINIAN = Locale("uk", "UA")
    }
}
