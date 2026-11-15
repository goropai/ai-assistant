package com.speedy.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Безперервне прослуховування через системний SpeechRecognizer (на Android 7.1 —
 * сервіс Google, потрібен інтернет і встановлений застосунок Google).
 * Після кожної фрази розпізнавач перезапускається.
 *
 * Розпізнавач Google пікає (raw/open, raw/failure) при кожному перезапуску — тобто кожні
 * кілька секунд тиші. На цьому телефоні сигнали йдуть потоком MUSIC, тож поки слухаємо,
 * цей потік приглушено; Спіді говорить лише тоді, коли не слухає ([stop]), і звук повертається.
 * Спершу просимо офлайн-розпізнавання (звук не йде в хмару — економія трафіку). Якщо
 * офлайн-пакета української немає, розпізнавач одразу падає з ERROR_CLIENT — тоді до кінця
 * сеансу слухаємо онлайн.
 *
 * Усі методи викликати з головного потоку.
 */
class VoiceListener(
    private val context: Context,
    private val language: String = "uk-UA",
    private val onPhrase: (List<String>) -> Unit,
    private val onFatalError: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var muted = false
    private var preferOffline = true
    private var offlineFailures = 0
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private val restart = Runnable { listenNow() }
    private val unmute = Runnable { muteBeeps(false) }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active) return
        active = true
        handler.removeCallbacks(unmute)
        muteBeeps(true)
        if (recognizer == null) createRecognizer()
        listenNow()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(restart)
        recognizer?.cancel()
        // Після cancel() Google ще встигає пікнути "failure" — повертаємо звук трохи згодом.
        handler.postDelayed(unmute, UNMUTE_DELAY_MS)
    }

    fun release() {
        stop()
        handler.removeCallbacks(unmute)
        muteBeeps(false)
        recognizer?.destroy()
        recognizer = null
    }

    private fun muteBeeps(mute: Boolean) {
        if (mute == muted) return
        muted = mute
        Log.d(TAG, if (mute) "beeps muted" else "beeps unmuted")
        try {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "mute failed (режим «Не турбувати»?)", e)
        }
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listenNow() {
        if (!active) return
        try {
            recognizer?.startListening(intent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            createRecognizer()
            scheduleRestart(1000)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!active) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            offlineFailures = 0
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.d(TAG, "heard: $phrases")
            // onPhrase може викликати stop() — тоді перезапуску не буде.
            if (phrases.isNotEmpty()) onPhrase(phrases)
            scheduleRestart(150)
        }

        override fun onError(error: Int) {
            if (preferOffline) {
                offlineFailures = if (error == SpeechRecognizer.ERROR_CLIENT) offlineFailures + 1 else 0
                if (offlineFailures >= 2) {
                    Log.i(TAG, "офлайн-розпізнавання недоступне — слухаю онлайн")
                    preferOffline = false
                }
            }
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    active = false
                    onFatalError("Немає дозволу на мікрофон")
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    createRecognizer()
                    scheduleRestart(500)
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> scheduleRestart(3000)
                // NO_MATCH / SPEECH_TIMEOUT — просто тиша, слухаємо далі.
                else -> scheduleRestart(150)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "VoiceListener"
        /** Скільки чекати після [stop], перш ніж говорити: звук ще приглушено. */
        const val UNMUTE_DELAY_MS = 350L
    }
}
