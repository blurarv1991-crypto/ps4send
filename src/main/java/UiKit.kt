package com.ps4send.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View

object Ui {
    val BG = Color.parseColor("#070F26")
    val CARD = Color.parseColor("#0A1226")
    val CARD_TOP = Color.parseColor("#0F1B3F")
    val LINE = Color.parseColor("#1E3566")
    val BLUE = Color.parseColor("#2563FF")
    val BLUE_SOFT = Color.parseColor("#3D7BFF")
    val BLUE_LIGHT = Color.parseColor("#5B9BFF")
    val BLUE_DEEP = Color.parseColor("#1744C9")
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

    /** Caja con degradado vertical (de arriba hacia abajo). */
    fun gradBox(
        ctx: Context,
        top: Int,
        bottom: Int,
        radiusDp: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokeDp: Float = 0f
    ): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusDp * d
            if (strokeDp > 0f) setStroke((strokeDp * d).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    /** Circulo con borde. */
    fun circle(ctx: Context, fill: Int, strokeColor: Int, strokeDp: Float): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke((strokeDp * d).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    /** Fondo de toda la pantalla: azul noche que se oscurece hacia abajo. */
    fun pageBg(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#070F26"), Color.parseColor("#050B1E"), Color.parseColor("#02040C"))
        )
}

class BadgeTitleView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val circleD = 64f * d
    private val margin = 6f * d
    private val gap = 14f * d

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A1636") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.BLUE_SOFT
        strokeWidth = 2.5f * d
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }

    private val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.BLUE_LIGHT
        textSize = 30f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }

    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textSize = 13f * d
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

    private val line1 = "Envía archivos fácilmente"
    private val line2 = "a tu PS4"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val titleW = t1.measureText("PS4 ") + t2.measureText("Send")
        val textW = maxOf(titleW, sub.measureText(line1))
        val total = (margin * 2f + circleD + gap + textW).toInt()
        setMeasuredDimension(total, (80f * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = margin + circleD / 2f
        val cy = height / 2f
        val r = circleD / 2f

        glow.shader = RadialGradient(
            cx, cy, r + margin,
            intArrayOf(Color.parseColor("#663D7BFF"), Color.parseColor("#003D7BFF")),
            floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r + margin, glow)
        c.drawCircle(cx, cy, r, fillP)
        c.drawCircle(cx, cy, r - 1.2f * d, ring)

        val s = (circleD * 0.56f) / 31f
        c.save()
        c.translate(cx - 22.5f * s, cy - 10f * s)
        c.scale(s, s)
        c.drawPath(letters, line)
        c.restore()

        val x = margin * 2f + circleD + gap - margin
        val y1 = 36f * d
        c.drawText("PS4 ", x, y1, t1)
        c.drawText("Send", x + t1.measureText("PS4 "), y1, t2)
        c.drawText(line1, x, y1 + 20f * d, sub)
        c.drawText(line2, x, y1 + 37f * d, sub)
    }
}

class IconView(context: Context, private val kind: Int, private var tint: Int = Ui.INK) : View(context) {

