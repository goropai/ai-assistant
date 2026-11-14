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
    context: Context,
    private val pitch: Float,
    private val rate: Float,
    private val preferredVoice: String,
) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS && configure())
        }
    }

    private fun configure(): Boolean {
        val locale = Locale("uk", "UA")
        val result = tts.setLanguage(locale)
        if (result < TextToSpeech.LANG_AVAILABLE) {
            Log.e(TAG, "Українська мова TTS недоступна ($result). Встанови голосові дані в налаштуваннях TTS.")
            return false
        }
        val voices = tts.voices.orEmpty().filter { it.locale.language == "uk" }
        Log.i(TAG, "Доступні голоси: ${voices.map { it.name }}")
        voices.firstOrNull { it.name == preferredVoice }?.let { tts.voice = it }

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
    suspend fun speak(text: String) {
        if (text.isBlank() || !ready.await()) return
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
    }
}
