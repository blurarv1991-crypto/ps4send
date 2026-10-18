package com.ps4send.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View

object Ui {
    val BG = Color.BLACK
    val CARD = Color.parseColor("#0B1020")
    val LINE = Color.parseColor("#1C2542")
    val BLUE = Color.parseColor("#1F5FFF")
    val BLUE_SOFT = Color.parseColor("#2F6BFF")
    val TEXT = Color.WHITE
    val MUTED = Color.parseColor("#8FA3D9")
    val GREEN = Color.parseColor("#22C55E")
    val AMBER = Color.parseColor("#F5A524")
    val RED = Color.parseColor("#E5484D")
    val GRAY_BTN = Color.parseColor("#121829")
    val GRAY_TXT = Color.parseColor("#55608A")
    val INK = Color.parseColor("#8FA3FF")

    fun box(
        ctx: Context,
        fill: Int,
        radiusDp: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokeDp: Float = 0f
    ): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * d
            if (strokeDp > 0f) setStroke((strokeDp * d).toInt().coerceAtLeast(1), strokeColor)
        }
    }
}

class BadgeTitleView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BLUE }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f * d
    }

    private val letters = Path().apply {
        moveTo(7f, 15f)
        lineTo(7f, 5f)
        lineTo(11f, 5f)
        arcTo(RectF(8f, 5f, 14f, 11f), -90f, 180f)
        lineTo(7f, 11f)
        moveTo(24f, 6.5f)
        cubicTo(23.5f, 5f, 22.5f, 5f, 20.5f, 5f)
        cubicTo(18.5f, 5f, 17.5f, 6f, 17.5f, 7.5f)
        cubicTo(17.5f, 9f, 18.5f, 10f, 20.5f, 10f)
        cubicTo(22.5f, 10f, 23.5f, 11f, 23.5f, 12.5f)
        cubicTo(23.5f, 14f, 22.5f, 15f, 20.5f, 15f)
        cubicTo(18.5f, 15f, 17.5f, 14.5f, 17f, 13.5f)
        moveTo(35f, 5f)
        lineTo(29.5f, 12f)
        lineTo(38f, 12f)
        moveTo(35f, 5f)
        lineTo(35f, 15f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = 28f * d / 20f
        val total = (46f * s + 10f * d + txt.measureText("Send")).toInt()
        setMeasuredDimension(total, (34f * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = 28f * d / 20f
        val top = (height - 28f * d) / 2f
        c.save()
        c.translate(0f, top)
        c.scale(s, s)
        c.drawRoundRect(RectF(0f, 0f, 46f, 20f), 6f, 6f, fill)
        c.drawPath(letters, line)
        c.restore()
        val base = height / 2f - (txt.descent() + txt.ascent()) / 2f
        c.drawText("Send", 46f * s + 10f * d, base, txt)
    }
}

class IconView(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val CONSOLE = 0
        const val COPY = 1
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.INK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        p.strokeWidth = 1.6f * d
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            CONSOLE -> {
                c.drawRoundRect(RectF(2f * d, h * 0.2f, w - 2f * d, h * 0.8f), 4f * d, 4f * d, p)
                c.drawLine(w * 0.17f, h * 0.3f, w * 0.17f, h * 0.7f, p)
                c.drawLine(w * 0.55f, h * 0.62f, w * 0.82f, h * 0.62f, p)
            }
            COPY -> {
                val s = minOf(w, h)
                val o = (w - s) / 2f
                c.drawRoundRect(RectF(o + s * 0.32f, s * 0.12f, o + s * 0.88f, s * 0.68f), 3f * d, 3f * d, p)
                c.drawRoundRect(RectF(o + s * 0.12f, s * 0.32f, o + s * 0.68f, s * 0.88f), 3f * d, 3f * d, p)
            }
        }
    }
}