    companion object {
        const val CONSOLE = 0
        const val COPY = 1
        const val CUBE = 2
        const val FOLDER_OPEN = 3
        const val FOLDER_UP = 4
        const val SEND = 5
        const val INFO = 6
        const val CHEVRON_DOWN = 7
                const val CHEVRON_UP = 8
        const val GEAR = 9
        const val WIFI = 10
        const val PLUG = 11
        const val USER = 12
        const val LOCK = 13
        const val CHEVRON_RIGHT = 14
        const val CLOSE = 15
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    init {
        when (kind) {
            COPY -> {
                path.addRoundRect(RectF(9f, 3f, 21f, 15f), 2.5f, 2.5f, Path.Direction.CW)
                path.addRoundRect(RectF(3f, 9f, 15f, 21f), 2.5f, 2.5f, Path.Direction.CW)
            }
            CUBE -> {
                path.moveTo(12f, 2.5f)
                path.lineTo(20f, 7f)
                path.lineTo(20f, 17f)
                path.lineTo(12f, 21.5f)
                path.lineTo(4f, 17f)
                path.lineTo(4f, 7f)
                path.close()
                path.moveTo(4f, 7f)
                path.lineTo(12f, 11.5f)
                path.lineTo(20f, 7f)
                path.moveTo(12f, 11.5f)
                path.lineTo(12f, 21.5f)
            }
            FOLDER_OPEN -> {
                path.moveTo(3f, 17.5f)
                path.lineTo(3f, 6f)
                path.quadTo(3f, 5f, 4f, 5f)
                path.lineTo(9f, 5f)
                path.lineTo(11f, 7.5f)
                path.lineTo(18f, 7.5f)
                path.quadTo(19f, 7.5f, 19f, 8.5f)
                path.lineTo(19f, 10f)
                path.moveTo(5f, 19f)
                path.lineTo(7.2f, 11.5f)
                path.quadTo(7.5f, 10.5f, 8.5f, 10.5f)
                path.lineTo(21f, 10.5f)
                path.quadTo(22.2f, 10.5f, 21.9f, 11.6f)
                path.lineTo(19.8f, 18f)
                path.quadTo(19.5f, 19f, 18.5f, 19f)
                path.close()
            }
            FOLDER_UP -> {
                path.moveTo(3f, 6f)
                path.quadTo(3f, 5f, 4f, 5f)
                path.lineTo(9f, 5f)
                path.lineTo(11.5f, 8f)
                path.lineTo(20f, 8f)
                path.quadTo(21f, 8f, 21f, 9f)
                path.lineTo(21f, 18f)
                path.quadTo(21f, 19f, 20f, 19f)
                path.lineTo(4f, 19f)
                path.quadTo(3f, 19f, 3f, 18f)
                path.close()
                path.moveTo(12f, 16f)
                path.lineTo(12f, 11.5f)
                path.moveTo(9.7f, 13.7f)
                path.lineTo(12f, 11.4f)
                path.lineTo(14.3f, 13.7f)
            }
            SEND -> {
                path.moveTo(21f, 3f)
                path.lineTo(14.5f, 21f)
                path.lineTo(10.5f, 13.5f)
                path.lineTo(3f, 9.5f)
                path.close()
                path.moveTo(21f, 3f)
                path.lineTo(10.5f, 13.5f)
            }
            INFO -> {
                path.addCircle(12f, 12f, 9f, Path.Direction.CW)
                path.moveTo(12f, 11f)
                path.lineTo(12f, 16.5f)
                path.moveTo(12f, 7.6f)
                path.lineTo(12f, 7.7f)
            }
            CHEVRON_DOWN -> {
                path.moveTo(6f, 9f)
                path.lineTo(12f, 15f)
                path.lineTo(18f, 9f)
            }
                        CHEVRON_UP -> {
                path.moveTo(6f, 15f)
                path.lineTo(12f, 9f)
                path.lineTo(18f, 15f)
            }
            GEAR -> {
                path.addCircle(12f, 12f, 3f, Path.Direction.CW)
                path.addCircle(12f, 12f, 6.5f, Path.Direction.CW)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    path.moveTo(12f + 6.5f * ca, 12f + 6.5f * sa)
                    path.lineTo(12f + 9.5f * ca, 12f + 9.5f * sa)
                }
            }
            WIFI -> {
                path.addArc(RectF(8f, 15f, 16f, 23f), 225f, 90f)
                path.addArc(RectF(4f, 11f, 20f, 27f), 225f, 90f)
                path.addArc(RectF(0f, 7f, 24f, 31f), 225f, 90f)
                path.moveTo(12f, 19f)
                path.lineTo(12f, 19.1f)
            }
            PLUG -> {
                path.moveTo(9f, 2f)
                path.lineTo(9f, 7f)
                path.moveTo(15f, 2f)
                path.lineTo(15f, 7f)
                path.moveTo(6f, 7f)
                path.lineTo(18f, 7f)
                path.lineTo(18f, 11f)
                path.quadTo(18f, 16f, 12f, 16f)
                path.quadTo(6f, 16f, 6f, 11f)
                path.close()
                path.moveTo(12f, 16f)
                path.lineTo(12f, 22f)
            }
            USER -> {
                path.addCircle(12f, 8f, 4f, Path.Direction.CW)
                path.moveTo(4f, 21f)
                path.quadTo(4f, 14f, 12f, 14f)
                path.quadTo(20f, 14f, 20f, 21f)
            }
            LOCK -> {
                path.addRoundRect(RectF(5f, 11f, 19f, 21f), 2f, 2f, Path.Direction.CW)
                path.moveTo(8f, 11f)
                path.lineTo(8f, 8f)
                path.cubicTo(8f, 3f, 16f, 3f, 16f, 8f)
                path.lineTo(16f, 11f)
            }
            CHEVRON_RIGHT -> {
                path.moveTo(9f, 6f)
                path.lineTo(15f, 12f)
                path.lineTo(9f, 18f)
            }
            CLOSE -> {
                path.moveTo(6f, 6f)
                path.lineTo(18f, 18f)
                path.moveTo(18f, 6f)
                path.lineTo(6f, 18f)
            }
        }
    }

