package com.speedy.assistant.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Мінімалістичне обличчя: два білі ока на чорному тлі.
 *
 * Енергоощадність: кадри малюються тільки поки йде анімація (перехід настрою,
 * кліпання, мовлення). У спокої View не перемальовується зовсім, а чорні пікселі
 * на OLED-екрані не світяться.
 */
class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var brainStatus = com.speedy.assistant.brain.BrainStatus.UNKNOWN
    private val eyeRect = RectF()
    private val lidRect = RectF()

    private var eyeW = 0f
    private var eyeH = 0f
    private var eyeSpacing = 0f
    private var centerY = 0f

    private var from = EyeShape.of(Mood.NEUTRAL)
    private var to = from
    private var transitionStart = 0L
    private val transitionMs = 280L

    private var blinkStart = -1L
    private val blinkMs = 160L

    private var speaking = false
    private var lookUntil = 0L

    var mood: Mood = Mood.NEUTRAL
        private set

    // --- Ілюстрація до уроку: очі меншають і йдуть угору, внизу — малюнок ---
    private var picture: List<List<String>>? = null
    private var pictureFrom = 0f
    private var pictureTo = 0f
    private var pictureStart = 0L
    private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val shapePath = Path()

    /**
     * Показати малюнок (рядки "клітинок": емодзі, текст чи фігура "circle:red", "square:blue",
     * "triangle:green", "star:yellow", "heart:red") або прибрати (null).
     */
    fun showPicture(rows: List<List<String>>?) {
        mirror?.showPicture(rows)
        val now = SystemClock.uptimeMillis()
        pictureFrom = pictureProgress(now)
        pictureTo = if (rows.isNullOrEmpty()) 0f else 1f
        if (!rows.isNullOrEmpty()) picture = rows
        pictureStart = now
        postInvalidateOnAnimation()
    }

    private fun pictureProgress(now: Long): Float {
        val t = ((now - pictureStart).toFloat() / PICTURE_MS).coerceIn(0f, 1f)
        return pictureFrom + (pictureTo - pictureFrom) * easeInOut(t)
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    /** Міні-копія поверх інших застосунків — повторює все, що робить це обличчя. */
    var mirror: FaceView? = null
        set(value) {
            field = value
            value?.let { m -> m.setMood(mood); m.setSpeaking(speaking); m.setBrainStatus(brainStatus) }
        }

    fun setMood(newMood: Mood) {
        mirror?.setMood(newMood)
        if (newMood == mood) return
        mood = newMood
        animateTo(EyeShape.of(newMood))
        // Зміна настрою часто супроводжується кліпанням — виглядає живіше.
        if (newMood != Mood.SLEEPY) blink()
    }

    fun setSpeaking(value: Boolean) {
        mirror?.setSpeaking(value)
        if (speaking == value) return
        speaking = value
        postInvalidateOnAnimation()
    }

    /** Подивитися в точку (-1..1, x вправо, y вниз) — напр. на людину в кадрі. */
    fun lookAt(x: Float, y: Float) {
        mirror?.lookAt(x, y)
        lookUntil = SystemClock.uptimeMillis() + LOOK_HOLD_MS
        animateTo(EyeShape.of(mood).withGaze(x.coerceIn(-1f, 1f) * 0.6f, y.coerceIn(-1f, 1f) * 0.4f))
    }

    /** Тьмяна крапка внизу: зелена — "мозок" на зв'язку, червона — ні, жовта — немає ключа. */
    fun setBrainStatus(status: com.speedy.assistant.brain.BrainStatus) {
        mirror?.setBrainStatus(status)
        if (status == brainStatus) return
        brainStatus = status
        invalidate()
    }

    fun blink() {
        blinkStart = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun animateTo(target: EyeShape) {
        val now = SystemClock.uptimeMillis()
        from = currentShape(now)
        to = target
        transitionStart = now
        postInvalidateOnAnimation()
    }

    private fun currentShape(now: Long): EyeShape {
        val t = ((now - transitionStart).toFloat() / transitionMs).coerceIn(0f, 1f)
        return EyeShape.lerp(from, to, easeInOut(t))
    }

    private fun easeInOut(t: Float) = t * t * (3f - 2f * t)

    // --- Фонові "ознаки життя": кліпання та рідкісні рухи очей ---

    private val blinkLoop = object : Runnable {
        override fun run() {
            if (mood != Mood.SLEEPY || Random.nextFloat() < 0.3f) blink()
            postDelayed(this, Random.nextLong(2500, 6500))
        }
    }

    private val glanceLoop = object : Runnable {
        override fun run() {
            if ((mood == Mood.NEUTRAL || mood == Mood.LISTENING) && SystemClock.uptimeMillis() > lookUntil) {
                val base = EyeShape.of(mood)
                val look = Random.nextFloat() < 0.6f
                animateTo(
                    if (look) base.withGaze(Random.nextFloat() * 0.5f - 0.25f, Random.nextFloat() * 0.3f - 0.15f)
                    else base
                )
            }
            postDelayed(this, Random.nextLong(4000, 9000))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blinkLoop, 2000)
        postDelayed(glanceLoop, 5000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkLoop)
        removeCallbacks(glanceLoop)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Ширина ока обмежена і шириною, і висотою — так очі добре виглядають в обох орієнтаціях.
        eyeW = min(w * 0.28f, h * 0.42f)
        eyeH = min(eyeW * 1.3f, h * 0.55f)
        eyeSpacing = min(w * 0.2f, eyeW * 1.1f)
        // У вертикальному режимі очі трохи вище центру — як на обличчі.
        centerY = if (h > w) h * 0.42f else h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = now - transitionStart < transitionMs
        val shape = currentShape(now)

        var blink = 1f
        if (blinkStart >= 0) {
            val bt = (now - blinkStart).toFloat() / blinkMs
            if (bt >= 1f) {
                blinkStart = -1
            } else {
                blink = 1f - 0.92f * sin(PI * bt).toFloat()
                animating = true
            }
        }

        val pic = pictureProgress(now)
        if (now - pictureStart < PICTURE_MS) animating = true
        if (pic <= 0.001f && pictureTo == 0f) picture = null

        var bob = 0f
        if (speaking) {
            bob = sin(now / 110.0).toFloat() * 0.035f
            animating = true
        }

        drawEye(canvas, left = true, s = shape, blink = blink, bob = bob, pic = pic)
        drawEye(canvas, left = false, s = shape, blink = blink, bob = bob, pic = pic)
        if (pic > 0.01f) drawPicture(canvas, pic)
        drawBrainStatus(canvas)

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawBrainStatus(canvas: Canvas) {
        statusPaint.color = when (brainStatus) {
            com.speedy.assistant.brain.BrainStatus.OK -> 0xFF1F7A3A.toInt()
            com.speedy.assistant.brain.BrainStatus.DOWN -> 0xFF8A2020.toInt()
            com.speedy.assistant.brain.BrainStatus.NO_KEY -> 0xFF8A6A10.toInt()
            com.speedy.assistant.brain.BrainStatus.UNKNOWN -> return
        }
        val r = resources.displayMetrics.density * 4
        canvas.drawCircle(width / 2f, height - r * 4, r, statusPaint)
    }

    private fun drawEye(canvas: Canvas, left: Boolean, s: EyeShape, blink: Float, bob: Float, pic: Float) {
        // Малюнок: очі втричі менші й угорі.
        val k = 1f - (1f - PICTURE_EYES) * pic
        val baseY = centerY + (eyeH * PICTURE_EYES * 0.75f - centerY) * pic
        val side = if (left) -1f else 1f
        val cx = width / 2f + side * eyeSpacing * k + s.gazeX * eyeW * k * 0.6f
        val cy = baseY + s.gazeY * eyeH * k * 0.5f - bob * eyeH * k
        val w = eyeW * k * s.scaleX
        val h = eyeH * k * s.scaleY * blink * (1f + bob)

        eyeRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = min(w, h) * s.roundness
        canvas.drawRoundRect(eyeRect, r, r, eyePaint)

        if (s.topLid > 0.01f) {
            canvas.save()
            canvas.rotate(if (left) s.lidAngle else -s.lidAngle, cx, cy)
            lidRect.set(cx - w * 0.8f, cy - h * 1.5f, cx + w * 0.8f, eyeRect.top + h * s.topLid)
            canvas.drawRect(lidRect, lidPaint)
            canvas.restore()
        }

        if (s.bottomLid > 0.01f) {
            val top = eyeRect.bottom - h * s.bottomLid
            lidRect.set(cx - w * 0.8f, top, cx + w * 0.8f, top + h * 1.2f)
            canvas.drawOval(lidRect, lidPaint)
        }
    }

    /** Малюнок під очима: кожен рядок — по центру, клітинки однакового розміру. */
    private fun drawPicture(canvas: Canvas, pic: Float) {
        val rows = picture ?: return
        val top = eyeH * PICTURE_EYES * 1.6f
        val bottom = height - resources.displayMetrics.density * 20
        val areaW = width * 0.92f
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val cell = min(areaW / cols, (bottom - top) / rows.size)
        val alpha = (255 * pic).toInt()
        rows.forEachIndexed { r, row ->
            val cy = top + (bottom - top - cell * rows.size) / 2f + cell * (r + 0.5f)
            row.forEachIndexed { c, token ->
                val cx = width / 2f + (c - (row.size - 1) / 2f) * cell
                drawToken(canvas, token.trim(), cx, cy, cell * 0.8f, alpha)
            }
        }
    }

    private fun drawToken(canvas: Canvas, token: String, cx: Float, cy: Float, size: Float, alpha: Int) {
        val m = SHAPE.matchEntire(token.lowercase())
        if (m == null) {
            picturePaint.color = Color.WHITE
            picturePaint.alpha = alpha
            picturePaint.textSize = size * (if (token.length <= 2) 0.85f else 1.6f / token.length.coerceAtLeast(2))
            canvas.drawText(token, cx, cy - (picturePaint.descent() + picturePaint.ascent()) / 2f, picturePaint)
            return
        }
        picturePaint.color = COLORS[m.groupValues[2]] ?: Color.WHITE
        picturePaint.alpha = alpha
        val r = size / 2f
        when (m.groupValues[1]) {
            "circle" -> canvas.drawCircle(cx, cy, r, picturePaint)
            "square" -> canvas.drawRect(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, picturePaint)
            "triangle" -> {
                shapePath.reset()
                shapePath.moveTo(cx, cy - r); shapePath.lineTo(cx + r, cy + r * 0.8f); shapePath.lineTo(cx - r, cy + r * 0.8f); shapePath.close()
                canvas.drawPath(shapePath, picturePaint)
            }
            "star" -> {
                shapePath.reset()
                for (i in 0 until 10) {
                    val a = Math.PI / 2 + i * Math.PI / 5
                    val rr = if (i % 2 == 0) r else r * 0.45f
                    val x = cx + (rr * kotlin.math.cos(a)).toFloat()
                    val y = cy - (rr * kotlin.math.sin(a)).toFloat()
                    if (i == 0) shapePath.moveTo(x, y) else shapePath.lineTo(x, y)
                }
                shapePath.close()
                canvas.drawPath(shapePath, picturePaint)
            }
            "heart" -> {
                shapePath.reset()
                shapePath.moveTo(cx, cy + r * 0.85f)
                shapePath.cubicTo(cx - r * 1.4f, cy - r * 0.1f, cx - r * 0.6f, cy - r * 1.1f, cx, cy - r * 0.35f)
                shapePath.cubicTo(cx + r * 0.6f, cy - r * 1.1f, cx + r * 1.4f, cy - r * 0.1f, cx, cy + r * 0.85f)
                canvas.drawPath(shapePath, picturePaint)
            }
        }
    }

    private companion object {
        const val PICTURE_MS = 400L
        /** Розмір очей, поки показано малюнок. */
        const val PICTURE_EYES = 0.35f
        val SHAPE = Regex("(circle|square|triangle|star|heart)(?::(\\w+))?")
        val COLORS = mapOf(
            "red" to 0xFFE53935.toInt(), "blue" to 0xFF1E88E5.toInt(), "green" to 0xFF43A047.toInt(),
            "yellow" to 0xFFFDD835.toInt(), "orange" to 0xFFFB8C00.toInt(), "purple" to 0xFF8E24AA.toInt(),
            "pink" to 0xFFEC407A.toInt(), "brown" to 0xFF8D6E63.toInt(), "white" to Color.WHITE, "gray" to 0xFF9E9E9E.toInt(),
            "black" to 0xFF424242.toInt(),
        )

        /** Скільки тримати погляд на об'єкті, перш ніж знову "розглядатися". */
        const val LOOK_HOLD_MS = 3000L
    }
}
