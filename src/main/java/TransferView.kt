package com.ps4send.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

class TransferView(context: Context) : View(context) {

    companion object {
        const val IDLE = 0
        const val SENDING = 1
        const val SUCCESS = 2
        const val ERROR = 3
    }

    private var mode = IDLE
    private var phase = 0f
    private var ink = Color.BLACK
    private val green = Color.parseColor("#2FBF71")
    private val red = Color.parseColor("#E5484D")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setMode(m: Int) {
        mode = m
        if (m == SENDING) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
        invalidate()
    }

    fun setInk(color: Int) {
        ink = color
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (96 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(100, widthMeasureSpec), h)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val cy = height / 2f
        val px = w * 0.2f
        val cx = w * 0.8f
        val mx = w * 0.5f

        // Móvil
        paint.style = Paint.Style.STROKE
        paint.color = ink
        paint.alpha = 255
        paint.strokeWidth = 2.4f * d
        c.drawRoundRect(RectF(px - 14 * d, cy - 24 * d, px + 14 * d, cy + 24 * d), 5 * d, 5 * d, paint)
        paint.strokeWidth = 1.6f * d
        c.drawRoundRect(RectF(px - 10.5f * d, cy - 18 * d, px + 10.5f * d, cy + 12 * d), 1.5f * d, 1.5f * d, paint)
        c.drawCircle(px, cy + 18 * d, 1.6f * d, paint)

        // Consola
        val body = RectF(cx - 17 * d, cy - 22 * d, cx + 17 * d, cy + 22 * d)
        if (mode == SUCCESS) {
            paint.style = Paint.Style.FILL
            paint.color = green
            paint.alpha = 60
            c.drawRoundRect(body, 4 * d, 4 * d, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.color = if (mode == SUCCESS) green else ink
        paint.alpha = 255
        paint.strokeWidth = 2.4f * d
        c.drawRoundRect(body, 4 * d, 4 * d, paint)
        paint.strokeWidth = 1.6f * d
        c.drawLine(cx - 11 * d, cy - 19 * d, cx - 11 * d, cy + 19 * d, paint)
        c.drawLine(cx + 3 * d, cy - 8 * d, cx + 11 * d, cy - 8 * d, paint)
        c.drawLine(cx + 3 * d, cy - 3 * d, cx + 11 * d, cy - 3 * d, paint)

        // Zona central
        val x0 = px + 24 * d
        val x1 = cx - 26 * d
        paint.strokeWidth = 2.4f * d
        when (mode) {
            SENDING -> {
                for (i in 0..2) {
                    val t = (phase + i / 3f) % 1f
                    val x = x0 + (x1 - x0) * t
                    paint.color = ink
                    paint.alpha = (255 * Math.sin(Math.PI * t)).toInt().coerceIn(0, 255)
                    c.drawArc(RectF(x - 9 * d, cy - 9 * d, x + 9 * d, cy + 9 * d), -45f, 90f, false, paint)
                }
            }
            SUCCESS -> {
                val check = Path()
                check.moveTo(mx - 10 * d, cy)
                check.lineTo(mx - 3 * d, cy + 8 * d)
                check.lineTo(mx + 11 * d, cy - 9 * d)
                paint.color = green
                paint.alpha = 255
                paint.strokeWidth = 4 * d
                c.drawPath(check, paint)
            }
            ERROR -> {
                paint.color = red
                paint.alpha = 255
                paint.strokeWidth = 4 * d
                c.drawLine(mx - 9 * d, cy - 9 * d, mx + 9 * d, cy + 9 * d, paint)
                c.drawLine(mx + 9 * d, cy - 9 * d, mx - 9 * d, cy + 9 * d, paint)
            }
            else -> {
                for (i in 0..2) {
                    val x = x0 + (x1 - x0) * (i + 0.5f) / 3f
                    paint.color = ink
                    paint.alpha = 90
                    c.drawArc(RectF(x - 9 * d, cy - 9 * d, x + 9 * d, cy + 9 * d), -45f, 90f, false, paint)
                }
            }
        }
    }
}
