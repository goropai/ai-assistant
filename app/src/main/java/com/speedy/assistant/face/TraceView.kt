package com.speedy.assistant.face

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Урок письма: обрис фігури, цифри чи літери пунктиром; дитина веде пальцем — обведені ділянки
 * зафарбовуються (видно прогрес). ≥ [DONE_SHARE] точок обрису — [onDone]. Над обличчям, під маленькими очима.
 */
class TraceView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF9E9E9E.toInt(); strokeWidth = 4 * density
        pathEffect = DashPathEffect(floatArrayOf(14 * density, 10 * density), 0f)
    }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43A047.toInt() }
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 10 * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43A047.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303030.toInt() }

    private var target: String? = null
    private val outline = Path()
    private var points = FloatArray(0)
    private var covered = BooleanArray(0)
    private val finger = Path()
    private var finished = false

    /** Обвели: викликається один раз на обрис (головний потік). */
    var onDone: () -> Unit = {}
    /** Торкнулись (щоб рахувати "не торкались" від останнього дотику). */
    var onTouched: () -> Unit = {}

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun show(target: String) {
        this.target = target
        finished = false
        finger.reset()
        if (width > 0) build()
        visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        target = null
        visibility = GONE
    }

    val progress: Float get() = if (covered.isEmpty()) 0f else covered.count { it } / covered.size.toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (target != null) build()
    }

    /** Обрис у вільній частині екрана (верхня чверть — для маленьких очей), точки — щокожні ~[STEP_DP]. */
    private fun build() {
        val t = target ?: return
        val area = RectF(width * 0.1f, height * 0.26f, width * 0.9f, height * 0.94f)
        outline.reset()
        if (t.startsWith("shape:")) shape(t.removePrefix("shape:"), area) else glyph(t, area)
        val list = ArrayList<Float>()
        val measure = PathMeasure(outline, false)
        val pos = FloatArray(2)
        do {
            var d = 0f
            while (d < measure.length) {
                measure.getPosTan(d, pos, null)
                list += pos[0]; list += pos[1]
                d += STEP_DP * density
            }
        } while (measure.nextContour())
        points = list.toFloatArray()
        covered = BooleanArray(points.size / 2)
    }

    private fun shape(name: String, a: RectF) {
        val s = min(a.width(), a.height()) * 0.8f
        val cx = a.centerX(); val cy = a.centerY(); val r = s / 2
        when (name) {
            "circle" -> outline.addCircle(cx, cy, r, Path.Direction.CW)
            "square" -> outline.addRect(cx - r, cy - r, cx + r, cy + r, Path.Direction.CW)
            "triangle" -> { outline.moveTo(cx, cy - r); outline.lineTo(cx + r, cy + r * 0.8f); outline.lineTo(cx - r, cy + r * 0.8f); outline.close() }
            "star" -> {
                for (i in 0 until 10) {
                    val ang = Math.PI / 2 + i * Math.PI / 5
                    val rr = if (i % 2 == 0) r else r * 0.45f
                    val x = cx + (rr * kotlin.math.cos(ang)).toFloat(); val y = cy - (rr * kotlin.math.sin(ang)).toFloat()
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                outline.close()
            }
            else -> { // heart
                outline.moveTo(cx, cy + r * 0.85f)
                outline.cubicTo(cx - r * 1.4f, cy - r * 0.1f, cx - r * 0.6f, cy - r * 1.1f, cx, cy - r * 0.35f)
                outline.cubicTo(cx + r * 0.6f, cy - r * 1.1f, cx + r * 1.4f, cy - r * 0.1f, cx, cy + r * 0.85f)
            }
        }
    }

    /** Контур символу жирним шрифтом, вписаний у [a]. */
    private fun glyph(text: String, a: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = a.height() }
        val raw = Path()
        p.getTextPath(text, 0, text.length, 0f, 0f, raw)
        val b = RectF()
        raw.computeBounds(b, true)
        if (b.width() <= 0 || b.height() <= 0) return
        val k = min(a.width() / b.width(), a.height() / b.height()) * 0.85f
        val m = android.graphics.Matrix().apply {
            postTranslate(-b.centerX(), -b.centerY()); postScale(k, k); postTranslate(a.centerX(), a.centerY())
        }
        raw.transform(m)
        outline.set(raw)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (target == null || finished) return true
        onTouched()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> finger.moveTo(e.x, e.y)
            MotionEvent.ACTION_MOVE -> finger.lineTo(e.x, e.y)
        }
        val radius = TOUCH_DP * density
        for (i in covered.indices) {
            if (!covered[i] && hypot(points[i * 2] - e.x, points[i * 2 + 1] - e.y) <= radius) covered[i] = true
        }
        invalidate()
        if (progress >= DONE_SHARE) { finished = true; onDone() }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (target == null) return
        canvas.drawPath(outline, dashed)
        canvas.drawPath(finger, fingerPaint)
        val r = 5 * density
        for (i in covered.indices) if (covered[i]) canvas.drawCircle(points[i * 2], points[i * 2 + 1], r, donePaint)
        // Смужка прогресу внизу.
        val h = 6 * density
        val top = height - 14 * density
        canvas.drawRect(width * 0.1f, top, width * 0.9f, top + h, trackPaint)
        canvas.drawRect(width * 0.1f, top, width * 0.1f + width * 0.8f * progress, top + h, progressPaint)
    }

    private companion object {
        const val STEP_DP = 12f
        const val TOUCH_DP = 26f
        const val DONE_SHARE = 0.9f
    }
}
