package com.jerrynifemi.pourlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A particle fluid sandbox. One engine, four materials, gravity supplied by the
 * phone's tilt, and a cup you can shove around the screen.
 */
class FluidView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    enum class Material { WATER, SAND, BUBBLES, HONEY }

    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var jitter: Float)

    private class Vessel(var cx: Float, var cy: Float, var hw: Float, var hh: Float, val moving: Boolean)

    private val parts = ArrayList<Particle>(MAX)
    private val cup = Vessel(0f, 0f, 0f, 0f, true)
    private val glass = Vessel(0f, 0f, 0f, 0f, false)

    private val cell = 26f
    private val grid = HashMap<Int, ArrayList<Int>>()

    private var tiltX = 0f
    private var tiltY = 0f
    private var sensorManager: SensorManager? = null
    private var accel: Sensor? = null
    private var running = false
    private var lastNanos = 0L

    // material tuning
    private var cohesion = 0.9f
    private var viscosity = 0.16f
    private var restitution = 0.18f
    private var gravityScale = 1f
    private var renderRadius = 22f
    private var liquidColor = Color.parseColor("#4DA3FF")

    var material: Material = Material.WATER
        set(value) {
            field = value
            applyMaterial()
            invalidate()
        }

    companion object {
        const val MAX = 340
        private const val SUBSTEPS = 3
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var blobShader: RadialGradient? = null
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#8CFFFFFF")
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1FFFFFFF") }
    private val matrix = Matrix()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastNanos == 0L) lastNanos = frameTimeNanos
            var dt = (frameTimeNanos - lastNanos) / 1_000_000_000f
            lastNanos = frameTimeNanos
            dt = dt.coerceIn(0.001f, 0.033f)
            step(dt)
            invalidate()
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastNanos = 0L
        if (blobShader == null) applyMaterial()
        Choreographer.getInstance().postFrameCallback(frame)
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accel?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        tiltX = (-event.values[0] / 9.81f).coerceIn(-1.2f, 1.2f)
        tiltY = (event.values[1] / 9.81f).coerceIn(-1.2f, 1.2f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyMaterial() {
        when (material) {
            Material.WATER -> {
                cohesion = 0.9f; viscosity = 0.16f; restitution = 0.20f
                gravityScale = 1f; liquidColor = Color.parseColor("#4DA3FF")
                renderRadius = dp(22f)
            }
            Material.SAND -> {
                cohesion = 0.06f; viscosity = 0.05f; restitution = 0.06f
                gravityScale = 1.4f; liquidColor = Color.parseColor("#E0A458")
                renderRadius = dp(9f)
            }
            Material.BUBBLES -> {
                cohesion = 0.25f; viscosity = 0.20f; restitution = 0.55f
                gravityScale = -0.42f; liquidColor = Color.parseColor("#BFE9FF")
                renderRadius = dp(16f)
            }
            Material.HONEY -> {
                cohesion = 1.25f; viscosity = 0.36f; restitution = 0.08f
                gravityScale = 0.85f; liquidColor = Color.parseColor("#FFB020")
                renderRadius = dp(24f)
            }
        }
        val c = liquidColor
        val shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.argb(242, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(175, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
            ),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        blobShader = shader
        blobPaint.shader = shader
    }

    private fun particleRadius() = dp(6.5f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cup.cx = w * 0.30f
        cup.cy = h * 0.26f
        cup.hw = dp(62f)
        cup.hh = dp(60f)
        glass.cx = w * 0.70f
        glass.cy = h * 0.80f
        glass.hw = dp(48f)
        glass.hh = dp(58f)
        if (parts.isEmpty()) refill()
    }

    /** Fills the cup to the brim (or tops it up). */
    fun refill(count: Int = 210) {
        val target = min(MAX, parts.size + count)
        var added = 0
        var guard = 0
        while (parts.size < target && guard < 2000) {
            guard++
            val px = cup.cx + (Random.nextFloat() - 0.5f) * (cup.hw * 1.7f)
            val py = cup.cy + (Random.nextFloat() - 0.5f) * (cup.hh * 1.5f)
            parts.add(Particle(px, py, 0f, 0f, Random.nextFloat()))
            added++
        }
        if (added > 0) invalidate()
    }

    fun clearLiquid() {
        parts.clear()
        invalidate()
    }

    private fun step(dt: Float) {
        if (width == 0) return
        val gx = tiltX * 1500f * gravityScale
        val gy = (1500f + tiltY * 250f) * gravityScale
        val h = dt / SUBSTEPS
        repeat(SUBSTEPS) { substep(h, gx, gy) }
        parts.removeAll { it.y > height + dp(140f) || it.x < -dp(90f) || it.x > width + dp(90f) }
    }

    private fun substep(h: Float, gx: Float, gy: Float) {
        for (p in parts) {
            p.vx += gx * h
            p.vy += gy * h
            if (material == Material.BUBBLES) {
                p.vx += sin((p.y * 0.02f + p.jitter * 9f).toDouble()).toFloat() * 140f * h
            }
            val damp = if (material == Material.HONEY) 0.988f else 0.996f
            p.vx *= damp
            p.vy *= damp
            val vmax = dp(1100f)
            val sp = hypot(p.vx, p.vy)
            if (sp > vmax) {
                p.vx = p.vx / sp * vmax
                p.vy = p.vy / sp * vmax
            }
            p.x += p.vx * h
            p.y += p.vy * h
        }
        neighbourForces(h)
        collide()
    }

    private fun key(cx: Int, cy: Int): Int = ((cx + 512) shl 12) xor (cy + 512)

    private fun neighbourForces(h: Float) {
        grid.clear()
        for (i in parts.indices) {
            val p = parts[i]
            val k = key((p.x / cell).toInt(), (p.y / cell).toInt())
            grid.getOrPut(k) { ArrayList(8) }.add(i)
        }
        val sep = particleRadius() * 2f
        for (i in parts.indices) {
            val a = parts[i]
            val cx = (a.x / cell).toInt()
            val cy = (a.y / cell).toInt()
            for (ox in -1..1) {
                for (oy in -1..1) {
                    val bucket = grid[key(cx + ox, cy + oy)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue
                        val b = parts[j]
                        var dx = b.x - a.x
                        var dy = b.y - a.y
                        var d = hypot(dx, dy)
                        if (d < 0.0001f) {
                            dx = 0.01f
                            dy = 0f
                            d = 0.01f
                        }
                        if (d < sep) {
                            val push = (sep - d) * 0.5f
                            val nx = dx / d
                            val ny = dy / d
                            a.x -= nx * push
                            a.y -= ny * push
                            b.x += nx * push
                            b.y += ny * push
                            val avx = (a.vx + b.vx) * 0.5f
                            val avy = (a.vy + b.vy) * 0.5f
                            a.vx += (avx - a.vx) * viscosity
                            a.vy += (avy - a.vy) * viscosity
                            b.vx += (avx - b.vx) * viscosity
                            b.vy += (avy - b.vy) * viscosity
                        } else if (cohesion > 0f && d < sep * 1.8f) {
                            val pull = cohesion * 60f * h
                            val nx = dx / d
                            val ny = dy / d
                            a.vx += nx * pull
                            a.vy += ny * pull
                            b.vx -= nx * pull
                            b.vy -= ny * pull
                        }
                    }
                }
            }
        }
    }

    private fun collide() {
        val pr = particleRadius()
        val floorY = height - dp(40f)
        for (p in parts) {
            if (p.x - pr < 0f) {
                p.x = pr
                if (p.vx < 0f) p.vx = -p.vx * restitution
            } else if (p.x + pr > width) {
                p.x = width - pr
                if (p.vx > 0f) p.vx = -p.vx * restitution
            }
            if (p.y - pr < 0f) {
                p.y = pr
                if (p.vy < 0f) p.vy = 0f
            }
            segment(p, 0f, floorY, width.toFloat(), floorY)
            collideVessel(cup, p)
            collideVessel(glass, p)
        }
    }

    private fun collideVessel(v: Vessel, p: Particle) {
        val l = v.cx - v.hw
        val r = v.cx + v.hw
        val b = v.cy + v.hh
        segment(p, l, v.cy - v.hh, l, b)
        segment(p, r, v.cy - v.hh, r, b)
        segment(p, l, b, r, b)
    }

    private fun segment(p: Particle, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0f) 0f else ((p.x - x1) * dx + (p.y - y1) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val px = x1 + dx * t
        val py = y1 + dy * t
        var nx = p.x - px
        var ny = p.y - py
        val d = hypot(nx, ny)
        val pr = particleRadius()
        if (d < pr && d > 0.0001f) {
            nx /= d
            ny /= d
            p.x = px + nx * pr
            p.y = py + ny * pr
            val vn = p.vx * nx + p.vy * ny
            if (vn < 0f) {
                p.vx -= (1f + restitution) * vn * nx
                p.vy -= (1f + restitution) * vn * ny
                p.vx *= 0.99f
                p.vy *= 0.99f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawVessel(canvas, cup)
        drawVessel(canvas, glass)
        when (material) {
            Material.SAND -> drawSand(canvas)
            Material.BUBBLES -> drawBubbles(canvas)
            else -> drawLiquid(canvas)
        }
    }

    private fun drawVessel(canvas: Canvas, v: Vessel) {
        val l = v.cx - v.hw
        val r = v.cx + v.hw
        val t = v.cy - v.hh
        val b = v.cy + v.hh
        canvas.drawRoundRect(l, t, r, b, dp(12f), dp(12f), glassPaint)
        canvas.drawLine(l, t, l, b, wallPaint)
        canvas.drawLine(r, t, r, b, wallPaint)
        canvas.drawLine(l, b, r, b, wallPaint)
    }

    private fun drawLiquid(canvas: Canvas) {
        for (p in parts) {
            matrix.setScale(renderRadius, renderRadius)
            matrix.postTranslate(p.x, p.y)
            blobShader?.setLocalMatrix(matrix)
            canvas.drawCircle(p.x, p.y, renderRadius, blobPaint)
        }
        for (p in parts) {
            if (Random.nextInt(14) == 0) {
                canvas.drawCircle(p.x - renderRadius * 0.22f, p.y - renderRadius * 0.28f, dp(2f), glintPaint)
            }
        }
    }

    private fun drawSand(canvas: Canvas) {
        grainPaint.color = liquidColor
        val r = particleRadius() * 1.1f
        for (p in parts) canvas.drawCircle(p.x, p.y, r, grainPaint)
    }

    private fun drawBubbles(canvas: Canvas) {
        ringPaint.color = Color.argb(180, 255, 255, 255)
        ringPaint.strokeWidth = dp(1.6f)
        val r = renderRadius * 0.85f
        for (p in parts) {
            canvas.drawCircle(p.x, p.y, r, ringPaint)
            canvas.drawCircle(p.x - r * 0.34f, p.y - r * 0.38f, r * 0.2f, glintPaint)
        }
    }

    private var dragging = false
    private var grabX = 0f
    private var grabY = 0f
    private var cupStartX = 0f
    private var cupStartY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (abs(event.x - cup.cx) < cup.hw + dp(26f) && abs(event.y - cup.cy) < cup.hh + dp(26f)) {
                    dragging = true
                    grabX = event.x
                    grabY = event.y
                    cupStartX = cup.cx
                    cupStartY = cup.cy
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    cup.cx = (cupStartX + event.x - grabX).coerceIn(cup.hw, width - cup.hw)
                    cup.cy = (cupStartY + event.y - grabY).coerceIn(cup.hh, height - cup.hh - dp(24f))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }
}