    fun setTint(color: Int) {
        tint = color
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        p.color = tint
        val w = width.toFloat()
        val h = height.toFloat()
        if (kind == CONSOLE) {
            val d = resources.displayMetrics.density
            p.strokeWidth = 1.6f * d
            c.drawRoundRect(RectF(2f * d, h * 0.2f, w - 2f * d, h * 0.8f), 4f * d, 4f * d, p)
            c.drawLine(w * 0.17f, h * 0.3f, w * 0.17f, h * 0.7f, p)
            c.drawLine(w * 0.55f, h * 0.62f, w * 0.82f, h * 0.62f, p)
            return
        }
        val s = minOf(w, h) / 24f
        c.save()
        c.translate((w - 24f * s) / 2f, (h - 24f * s) / 2f)
        c.scale(s, s)
        p.strokeWidth = 1.8f
        c.drawPath(path, p)
        c.restore()
    }
}

class FileIllustrationView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.BLUE_SOFT
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val deco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.BLUE_SOFT
        strokeWidth = 1.8f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val platform = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.BLUE_SOFT
        strokeWidth = 2.2f * d
    }

    private val platformFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#262F6BFF")
    }

    private val file = Path().apply {
        moveTo(6f, 2.5f)
        lineTo(14f, 2.5f)
        lineTo(19.5f, 8f)
        lineTo(19.5f, 20f)
        quadTo(19.5f, 21.5f, 18f, 21.5f)
        lineTo(6f, 21.5f)
        quadTo(4.5f, 21.5f, 4.5f, 20f)
        lineTo(4.5f, 4f)
        quadTo(4.5f, 2.5f, 6f, 2.5f)
        close()
        moveTo(14f, 2.5f)
        lineTo(14f, 8f)
        lineTo(19.5f, 8f)
    }

    private val cube = Path().apply {
        moveTo(12f, 10.5f)
        lineTo(15.2f, 12.3f)
        lineTo(15.2f, 16.1f)
        lineTo(12f, 17.9f)
        lineTo(8.8f, 16.1f)
        lineTo(8.8f, 12.3f)
        close()
        moveTo(8.8f, 12.3f)
        lineTo(12f, 14.1f)
        lineTo(15.2f, 12.3f)
        moveTo(12f, 14.1f)
        lineTo(12f, 17.9f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(100, widthMeasureSpec), (150f * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = 66f * d
        val r = 76f * d
        glow.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.parseColor("#443D7BFF"), Color.parseColor("#003D7BFF")),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, glow)

        // plataforma bajo el archivo
        val plat = RectF(cx - 88f * d, cy + 46f * d, cx + 88f * d, cy + 64f * d)
        c.drawOval(plat, platformFill)
        c.drawOval(plat, platform)
        val plat2 = RectF(cx - 60f * d, cy + 50f * d, cx + 60f * d, cy + 60f * d)
        c.drawOval(plat2, platform)

        // archivo con el cubo
        val s = 92f * d / 24f
        c.save()
        c.translate(cx - 12f * s, cy - 12f * s)
        c.scale(s, s)
        line.strokeWidth = 1.2f
        c.drawPath(file, line)
        c.drawPath(cube, line)
        c.restore()

        // formas de adorno (equis, cuadrado, circulo, triangulos)
        val xs = cx - 124f * d
        val ys = cy - 8f * d
        c.drawLine(xs - 8f * d, ys - 8f * d, xs + 8f * d, ys + 8f * d, deco)
        c.drawLine(xs + 8f * d, ys - 8f * d, xs - 8f * d, ys + 8f * d, deco)
        c.drawRect(cx - 98f * d, cy + 24f * d, cx - 82f * d, cy + 40f * d, deco)
        c.drawCircle(cx + 122f * d, cy + 6f * d, 8f * d, deco)
        val tri = Path().apply {
            moveTo(cx + 96f * d, cy - 54f * d)
            lineTo(cx + 106f * d, cy - 36f * d)
            lineTo(cx + 86f * d, cy - 36f * d)
            close()
        }
        c.drawPath(tri, deco)
        val tri2 = Path().apply {
            moveTo(cx + 78f * d, cy + 28f * d)
            lineTo(cx + 85f * d, cy + 40f * d)
            lineTo(cx + 71f * d, cy + 40f * d)
            close()
        }
        c.drawPath(tri2, deco)
    }
}
