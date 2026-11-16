package com.speedy.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Слух Спіді у два шари:
 * 1. "Вартовий" ([SoundGate]) — сам слухає мікрофон (AudioRecord), без інтернету й без звуків;
 * 2. коли хтось заговорив — сеанс системного SpeechRecognizer (Google, онлайн), який триває,
 *    поки в кімнаті говорять, і ще [SESSION_MS] після останньої фрази.
 *
 * Раніше розпізнавач працював безперервно: слав звук у хмару навіть у тиші й після кожної
 * паузи перезапускався з сигналом ("булькотіння"). Тепер у тиші — ні трафіку, ні сигналів.
 * Сигнали Google під час сеансу глушимо: запам'ятовуємо гучність потоку [BEEP_STREAMS],
 * ставимо 0 і відновлюємо після (на диску теж — на випадок падіння застосунку).
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
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private var muted = false
    private var preferOffline = true
    private var offlineFailures = 0
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var inSession = false
    private var sessionUntil = 0L
    @Volatile private var guard: Thread? = null

    init {
        // Попередній запуск міг завершитись, поки звук був приглушений.
        restoreVolumes()
    }

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
    private val resumeGuard = Runnable { startGuard() }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** @param listenNow одразу сеанс розпізнавання (Спіді щойно спитала і чекає відповіді). */
    fun start(listenNow: Boolean = false) {
        if (active) return
        active = true
        if (listenNow) startSession() else startGuard()
    }

    fun stop() {
        active = false
        stopGuard()
        handler.removeCallbacks(restart)
        handler.removeCallbacks(resumeGuard)
        if (inSession) {
            inSession = false
            recognizer?.cancel()
            // Після cancel() Google ще встигає пікнути "failure" — повертаємо звук трохи згодом.
            handler.postDelayed(unmute, UNMUTE_DELAY_MS)
        }
    }

    fun release() {
        stop()
        handler.removeCallbacks(unmute)
        muteBeeps(false)
        recognizer?.destroy()
        recognizer = null
    }

    // --- вартовий ---

    private fun startGuard() {
        if (!active || inSession || guard != null) return
        val thread = Thread({ guardLoop() }, "SoundGate")
        guard = thread
        thread.start()
    }

    private fun stopGuard() {
        val thread = guard ?: return
        guard = null
        thread.interrupt()
        thread.join(500) // мікрофон має звільнитися до старту розпізнавача
    }

    private fun guardLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 4))
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            // Мікрофон зайнятий — без вартового, слухаємо як раніше.
            handler.post { if (guard === Thread.currentThread() || guard == null) { guard = null; startSession() } }
            return
        }
        val gate = SoundGate()
        val buffer = ShortArray(FRAME)
        try {
            record.startRecording()
            while (!Thread.currentThread().isInterrupted && guard === Thread.currentThread()) {
                val n = record.read(buffer, 0, FRAME)
                if (n <= 0) continue
                if (gate.feed(SoundGate.levelDb(buffer, n))) {
                    handler.post { onVoiceDetected() }
                    break
                }
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }

    private fun onVoiceDetected() {
        if (!active || inSession) return
        guard?.let { if (it.isAlive) it.join(300) }
        guard = null
        Log.d(TAG, "голос — вмикаю розпізнавання")
        startSession()
    }

    // --- сеанс розпізнавання ---

    private fun startSession() {
        if (!active) return
        stopGuard()
        inSession = true
        sessionUntil = SystemClock.uptimeMillis() + SESSION_MS
        handler.removeCallbacks(unmute)
        muteBeeps(true)
        if (recognizer == null) createRecognizer()
        listenNow()
    }

    /** Сеанс завершено (тиша) — повертаємо звук і вартового. */
    private fun endSession() {
        inSession = false
        handler.postDelayed(unmute, UNMUTE_DELAY_MS)
        handler.postDelayed(resumeGuard, UNMUTE_DELAY_MS)
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listenNow() {
        if (!active || !inSession) return
        try {
            recognizer?.startListening(intent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            createRecognizer()
            scheduleRestart(1000)
        }
    }

    /** Наступна спроба в межах сеансу або кінець сеансу, якщо в кімнаті давно тихо. */
    private fun scheduleRestart(delayMs: Long) {
        if (!active || !inSession) return
        handler.removeCallbacks(restart)
        if (SystemClock.uptimeMillis() > sessionUntil) endSession()
        else handler.postDelayed(restart, delayMs)
    }

    private fun muteBeeps(mute: Boolean) {
        if (mute == muted) return
        muted = mute
        try {
            if (mute) {
                val editor = prefs.edit()
                for (stream in BEEP_STREAMS) {
                    val level = audio.getStreamVolume(stream)
                    if (level > 0) editor.putInt(key(stream), level)
                }
                editor.apply()
                for (stream in BEEP_STREAMS) audio.setStreamVolume(stream, 0, 0)
            } else {
                restoreVolumes()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "mute failed (режим «Не турбувати»?)", e)
        }
    }

    private fun restoreVolumes() {
        try {
            val editor = prefs.edit()
            // Ключ попередньої версії (лише MUSIC).
            prefs.getInt(LEGACY_KEY, -1).takeIf { it > 0 }?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
            editor.remove(LEGACY_KEY)
            for (stream in BEEP_STREAMS) {
                val level = prefs.getInt(key(stream), -1)
                if (level > 0) audio.setStreamVolume(stream, level, 0)
                editor.remove(key(stream))
            }
            editor.apply()
        } catch (e: SecurityException) {
            Log.w(TAG, "restore volume failed", e)
        }
    }

    private fun key(stream: Int) = "volume_$stream"

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            offlineFailures = 0
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.d(TAG, "heard: $phrases")
            if (phrases.isNotEmpty()) sessionUntil = SystemClock.uptimeMillis() + SESSION_MS
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
                // NO_MATCH / SPEECH_TIMEOUT — тиша; якщо давно тихо, сеанс закінчиться.
                else -> scheduleRestart(150)
            }
        }

        override fun onBeginningOfSpeech() {
            sessionUntil = SystemClock.uptimeMillis() + SESSION_MS
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "VoiceListener"
        private const val LEGACY_KEY = "music_volume"
        private const val RATE = 16000
        private const val FRAME = RATE / 50 // 20 мс
        /** Скільки слухати після останньої почутої мови, перш ніж повернутися до вартового. */
        private const val SESSION_MS = 8_000L
        /**
         * Потік, яким розпізнавач Google подає сигнали (raw/open, failure, no_input): на Galaxy J5
         * доріжка застосунку Google має тип 1 — STREAM_SYSTEM (dumpsys media.audio_flinger).
         */
        private val BEEP_STREAMS = intArrayOf(AudioManager.STREAM_SYSTEM)
        /** Скільки чекати після [stop], перш ніж говорити: звук ще приглушено. */
        const val UNMUTE_DELAY_MS = 350L
    }
}
