package com.autotv.player.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.autotv.player.player.PlayerHub

/** Barra de progresso do vídeo. Um toque em qualquer ponto pula para aquele momento. */
class SeekView(context: Context) : View(context) {
    var progress = 0f
    var buffered = 0f
    var onUserAction: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val padX = 12 * d
        val trackH = 6 * d
        val cy = h / 2
        val left = padX
        val right = w - padX
        fun bar(from: Float, to: Float, color: Int) {
            paint.color = color
            rect.set(from, cy - trackH / 2, to, cy + trackH / 2)
            canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
        }
        bar(left, right, 0x44FFFFFF)
        bar(left, left + (right - left) * buffered.coerceIn(0f, 1f), 0x88FFFFFF)
        val x = left + (right - left) * progress.coerceIn(0f, 1f)
        bar(left, x, 0xFFFF2D55.toInt())
        paint.color = 0xFFFF2D55.toInt()
        canvas.drawCircle(x, cy, 9 * d, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val dur = PlayerHub.durationMs()
                if (dur > 0 && !PlayerHub.liveNow()) {
                    val padX = 12 * d
                    val f = ((e.x - padX) / (width - 2 * padX)).coerceIn(0f, 1f)
                    PlayerHub.seekTo((f * dur).toLong())
                    progress = f
                    invalidate()
                }
                onUserAction()
                return true
            }
        }
        return true
    }
}

/** Barra vertical de brilho do vídeo (toque na altura desejada: em cima = mais claro). */
class BrightnessView(context: Context) : View(context) {
    var level = 1f
        set(v) { field = v.coerceIn(0.15f, 1f); invalidate() }
    var onLevelChanged: (Float) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val barW = 10 * d
        val cx = w / 2
        val top = 8 * d
        val bottom = h - 8 * d
        paint.color = 0x44FFFFFF
        rect.set(cx - barW / 2, top, cx + barW / 2, bottom)
        canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
        val frac = (level - 0.15f) / 0.85f
        val y = bottom - (bottom - top) * frac
        paint.color = 0xFFFFD54F.toInt()
        rect.set(cx - barW / 2, y, cx + barW / 2, bottom)
        canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
        canvas.drawCircle(cx, y, 12 * d, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val top = 8 * d
                val bottom = height - 8 * d
                val frac = 1f - ((e.y - top) / (bottom - top)).coerceIn(0f, 1f)
                level = 0.15f + frac * 0.85f
                onLevelChanged(level)
                return true
            }
        }
        return true
    }
}
