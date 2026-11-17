package com.speedy.assistant.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.speedy.assistant.settings.AppSettings

/** Вібрація Спіді: кожен випадок вмикається / вимикається в налаштуваннях окремо. */
class Buzzer(context: Context, private val settings: AppSettings) {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Дотик чи звертання "Спіді" — коротко: почула. */
    fun touch() { if (settings.vibrateTouch) pattern(0, 35) }

    /** Правильна відповідь в уроці — легко двічі. */
    fun cheer() { if (settings.vibrateCheer) pattern(0, 40, 80, 40) }

    /** Нагадування в тиші — тричі помітно (замість голосу). */
    fun quietReminder() { if (settings.vibrateQuiet) pattern(0, 400, 300, 400, 300, 400) }

    /** "Спіді, де ти?" — довго, щоб знайти телефон під подушкою. */
    fun find() { if (settings.vibrateFind) pattern(0, 800, 200, 800, 200, 800) }

    @Suppress("DEPRECATION") // VibrationEffect — з Android 8; J5 — 7.1
    private fun pattern(vararg timings: Long) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(timings, -1))
        else v.vibrate(timings, -1)
    }
}
