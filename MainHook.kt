package com.waterdrop.module

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class WaterDropOverlay(context: Context) : View(context) {

    private val droplets = mutableListOf<Droplet>()
    private val trails = mutableListOf<Trail>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())

    init {
        // مهم: background را شفاف کن
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(50, 180, 220, 255)

        highlightPaint.style = Paint.Style.FILL
        highlightPaint.color = Color.argb(220, 255, 255, 255)

        borderPaint.style = Paint.Style.STROKE
        borderPaint.color = Color.argb(100, 100, 140, 180)
        borderPaint.strokeWidth = 1.5f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initDroplets(w, h)
    }

    private fun initDroplets(w: Int, h: Int) {
        droplets.clear()
        val count = 80 // کمتر برای performance بهتر
        repeat(count) {
            droplets.add(createDroplet(w, h, random.nextFloat() * h))
        }
    }

    private fun createDroplet(w: Int, h: Int, startY: Float = 0f): Droplet {
        return Droplet(
            x = random.nextFloat() * w,
            y = startY,
            radius = random.nextFloat() * 10f + 3f,
            speed = random.nextFloat() * 1.5f + 0.3f,
            opacity = random.nextFloat() * 0.4f + 0.2f,
            wobble = random.nextFloat() * 0.5f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // مهم: canvas را پاک نکن، فقط قطرات را بکش
        // اگر canvas.drawColor(Color.BLACK) بزنی، سیاه می‌شود!

        trails.forEach { trail ->
            drawTrail(canvas, trail)
        }

        droplets.forEach { droplet ->
            drawDroplet(canvas, droplet)
        }
    }

    private fun drawDroplet(canvas: Canvas, d: Droplet) {
        val x = d.x
        val y = d.y
        val r = d.radius

        paint.alpha = (d.opacity * 255).toInt()
        canvas.drawCircle(x, y, r, paint)

        val gradient = RadialGradient(
            x - r * 0.3f, y - r * 0.3f,
            r * 0.8f,
            intArrayOf(
                Color.argb(80, 255, 255, 255),
                Color.argb(20, 100, 150, 200)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null

        highlightPaint.alpha = 230
        canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.22f, highlightPaint)

        highlightPaint.alpha = 120
        canvas.drawCircle(x + r * 0.3f, y + r * 0.4f, r * 0.1f, highlightPaint)

        borderPaint.alpha = (d.opacity * 180).toInt()
        canvas.drawCircle(x, y, r, borderPaint)
    }

    private fun drawTrail(canvas: Canvas, trail: Trail) {
        paint.alpha = (trail.opacity * 255).toInt()
        canvas.drawCircle(trail.x, trail.y, trail.radius, paint)
    }

    fun startAnimation() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    updateDroplets()
                    invalidate()
                    handler.postDelayed(this, 16)
                } catch (e: Throwable) {
                    // اگر خطا داد، انیمیشن متوقف شود ولی overlay نماند
                    handler.removeCallbacksAndMessages(null)
                }
            }
        })
    }

    private fun updateDroplets() {
        val w = width.toFloat()
        val h = height.toFloat()

        droplets.forEach { d ->
            d.y += d.speed
            d.x += sin(d.y * 0.02f).toFloat() * d.wobble

            if (d.speed > 1.2f && random.nextFloat() > 0.7f) {
                trails.add(
                    Trail(
                        x = d.x + (random.nextFloat() - 0.5f) * 2,
                        y = d.y - d.radius,
                        radius = d.radius * 0.4f,
                        opacity = d.opacity * 0.5f
                    )
                )
            }

            if (d.y - d.radius > h) {
                d.y = -d.radius
                d.x = random.nextFloat() * w
                d.speed = random.nextFloat() * 1.5f + 0.3f
            }
        }

        trails.forEach { it.opacity -= 0.01f }
        trails.removeAll { it.opacity <= 0 }

        if (trails.size > 150) {
            trails.subList(0, trails.size - 150).clear()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}

data class Droplet(
    var x: Float,
    var y: Float,
    val radius: Float,
    var speed: Float,
    val opacity: Float,
    val wobble: Float
)

data class Trail(
    val x: Float,
    val y: Float,
    val radius: Float,
    var opacity: Float
)
