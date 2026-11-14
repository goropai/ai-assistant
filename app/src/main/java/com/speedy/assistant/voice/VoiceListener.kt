package com.speedy.assistant.voice

import android.content.Context
import android.content.Intent
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
 * Обмеження: системний розпізнавач може пікати при кожному старті та їсть трафік.
 * Наступний крок — офлайн-детектор імені (Vosk / Porcupine), а SpeechRecognizer
 * вмикати лише після "Спіді".
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
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private val restart = Runnable { listenNow() }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active) return
        active = true
        if (recognizer == null) createRecognizer()
        listenNow()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(restart)
        recognizer?.cancel()
    }

    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
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
            recognizer?.startListening(intent)
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
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.d(TAG, "heard: $phrases")
            // onPhrase може викликати stop() — тоді перезапуску не буде.
            if (phrases.isNotEmpty()) onPhrase(phrases)
            scheduleRestart(150)
        }

        override fun onError(error: Int) {
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

    private companion object {
        const val TAG = "VoiceListener"
    }
}
