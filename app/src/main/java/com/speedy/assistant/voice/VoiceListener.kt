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
import com.speedy.assistant.LocalCommands
import org.json.JSONObject

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
    /** Мова розпізнавання (на уроці англійської — "en-US"). */
    var language: String = "uk-UA",
    private val onPhrase: (List<String>) -> Unit,
    private val onFatalError: (String) -> Unit,
    /** Хтось заговорив — можна заздалегідь відкрити з'єднання з "мозком". */
    private val onVoice: () -> Unit = {},
    /** Офлайн-слух (Vosk) — запасний: лише коли [useOffline] (немає інтернету). */
    private val offline: OfflineEar? = null,
    /**
     * Чи слухати офлайн. На Galaxy J5 Vosk розпізнає гірше й повільніше за Google
     * (4–10 с на фразу, губить слова), тож він — лише на випадок, коли інтернету немає.
     */
    private val useOffline: () -> Boolean = { true },
) {
    private val handler = Handler(Looper.getMainLooper())

    /** Висота голосу останньої фрази (Гц, з буфера вартового) і коли заміряно. */
    @Volatile var lastPitchHz: Float? = null
        private set
    @Volatile var lastPitchAt: Long = 0
        private set

    /** Упевненість останнього розпізнавання (0..1) або null — розпізнавач не дав. */
    var lastConfidence: Float? = null
        private set
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

    /** У журнал — не PIN: поки відкрите віконце PIN, почуте не показуємо. */
    private fun shown(phrases: List<String>): Any =
        if (com.speedy.assistant.settings.PinDialog.awaitingVoice) "[приховано: вводять PIN]" else phrases

    /** Скільки звуку фрази віддав Google (onBufferReceived) — у журнал; на J5 може й не віддавати. */
    private var googleBytes = 0

    /**
     * Звук поточної фрази — для відбитка голосу (хто говорить): початок, який чув вартовий, звук від
     * Google (якщо віддає) або вся фраза в офлайн-режимі. Мікрофон під час розпізнавання Google
     * належить йому, тож часто є лише перша секунда.
     */
    private val audioLock = Any()
    private val pending = ArrayList<ShortArray>()

    /** Звук останньої почутої фрази (null — не було) і коли. Читати в onPhrase. */
    @Volatile var lastUtterance: ShortArray? = null; private set
    @Volatile var lastUtteranceAt = 0L; private set

    private fun addAudio(chunk: ShortArray) = synchronized(audioLock) { if (pending.sumOf { it.size } < MAX_AUDIO) pending += chunk }

    private fun resetAudio() = synchronized(audioLock) { pending.clear() }

    /** Фраза почута — звук, що зібрався, стає [lastUtterance]. */
    private fun finishAudio() = synchronized(audioLock) {
        val total = pending.sumOf { it.size }
        lastUtterance = if (total == 0) null else ShortArray(total).also { out ->
            var at = 0
            for (c in pending) { c.copyInto(out, at); at += c.size }
        }
        lastUtteranceAt = SystemClock.uptimeMillis()
        pending.clear()
    }

    /**
     * Чи грає зараз будильник / таймер (STREAM_ALARM) чи дзвінок (STREAM_RING). Публічного способу
     * на Android 7 немає — AudioSystem.isStreamActive через рефлексію (до Android 9 дозволено).
     */
    /** Дзвонить будильник / таймер / телефон (команди "вимкни будильник" — без "Спіді"). */
    val ringing: Boolean get() = otherSoundRinging()

    @Volatile private var ringingSeenAt = 0L

    /** Сигнал пульсує (між гудками потік на мить неактивний) — тож "дзвонить" ще кілька секунд після гудка. */
    private fun otherSoundRinging(): Boolean {
        val now = SystemClock.uptimeMillis()
        val active = try {
            val m = Class.forName("android.media.AudioSystem")
                .getMethod("isStreamActive", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            listOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_RING).any { m.invoke(null, it, 0) as Boolean }
        } catch (e: Exception) {
            false
        }
        if (active) ringingSeenAt = now
        return active || (ringingSeenAt > 0 && now - ringingSeenAt < RINGING_STICKY_MS)
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** @param listenNow одразу сеанс розпізнавання (Спіді щойно спитала і чекає відповіді). */
    fun start(listenNow: Boolean = false) {
        if (active) return
        active = true
        // Офлайн-слух бере звук із буфера вартового (з запасом 0.6 с) — окремий "одразу" не потрібен.
        if (listenNow && !offlineNow()) startSession() else startGuard()
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
        // Останні ~0.6 с звуку: коли вартовий спрацював, початок фрази вже минув — віддаємо його Vosk.
        val preroll = ArrayDeque<ShortArray>()
        try {
            record.startRecording()
            while (!Thread.currentThread().isInterrupted && guard === Thread.currentThread()) {
                val buffer = ShortArray(FRAME)
                val n = record.read(buffer, 0, FRAME)
                if (n <= 0) continue
                preroll.addLast(buffer)
                if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                if (!gate.feed(SoundGate.levelDb(buffer, n))) continue
                // Хтось заговорив: ще ~0.25 с мовлення (у буфері до спрацювання — переважно тиша),
                // і тон голосу локально — дитина чи дорослий.
                repeat(PITCH_EXTRA_FRAMES) {
                    val extra = ShortArray(FRAME)
                    if (record.read(extra, 0, FRAME) > 0) preroll.addLast(extra)
                }
                // Початок фрази — для відбитка голосу (Google далі звук не віддає).
                resetAudio()
                preroll.forEach { addAudio(it) }
                Pitch.ofSpeech(preroll.toList().takeLast(PREROLL_FRAMES))?.let {
                    lastPitchHz = it
                    lastPitchAt = SystemClock.uptimeMillis()
                    Log.d(TAG, "тон голосу: ${it.toInt()} Гц")
                }
                // Дзвонить будильник — офлайн (Vosk не забирає аудіофокус, будильник не замовкне),
                // щоб почути "вимкни будильник".
                val ear = offline?.takeIf { offlineNow() || (it.ready && otherSoundRinging()) }
                if (ear == null) {
                    handler.post { onVoiceDetected() }
                    break
                }
                handler.post { onVoice() }
                recognizeOffline(ear, record, preroll, alarm = !offlineNow())
                preroll.clear()
                gate.reset()
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }

    private fun offlineNow(): Boolean = offline?.ready == true && useOffline()

    /** Одна фраза через Vosk: спершу буфер, далі живий звук, поки модель не скаже "кінець фрази". */
    private fun recognizeOffline(ear: OfflineEar, record: AudioRecord, preroll: ArrayDeque<ShortArray>, alarm: Boolean) {
        val recognizer = ear.recognizer() ?: return
        // Під сигнал Vosk не чує кінця фрази: команда коротка, довше не слухаємо, а уламок
        // ("будник", "стоп") ловимо вже в проміжному результаті.
        val limit = if (alarm) ALARM_UTTERANCE_MS else MAX_UTTERANCE_MS
        fun alarmWords(): String? = if (!alarm) null
            else JSONObject(recognizer.partialResult).optString("partial").takeIf { LocalCommands.whileRinging(it) != null }
        val started = SystemClock.uptimeMillis()
        try {
            var final: String? = null
            for (frame in preroll) if (recognizer.acceptWaveForm(frame, frame.size)) { final = recognizer.result; break }
            while (final == null && guard === Thread.currentThread() && !Thread.currentThread().isInterrupted) {
                val buffer = ShortArray(FRAME)
                val n = record.read(buffer, 0, FRAME)
                if (n <= 0) continue
                addAudio(if (n == FRAME) buffer else buffer.copyOf(n)) // офлайн — уся фраза для відбитка голосу
                if (recognizer.acceptWaveForm(buffer, n)) final = recognizer.result
                else if (alarmWords() != null) final = JSONObject().put("text", alarmWords()).toString()
                else if (SystemClock.uptimeMillis() - started > limit) final = recognizer.finalResult
            }
            val phrases = final?.let { OfflineEar.phrases(it) }.orEmpty().filter { it != "[unk]" }
            Log.d(TAG, "офлайн почула (${SystemClock.uptimeMillis() - started} мс): ${shown(phrases)}")
            if (phrases.isNotEmpty()) { finishAudio(); handler.post { if (active) onPhrase(phrases) } } else resetAudio()
        } catch (e: Exception) {
            Log.w(TAG, "offline recognition failed", e)
        } finally {
            recognizer.close()
        }
    }

    private fun onVoiceDetected() {
        if (!active || inSession) return
        guard?.let { if (it.isAlive) it.join(300) }
        guard = null
        Log.d(TAG, "голос — вмикаю розпізнавання")
        startSession()
        onVoice()
    }

    // --- сеанс розпізнавання ---

    private fun startSession() {
        if (!active) return
        // Дзвонить будильник, таймер чи телефон: розпізнавач Google забирає ексклюзивний аудіофокус —
        // і годинник замовкає. Поки дзвонить — не вмикаємо, перевіримо знову за кілька секунд.
        if (otherSoundRinging()) {
            Log.d(TAG, "дзвонить будильник / таймер / телефон — розпізнавання не вмикаю")
            stopGuard()
            handler.removeCallbacks(resumeGuard)
            // Офлайн-слух готовий — вартовий одразу слухає далі через нього (не глухнемо на 4 с).
            handler.postDelayed(resumeGuard, if (offline?.ready == true) 0 else RINGING_RECHECK_MS)
            return
        }
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
            // Упевненість розпізнавача в першому варіанті — "вухо" для оцінки вимови.
            lastConfidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
            Log.d(TAG, "heard: ${shown(phrases)} (звук від Google: $googleBytes байт)")
            googleBytes = 0
            if (phrases.isNotEmpty()) finishAudio() else resetAudio()
            if (phrases.isNotEmpty()) sessionUntil = SystemClock.uptimeMillis() + SESSION_MS
            // onPhrase може викликати stop() — тоді перезапуску не буде.
            if (phrases.isNotEmpty()) onPhrase(phrases)
            scheduleRestart(150)
        }

        override fun onError(error: Int) {
            resetAudio() // звук невдалої спроби не має приклеїтись до наступної фрази
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
        override fun onBufferReceived(buffer: ByteArray?) {
            val b = buffer ?: return
            googleBytes += b.size
            // PCM 16 біт, little-endian (якщо Google взагалі віддає звук).
            addAudio(ShortArray(b.size / 2) { ((b[it * 2].toInt() and 0xff) or (b[it * 2 + 1].toInt() shl 8)).toShort() })
        }
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "VoiceListener"
        private const val LEGACY_KEY = "music_volume"
        private const val RATE = 16000
        private const val FRAME = RATE / 50 // 20 мс
        /** Запас звуку перед спрацюванням вартового: 30 кадрів по 20 мс. */
        private const val PREROLL_FRAMES = 30
        /** Скільки кадрів мовлення дочитати після спрацювання вартового для оцінки тону. */
        private const val PITCH_EXTRA_FRAMES = 12
        private const val MAX_UTTERANCE_MS = 15_000L
        private const val ALARM_UTTERANCE_MS = 4_000L
        /** Поки дзвонить будильник — перевіряти знову через стільки. */
        private const val RINGING_RECHECK_MS = 4_000L
        private const val RINGING_STICKY_MS = 3_000L
        /** Звуку фрази для відбитка голосу — не більше 8 с. */
        private const val MAX_AUDIO = RATE * 8
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
