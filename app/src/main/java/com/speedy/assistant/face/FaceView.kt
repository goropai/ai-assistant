package com.speedy.assistant.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

        var bob = 0f
        if (speaking) {
            bob = sin(now / 110.0).toFloat() * 0.035f
            animating = true
        }

        drawEye(canvas, left = true, s = shape, blink = blink, bob = bob)
        drawEye(canvas, left = false, s = shape, blink = blink, bob = bob)
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

    private fun drawEye(canvas: Canvas, left: Boolean, s: EyeShape, blink: Float, bob: Float) {
        val side = if (left) -1f else 1f
        val cx = width / 2f + side * eyeSpacing + s.gazeX * eyeW * 0.6f
        val cy = centerY + s.gazeY * eyeH * 0.5f - bob * eyeH
        val w = eyeW * s.scaleX
        val h = eyeH * s.scaleY * blink * (1f + bob)

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

    private companion object {
        /** Скільки тримати погляд на об'єкті, перш ніж знову "розглядатися". */
        const val LOOK_HOLD_MS = 3000L
    }
}
