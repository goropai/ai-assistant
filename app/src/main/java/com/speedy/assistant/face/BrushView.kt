package com.speedy.assistant.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.speedy.assistant.brushing.BrushPlan
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

/**
 * Чищення зубів: верхній і нижній ряд зубів (по 4 у кожній з 4 зон, як у дзеркалі). На ще не почищених
 * зубах сидять мікробики й зникають у міру чищення; поточна зона підсвічена, почищені — блищать.
 * Внизу — смужка двох хвилин. Над обличчям, під маленькими очима (як урок письма).
 */
class BrushView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    /** Ще не почищений зуб — жовтуватий; почищений — білий і блищить. */
    private val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6D9A8.toInt() }
    private val shiny = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val gum = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE57373.toInt() }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF4FC3F7.toInt(); strokeWidth = 4 * density
    }
    private val microbe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7CB342.toInt() }
    private val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val sparkle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF59D.toInt(); strokeWidth = 2 * density }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303030.toInt() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43A047.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22 * density; textAlign = Paint.Align.CENTER }

    private var zone = BrushPlan.Zone.UPPER_RIGHT
    private var zoneDone = 0f
    private var total = 0f
    private var frame = 0

    fun update(zone: BrushPlan.Zone, zoneDone: Float, total: Float) {
        this.zone = zone
        this.zoneDone = zoneDone
        this.total = total
        frame++
        visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Ряди зубів — у нижніх двох третинах (угорі — маленькі очі Спіді).
        val toothW = min(w * 0.9f / TEETH_PER_ROW, h * 0.11f)
        val toothH = toothW * 1.25f
        val left = (w - toothW * TEETH_PER_ROW) / 2
        val upperY = h * 0.42f
        val lowerY = upperY + toothH + 24 * density
        drawRow(canvas, left, upperY, toothW, toothH, upper = true)
        drawRow(canvas, left, lowerY, toothW, toothH, upper = false)
        // Смужка двох хвилин і час.
        val barTop = lowerY + toothH + 40 * density
        val barRect = RectF(left, barTop, left + toothW * TEETH_PER_ROW, barTop + 14 * density)
        canvas.drawRoundRect(barRect, 7 * density, 7 * density, track)
        canvas.drawRoundRect(RectF(barRect.left, barRect.top, barRect.left + barRect.width() * total, barRect.bottom), 7 * density, 7 * density, bar)
        val secondsLeft = ((1 - total) * BrushPlan.TOTAL_S).toInt().coerceAtLeast(0)
        canvas.drawText("%d:%02d".format(secondsLeft / 60, secondsLeft % 60), w / 2, barRect.bottom + 32 * density, text)
    }

    private fun drawRow(canvas: Canvas, left: Float, top: Float, tw: Float, th: Float, upper: Boolean) {
        // Ясна — смуга над верхнім рядом / під нижнім.
        val gumH = th * 0.35f
        val gumTop = if (upper) top - gumH * 0.6f else top + th - gumH * 0.4f
        canvas.drawRoundRect(RectF(left - tw * 0.2f, gumTop, left + tw * (TEETH_PER_ROW + 0.2f), gumTop + gumH), gumH / 2, gumH / 2, gum)
        for (i in 0 until TEETH_PER_ROW) {
            // Як у дзеркалі: права сторона дитини — праворуч на екрані.
            val right = i >= TEETH_PER_ROW / 2
            val z = BrushPlan.Zone.entries.first { it.upper == upper && it.right == right }
            val rect = RectF(left + i * tw + tw * 0.06f, top, left + (i + 1) * tw - tw * 0.06f, top + th)
            // Мікробики: у майбутніх зонах — усі, у поточній — скільки ще не почистили, у минулих — жодного.
            val inZone = if (right) i - TEETH_PER_ROW / 2 else i
            val left4 = when {
                z.ordinal > zone.ordinal -> 4
                z == zone -> ceil((1 - zoneDone) * 4).toInt()
                else -> 0
            }
            // У поточній зоні мікробики зникають від середини рота до краю (останній — на крайньому зубі).
            val order = if (right) TEETH_PER_ROW / 2 - 1 - inZone else inZone
            val dirty = order < left4
            canvas.drawRoundRect(rect, tw * 0.25f, tw * 0.25f, if (dirty) tooth else shiny)
            if (dirty) drawMicrobe(canvas, rect, i) else drawSparkle(canvas, rect, i)
        }
        // Поточна зона — у рамці.
        if (zone.upper == upper) {
            val from = if (zone.right) TEETH_PER_ROW / 2 else 0
            val r = RectF(left + from * tw - 4 * density, top - 6 * density, left + (from + TEETH_PER_ROW / 2) * tw + 4 * density, top + th + 6 * density)
            canvas.drawRoundRect(r, 12 * density, 12 * density, active)
        }
    }

    private fun drawMicrobe(canvas: Canvas, rect: RectF, i: Int) {
        // Трохи погойдується — живий.
        val wobble = sin((frame + i * 3) * 0.6).toFloat() * rect.width() * 0.04f
        val cx = rect.centerX() + wobble
        val cy = rect.centerY()
        val r = rect.width() * 0.3f
        canvas.drawCircle(cx, cy, r, microbe)
        val eye = r * 0.32f
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.15f, eye, eyeWhite)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.15f, eye, eyeWhite)
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.1f, eye * 0.5f, pupil)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.1f, eye * 0.5f, pupil)
    }

    private fun drawSparkle(canvas: Canvas, rect: RectF, i: Int) {
        if ((frame / 3 + i) % 4 != 0) return
        val cx = rect.left + rect.width() * 0.7f
        val cy = rect.top + rect.height() * 0.3f
        val s = rect.width() * 0.15f
        canvas.drawLine(cx - s, cy, cx + s, cy, sparkle)
        canvas.drawLine(cx, cy - s, cx, cy + s, sparkle)
    }

    private companion object {
        const val TEETH_PER_ROW = 8
    }
}
