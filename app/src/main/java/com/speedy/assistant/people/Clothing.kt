package com.speedy.assistant.people

import android.graphics.Bitmap
import android.graphics.Color

/** Колір верхнього одягу за рамкою людини в кадрі — без моделі. */
object Clothing {

    /**
     * Беремо смугу під головою (25–60% висоти рамки, середні 60% ширини) і найчастіший колір.
     * @return назва кольору в місцевому відмінку ("синьому") або null — не вдалося визначити.
     */
    fun color(frame: Bitmap, left: Float, top: Float, right: Float, bottom: Float): String? {
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return null
        val x0 = ((left + w * 0.2f) * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = ((right - w * 0.2f) * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = ((top + h * 0.25f) * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = ((top + h * 0.6f) * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        val counts = HashMap<String, Int>()
        val hsv = FloatArray(3)
        val step = maxOf(1, (x1 - x0) / 20)
        var total = 0
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            Color.colorToHSV(frame.getPixel(x, y), hsv)
            val name = name(hsv[0], hsv[1], hsv[2])
            counts[name] = (counts[name] ?: 0) + 1
            total++
        }
        val (best, n) = counts.maxByOrNull { it.value } ?: return null
        return best.takeIf { n * 3 >= total } // переважний колір, а не строката суміш
    }

    /** HSV → назва кольору (місцевий відмінок, "в синьому"). */
    fun name(hue: Float, sat: Float, value: Float): String = when {
        value < 0.18f -> "чорному"
        sat < 0.18f && value > 0.8f -> "білому"
        sat < 0.18f -> "сірому"
        hue < 15 || hue >= 345 -> if (value < 0.45f) "бордовому" else "червоному"
        hue < 40 -> if (value < 0.55f) "коричневому" else "помаранчевому"
        hue < 65 -> "жовтому"
        hue < 160 -> "зеленому"
        hue < 200 -> "блакитному"
        hue < 255 -> "синьому"
        hue < 290 -> "фіолетовому"
        else -> "рожевому"
    }
}
