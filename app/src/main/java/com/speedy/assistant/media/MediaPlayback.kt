package com.speedy.assistant.media

import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

/** Що сталося з відтворенням — Спіді коротко скаже. */
sealed interface MediaEvent {
    /** Закрили плеєр посеред фільму — місце збережено. */
    data class Stopped(val item: MediaItem, val positionMs: Long) : MediaEvent
    /** Серію / файл теки додивились — наступна чекає на "наступна серія" (відео не вмикаємо самі). */
    data class NextReady(val item: MediaItem, val next: Int) : MediaEvent
    /** Увесь запис (фільм, аудіокнига, серіал) — до кінця. */
    data class Finished(val item: MediaItem) : MediaEvent
}

/**
 * Відтворення у VLC з пам'яттю місця. Відео: позиція приходить, коли плеєр закрили ([onVideoResult]).
 * Аудіо: [tick] кожні кілька секунд дивиться в медіасесію VLC — зберігає позицію, на початку
 * перемотує на збережене місце, а коли файл аудіокниги скінчився — вмикає наступний.
 */
class MediaPlayback(
    private val vlc: Vlc,
    private val shelf: MediaShelf,
    private val onEvent: (MediaEvent) -> Unit,
    /** Тривалість файлу, мс (0 — не відомо): VLC для файлів через content:// її в сесії не дає. */
    private val durationOf: (java.io.File) -> Long = { 0L },
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var item: MediaItem? = null
    private var track = 0
    private var startedAt = 0L
    private var pendingSeek = 0L
    /** Тривалість нашого файлу; 0 — VLC ще не заграв його. */
    private var duration = 0L
    /** Тривалість за медіасесією (якщо VLC її дає): інша — у VLC увімкнули щось своє, не стежимо. */
    private var sessionDuration = 0L
    private var fileDuration = 0L
    private var lastPosition = 0L
    private var pausedByUs = false

    /** Що грало останнім і, якщо пауза від Спіді, — можна продовжити. */
    val canResume: Boolean get() = pausedByUs

    /** VLC зараз грає звук (наш чи будь-який) — Спіді не слухає мікрофоном, лише дотик. */
    val playingAudio: Boolean
        get() = vlc.session()?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun start(item: MediaItem, from: MediaPlace?) {
        val place = from?.takeIf { !it.finished && it.track in item.tracks.indices } ?: MediaPlace(0, 0)
        this.item = item
        track = place.track
        startedAt = now()
        duration = 0
        lastPosition = place.positionMs
        pausedByUs = false
        shelf.save(item, place)
        val file = item.tracks[track]
        Log.i(TAG, "VLC: ${item.title}, файл ${track + 1}/${item.tracks.size}, з ${place.positionMs / 1000} с")
        if (item.kind == MediaKind.VIDEO) {
            vlc.playVideo(file, if (item.tracks.size > 1) "${item.title} — ${track + 1}" else item.title, place.positionMs)
        } else {
            pendingSeek = place.positionMs
            fileDuration = durationOf(file)
            vlc.playAudio(file)
        }
    }

    /** Плеєр відео закрився: позиція й тривалість від VLC (-1 — VLC не повідомив). */
    fun onVideoResult(ok: Boolean, positionMs: Long, durationMs: Long) {
        val current = item?.takeIf { it.kind == MediaKind.VIDEO } ?: return
        item = null
        val watched = now() - startedAt
        // Наприкінці VLC віддає позицію біля кінця або 0 (плеєр уже зупинився) — 0 рахуємо кінцем,
        // лише якщо дивились більшу частину тривалості.
        val ended = durationMs > 0 && (positionMs >= durationMs - END_MS || ok && positionMs <= 0 && watched > durationMs * 6 / 10)
        Log.i(TAG, "VLC відео: ${positionMs / 1000}/${durationMs / 1000} с, кінець=$ended")
        when {
            !ended -> {
                if (positionMs <= 0) return // не почали (помилка, одразу закрили) — місце не чіпаємо
                shelf.save(current, MediaPlace(track, positionMs))
                onEvent(MediaEvent.Stopped(current, positionMs))
            }
            track + 1 < current.tracks.size -> {
                shelf.save(current, MediaPlace(track + 1, 0))
                onEvent(MediaEvent.NextReady(current, track + 1))
            }
            else -> {
                shelf.save(current, MediaPlace(0, 0, finished = true))
                onEvent(MediaEvent.Finished(current))
            }
        }
    }

    /** Кожні кілька секунд, поки грає аудіо: позиція, перемотка на місце, наступний файл. */
    fun tick() {
        val current = item?.takeIf { it.kind == MediaKind.AUDIO } ?: return
        val session = vlc.session()
        val state = session?.playbackState
        val length = session?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (session == null || state == null) {
            // Дограв файл — VLC деактивує сесію (getActiveSessions її вже не віддає): це кінець файлу.
            if (duration > 0) return stopped(current)
            // Ще не заграв (або закрили, немає доступу) — довше хвилини без сесії: більше не стежимо.
            if (now() - startedAt > LOST_MS) item = null
            return
        }
        if (duration == 0L) {
            // Наш файл — той, що заграв першим після запуску. Доти сесія може показувати ще
            // попередній файл і "зупинено" — чекаємо (не довше хвилини).
            val known = fileDuration.takeIf { it > 0 } ?: length
            if (known <= 0 || state.state != PlaybackState.STATE_PLAYING) {
                if (now() - startedAt > LOST_MS) {
                    Log.i(TAG, "VLC не заграв файл ${track + 1} — не стежу")
                    item = null
                }
                return
            }
            duration = known
            sessionDuration = length.coerceAtLeast(0)
            Log.i(TAG, "грає файл ${track + 1}/${current.tracks.size}, ${duration / 1000} с")
        } else if (length > 0) {
            if (sessionDuration == 0L) sessionDuration = length
            else if (kotlin.math.abs(length - sessionDuration) > 1_000) {
                Log.i(TAG, "у VLC інший запис — не стежу")
                item = null
                return
            }
        }
        val playing = state.state == PlaybackState.STATE_PLAYING
        val position = state.position + if (playing) ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0L
        if (pendingSeek > 0 && playing) {
            if (pendingSeek < duration - END_MS) session.transportControls.seekTo(pendingSeek)
            pendingSeek = 0
            return
        }
        when (state.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED -> {
                if (playing) pausedByUs = false
                lastPosition = position.coerceIn(0, duration)
                shelf.save(current, MediaPlace(track, lastPosition))
            }
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> stopped(current)
        }
    }

    /** Файл зупинився: біля кінця — наступний (або кінець запису), інакше — зупинили вручну. */
    private fun stopped(current: MediaItem) {
        if (lastPosition < duration - END_MS) {
            Log.i(TAG, "зупинили на ${lastPosition / 1000} с — місце збережене")
            item = null
            return
        }
        if (track + 1 < current.tracks.size) {
            track++
            startedAt = now()
            duration = 0
            sessionDuration = 0
            lastPosition = 0
            fileDuration = durationOf(current.tracks[track])
            shelf.save(current, MediaPlace(track, 0))
            Log.i(TAG, "наступний файл ${track + 1}/${current.tracks.size}")
            vlc.playAudio(current.tracks[track])
        } else {
            Log.i(TAG, "дослухали: ${current.title}")
            item = null
            shelf.save(current, MediaPlace(0, 0, finished = true))
            onEvent(MediaEvent.Finished(current))
        }
    }

    /** Пауза (Спіді хоче говорити / "стоп"). */
    fun pause(): Boolean {
        val s = vlc.session()?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return false
        s.transportControls.pause()
        pausedByUs = true
        return true
    }

    fun resume(): Boolean {
        val s = vlc.session() ?: return false
        s.transportControls.play()
        pausedByUs = false
        return true
    }

    private companion object {
        const val TAG = "MediaPlayback"
        /** Ближче до кінця — уже "дослухали". */
        const val END_MS = 15_000L
        const val LOST_MS = 60_000L
    }
}
