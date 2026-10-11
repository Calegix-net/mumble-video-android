package com.calegix.mumblevideo.core.fake

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Surface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a moving test pattern into a [Surface] on its own thread, standing in for the native
 * decoder. It honours the same contract the real core must: [stop] blocks until the thread has
 * released the surface.
 */
internal class TestPatternRenderer(
    private val surface: Surface,
    private val label: () -> Label?,
) {
    data class Label(
        val title: String,
        val subtitle: String,
        val screen: Boolean,
        val audio: String?,
        val waiting: Boolean,
        val seed: Int,
    )

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        typeface = Typeface.MONOSPACE
    }

    fun start() {
        running = true
        thread = Thread(::loop, "fake-video-render").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            it.join(STOP_TIMEOUT_MS)
        }
        thread = null
    }

    private fun loop() {
        val start = SystemClock.elapsedRealtime()
        var frame = 0L
        while (running) {
            val frameStart = SystemClock.elapsedRealtime()
            if (!surface.isValid) break
            val canvas = try {
                surface.lockHardwareCanvas()
            } catch (_: IllegalArgumentException) {
                break
            } catch (_: IllegalStateException) {
                break
            }
            try {
                draw(canvas, (frameStart - start) / 1000f, frame++)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            val elapsed = SystemClock.elapsedRealtime() - frameStart
            try {
                Thread.sleep((FRAME_MS - elapsed).coerceAtLeast(1))
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun draw(canvas: Canvas, t: Float, frame: Long) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val info = label()
        if (info == null || info.waiting) {
            canvas.drawColor(Color.BLACK)
            drawSpinner(canvas, w / 2, h / 2, min(w, h) / 10, t)
            return
        }
        if (info.screen) drawDesktop(canvas, w, h, t, info.seed) else drawCamera(canvas, w, h, t, info.seed)
        drawOverlay(canvas, w, h, info, frame)
    }

    /** Something camera-ish: a drifting colour field with an orbiting "face". */
    private fun drawCamera(canvas: Canvas, w: Float, h: Float, t: Float, seed: Int) {
        val hue = (seed % 360 + t * 12) % 360
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.35f)))
        val r = min(w, h) * 0.22f
        val cx = w / 2 + cos(t * 0.9f) * w * 0.18f
        val cy = h / 2 + sin(t * 1.3f) * h * 0.12f
        fill.color = Color.HSVToColor(floatArrayOf((hue + 180) % 360, 0.45f, 0.85f))
        canvas.drawCircle(cx, cy, r, fill)
        fill.color = Color.argb(200, 0, 0, 0)
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.2f, r * 0.1f, fill)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.2f, r * 0.1f, fill)
        val mouth = RectF(cx - r * 0.4f, cy + r * 0.05f, cx + r * 0.4f, cy + r * 0.5f)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = r * 0.07f
        canvas.drawArc(mouth, 20f, 140f, false, fill)
        fill.style = Paint.Style.FILL
        drawBars(canvas, w, h * 0.92f, h)
    }

    /** Something screen-ish: a desktop with windows and a wandering cursor. */
    private fun drawDesktop(canvas: Canvas, w: Float, h: Float, t: Float, seed: Int) {
        canvas.drawColor(Color.rgb(24, 30, 48))
        val windows = 3
        for (i in 0 until windows) {
            val ox = ((seed + i * 97) % 40) / 100f
            val left = w * (0.05f + ox + i * 0.12f)
            val top = h * (0.1f + i * 0.15f)
            val rect = RectF(left, top, left + w * 0.5f, top + h * 0.45f)
            fill.color = Color.rgb(236 - i * 20, 238 - i * 20, 242 - i * 20)
            canvas.drawRoundRect(rect, 12f, 12f, fill)
            fill.color = Color.rgb(60 + i * 40, 90, 160)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.04f, fill)
            fill.color = Color.rgb(150, 150, 160)
            for (line in 0 until 6) {
                val lw = rect.width() * (0.4f + ((line * 31 + i * 17 + (t * 2).toInt()) % 50) / 100f)
                val y = rect.top + h * 0.07f + line * h * 0.05f
                canvas.drawRect(rect.left + 16, y, rect.left + 16 + lw * 0.8f, y + h * 0.018f, fill)
            }
        }
        val cx = w * (0.5f + 0.35f * sin(t * 0.7f))
        val cy = h * (0.5f + 0.3f * cos(t * 0.5f))
        fill.color = Color.WHITE
        canvas.drawCircle(cx, cy, min(w, h) * 0.015f, fill)
    }

    private fun drawBars(canvas: Canvas, w: Float, top: Float, bottom: Float) {
        val colors = intArrayOf(
            Color.rgb(192, 192, 192),
            Color.rgb(192, 192, 0),
            Color.rgb(0, 192, 192),
            Color.rgb(0, 192, 0),
            Color.rgb(192, 0, 192),
            Color.rgb(192, 0, 0),
            Color.rgb(0, 0, 192),
        )
        val bw = w / colors.size
        colors.forEachIndexed { i, c ->
            fill.color = c
            canvas.drawRect(i * bw, top, (i + 1) * bw, bottom, fill)
        }
    }

    private fun drawOverlay(canvas: Canvas, w: Float, h: Float, info: Label, frame: Long) {
        val unit = min(w, h) / 18
        text.textSize = unit
        small.textSize = unit * 0.55f
        fill.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, unit * 2.6f, fill)
        canvas.drawText(info.title, unit * 0.6f, unit * 1.2f, text)
        canvas.drawText(info.subtitle, unit * 0.6f, unit * 2.1f, small)
        val stamp = "frame $frame" + (info.audio?.let { "  ·  $it" } ?: "")
        canvas.drawText(stamp, unit * 0.6f, h - unit * 0.6f - if (info.screen) 0f else h * 0.08f, small)
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = r / 5
        fill.color = Color.argb(200, 255, 255, 255)
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), (t * 360) % 360, 270f, false, fill)
        fill.style = Paint.Style.FILL
    }

    private companion object {
        const val FRAME_MS = 33L
        const val STOP_TIMEOUT_MS = 500L
    }
}
