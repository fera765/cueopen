package dev.cueopen.app.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Orb leve de Canvas; o RMS recebido do reconhecedor altera a expansão e a densidade visual. */
class AssistantOrbView(context: Context) : View(context) {
    private data class Particle(val angle: Float, val radius: Float, val size: Float, val speed: Float, val phase: Float)

    private val particles = List(240) {
        Particle(
            angle = Random.nextFloat() * (2f * PI.toFloat()),
            radius = 0.55f + Random.nextFloat() * 0.58f,
            size = 0.55f + Random.nextFloat() * 1.8f,
            speed = if (Random.nextBoolean()) 0.18f + Random.nextFloat() * 0.48f else -(0.18f + Random.nextFloat() * 0.42f),
            phase = Random.nextFloat() * (2f * PI.toFloat()),
        )
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var rms = 0f
    private var smoothedRms = 0f
    private var frame = 0
    private var listening = false

    init {
        contentDescription = "Orb animado do assistente; toque para ouvir novamente"
        isClickable = true
    }

    fun setRms(value: Float) {
        rms = value.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun setListening(value: Boolean) {
        listening = value
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) * 0.315f
        smoothedRms += (rms - smoothedRms) * 0.16f
        val pulse = 1f + 0.025f * sin(frame * 0.026f) + smoothedRms * 0.12f
        val outer = baseRadius * pulse
        frame++

        glow.shader = RadialGradient(
            cx, cy, outer * 1.72f,
            intArrayOf(0x3DFF791F, 0x1FFF6112, 0x08FF4B0A, Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, outer * 1.72f, glow)
        glow.shader = null

        val time = frame * 0.018f
        particles.forEach { p ->
            val angle = p.angle + time * p.speed * (0.65f + smoothedRms * 1.8f)
            val wave = sin(time * 1.7f + p.phase + p.angle * 2.5f) * (0.025f + smoothedRms * 0.095f)
            val radius = outer * (p.radius + wave)
            val x = cx + cos(angle.toDouble()).toFloat() * radius
            val y = cy + sin(angle.toDouble()).toFloat() * radius
            val alpha = (95 + (sin(time + p.phase).toFloat() + 1f) * 70 + smoothedRms * 65).toInt().coerceIn(50, 235)
            particlePaint.color = Color.argb(alpha, 255, 119 + (p.phase * 8).toInt().coerceIn(0, 100), 28)
            canvas.drawCircle(x, y, p.size * (0.78f + smoothedRms * 0.8f), particlePaint)
        }

        drawRibbon(canvas, cx, cy, outer, time, 0.012f, 3.2f, 0xEFFF9B43.toInt(), 2.2f)
        drawRibbon(canvas, cx, cy, outer, time + 1.9f, 0.018f, 4.1f, 0xBFFF6B1A.toInt(), 1.4f)
        drawRibbon(canvas, cx, cy, outer * 0.76f, time - 0.8f, 0.028f, 3.0f, 0x55FFB04A, 0.9f)

        // Anel de estado: mais vivo enquanto o microfone está aberto e capturando.
        ringPaint.color = if (listening) 0xEFFF9A45.toInt() else 0x66FF8A2A
        ringPaint.strokeWidth = if (listening) 1.7f else 1.0f
        canvas.drawCircle(cx, cy, outer * 1.12f, ringPaint)

        // Halo central suave: mantém o centro aberto e deixa o app subjacente visível.
        glow.shader = RadialGradient(cx, cy, outer * 0.72f,
            intArrayOf(0x18FF7A1E, 0x0AFF6A12, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, outer * 0.72f, glow)
        glow.shader = null
        postInvalidateOnAnimation()
    }

    private fun drawRibbon(canvas: Canvas, cx: Float, cy: Float, radius: Float, time: Float, wave: Float, lobes: Float, color: Int, stroke: Float) {
        val path = Path()
        val steps = 220
        for (i in 0..steps) {
            val angle = (i.toFloat() / steps) * (2f * PI.toFloat())
            val modulation = sin(angle * lobes + time).toFloat() * wave +
                sin(angle * (lobes * 0.55f) - time * 0.72f).toFloat() * wave * 0.7f
            val radiusAtAngle = radius * (1f + modulation + smoothedRms * 0.055f * sin(angle * 3f + time * 1.3f).toFloat())
            val x = cx + cos(angle.toDouble()).toFloat() * radiusAtAngle
            val y = cy + sin(angle.toDouble()).toFloat() * radiusAtAngle
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        ringPaint.color = (color and 0x00FFFFFF) or 0x30000000
        ringPaint.strokeWidth = stroke * 4f
        canvas.drawPath(path, ringPaint)
        ringPaint.color = color
        ringPaint.strokeWidth = stroke
        canvas.drawPath(path, ringPaint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
