package com.ps4send.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.TextUtils
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class UiBtn(val root: LinearLayout, val icon: IconView, val label: TextView)

object W {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun label(ctx: Context, t: String, size: Float = 14f, color: Int = Ui.TEXT): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
        }

    fun centered(ctx: Context, t: String, size: Float, color: Int): TextView =
        label(ctx, t, size, color).apply { gravity = Gravity.CENTER_HORIZONTAL }

    fun field(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(ctx).apply {
            this.hint = hint
            inputType = type
            setText(value)
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.GRAY_TXT)
            background = Ui.box(ctx, Ui.CARD, 10f, Ui.LINE, 1f)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }

    fun button(ctx: Context, caption: String, kind: Int, styleId: Int, onClick: () -> Unit): UiBtn {
        val icon = IconView(ctx, kind)
        val tv = TextView(ctx).apply {
            text = caption
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 15), dp(ctx, 16), dp(ctx, 15))
            setOnClickListener { onClick() }
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)))
        root.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(ctx, 10) })
        val b = UiBtn(root, icon, tv)
        applyStyle(ctx, b, styleId)
        return b
    }

    fun applyStyle(ctx: Context, b: UiBtn, styleId: Int) {
        val fg: Int
        when (styleId) {
            0 -> {
                b.root.background = Ui.gradBox(ctx, Ui.BLUE_SOFT, Ui.BLUE_DEEP, 16f, Ui.BLUE_LIGHT, 1f)
                fg = Color.WHITE
            }
            1 -> {
                b.root.background = Ui.gradBox(ctx, Color.parseColor("#0F1D47"), Color.parseColor("#091331"), 16f, Ui.BLUE_SOFT, 1.5f)
                fg = Ui.TEXT
            }
            else -> {
                b.root.background = Ui.gradBox(ctx, Color.parseColor("#141C38"), Color.parseColor("#0E1428"), 16f, Ui.LINE, 1f)
                fg = Ui.GRAY_TXT
            }
        }
        b.label.setTextColor(fg)
        b.icon.setTint(if (styleId == 1) Ui.BLUE_LIGHT else fg)
    }

    fun tab(ctx: Context, caption: String, kind: Int, onClick: () -> Unit): UiBtn {
        val b = button(ctx, caption, kind, 2, onClick)
        b.root.setPadding(dp(ctx, 8), dp(ctx, 13), dp(ctx, 8), dp(ctx, 13))
        b.label.textSize = 14f
        return b
    }

    fun styleTab(ctx: Context, b: UiBtn, active: Boolean) {
        if (active) {
            b.root.background = Ui.gradBox(ctx, Ui.BLUE_SOFT, Ui.BLUE_DEEP, 14f, Ui.BLUE_LIGHT, 1f)
            b.label.setTextColor(Color.WHITE)
            b.icon.setTint(Color.WHITE)
        } else {
            b.root.background = null
            b.label.setTextColor(Ui.MUTED)
            b.icon.setTint(Ui.INK)
        }
    }

    fun accordion(ctx: Context, steps: String): LinearLayout {
        val body = label(ctx, steps, 13f, Ui.MUTED).apply {
            visibility = View.GONE
            setPadding(dp(ctx, 30), dp(ctx, 2), dp(ctx, 4), dp(ctx, 10))
        }
        val down = IconView(ctx, IconView.CHEVRON_DOWN, Ui.MUTED)
        val up = IconView(ctx, IconView.CHEVRON_UP, Ui.MUTED).apply { visibility = View.GONE }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 2), dp(ctx, 10), dp(ctx, 2), dp(ctx, 10))
        }
        head.addView(IconView(ctx, IconView.INFO, Ui.BLUE_SOFT), LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
        head.addView(
            label(ctx, "¿Cómo funciona?", 13f, Ui.BLUE_SOFT),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 8) }
        )
        head.addView(down, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
        head.addView(up, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            down.visibility = if (open) View.GONE else View.VISIBLE
            up.visibility = if (open) View.VISIBLE else View.GONE
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(head, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
        box.addView(body, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
        return box
    }
}

class StatusCard(
    private val ctx: Context,
    private val ui: Handler,
    private val onEdit: () -> Unit,
    private val onCopy: () -> Unit
) {

        @Volatile
    var ip: String = ""
        private set

    @Volatile
    var ftpPort: Int = 2121

    @Volatile
    private var polling = false

    private val dot = View(ctx)
    private val statusText = W.label(ctx, "", 13f, Ui.GRAY_TXT)
    private val ipText = W.label(ctx, "", 14f, Ui.MUTED)
    val view = LinearLayout(ctx)

    init {
        dot.background = Ui.box(ctx, Ui.GRAY_TXT, 4f)
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(W.label(ctx, "PS4", 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        titleRow.addView(
            dot,
            LinearLayout.LayoutParams(W.dp(ctx, 9), W.dp(ctx, 9)).apply {
                marginStart = W.dp(ctx, 10)
                marginEnd = W.dp(ctx, 6)
            }
        )
        titleRow.addView(statusText)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(titleRow)
        col.addView(ipText)

        val consoleBox = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = Ui.box(ctx, Color.parseColor("#0C1530"), 12f, Ui.LINE, 1f)
        }
        consoleBox.addView(IconView(ctx, IconView.CONSOLE, Ui.BLUE_LIGHT), LinearLayout.LayoutParams(W.dp(ctx, 40), W.dp(ctx, 26)))

        val sep = View(ctx).apply { setBackgroundColor(Ui.LINE) }

        val copyBox = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = Ui.gradBox(ctx, Ui.BLUE_SOFT, Ui.BLUE_DEEP, 14f, Ui.BLUE_LIGHT, 1f)
            setOnClickListener { onCopy() }
        }
        copyBox.addView(IconView(ctx, IconView.COPY, Color.WHITE), LinearLayout.LayoutParams(W.dp(ctx, 24), W.dp(ctx, 24)))

        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        view.background = Ui.gradBox(ctx, Ui.CARD_TOP, Ui.CARD, 18f, Ui.LINE, 1f)
        view.setPadding(W.dp(ctx, 14), W.dp(ctx, 14), W.dp(ctx, 14), W.dp(ctx, 14))
        view.setOnClickListener { onEdit() }
        view.addView(consoleBox, LinearLayout.LayoutParams(W.dp(ctx, 62), W.dp(ctx, 46)))
        view.addView(
            col,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = W.dp(ctx, 14) }
        )
        view.addView(
            sep,
            LinearLayout.LayoutParams(W.dp(ctx, 1), W.dp(ctx, 40)).apply { marginEnd = W.dp(ctx, 12) }
        )
        view.addView(copyBox, LinearLayout.LayoutParams(W.dp(ctx, 46), W.dp(ctx, 46)))
    }

    fun setIp(v: String) {
        ip = v
        ipText.text = if (v.isEmpty()) "Toca para escribir la IP" else v
        setState(if (v.isEmpty()) 0 else 4)
    }

    private fun setState(state: Int) {
        val color: Int
        val t: String
        when (state) {
            1 -> {
                color = Ui.GREEN
                t = "Conectada"
            }
            2 -> {
                color = Ui.AMBER
                t = "Sin servicio"
            }
            3 -> {
                color = Ui.RED
                t = "Sin respuesta"
            }
            4 -> {
                color = Ui.MUTED
                t = "Comprobando…"
            }
            else -> {
                color = Ui.GRAY_TXT
                t = "Sin IP"
            }
        }
        (dot.background as GradientDrawable).setColor(color)
        statusText.setTextColor(color)
        statusText.text = t
    }

    fun start() {
        if (polling) return
        polling = true
        thread {
            while (polling) {
                val target = ip
                var state = 0
                if (target.isNotEmpty()) {
                    var reachable = false
                    var open = false
                    for (pt in intArrayOf(12800, ftpPort)) {
                        try {
                            Socket().use { it.connect(InetSocketAddress(target, pt), 1200) }
                            open = true
                            break
                        } catch (e: Exception) {
                            val m = e.message ?: ""
                            if (m.contains("refused", ignoreCase = true)) reachable = true
                        }
                    }
                    state = if (open) 1 else if (reachable) 2 else 3
                }
                val s = state
                ui.post { setState(s) }
                try {
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        polling = false
    }
}

