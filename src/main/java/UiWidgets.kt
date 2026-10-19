package com.ps4send.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
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
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 14), dp(ctx, 13), dp(ctx, 14), dp(ctx, 13))
            setOnClickListener { onClick() }
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
        root.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(ctx, 8) })
        val b = UiBtn(root, icon, tv)
        applyStyle(ctx, b, styleId)
        return b
    }

    fun applyStyle(ctx: Context, b: UiBtn, styleId: Int) {
        val fg: Int
        when (styleId) {
            0 -> {
                b.root.background = Ui.box(ctx, Ui.BLUE, 14f)
                fg = Color.WHITE
            }
            1 -> {
                b.root.background = Ui.box(ctx, Color.TRANSPARENT, 14f, Ui.BLUE_SOFT, 1.5f)
                fg = Ui.TEXT
            }
            else -> {
                b.root.background = Ui.box(ctx, Ui.GRAY_BTN, 14f)
                fg = Ui.GRAY_TXT
            }
        }
        b.label.setTextColor(fg)
        b.icon.setTint(if (styleId == 1) Ui.BLUE_SOFT else fg)
    }

    fun tab(ctx: Context, caption: String, kind: Int, onClick: () -> Unit): UiBtn {
        val b = button(ctx, caption, kind, 2, onClick)
        b.root.setPadding(dp(ctx, 8), dp(ctx, 11), dp(ctx, 8), dp(ctx, 11))
        b.label.textSize = 13f
        return b
    }

    fun styleTab(ctx: Context, b: UiBtn, active: Boolean) {
        if (active) {
            b.root.background = Ui.box(ctx, Ui.BLUE, 11f)
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

    @Volatile
    var ftpPort: Int = 2121

    @Volatile
    private var polling = false

    private val dot = View(ctx)
    private val statusText = W.label(ctx, "", 12f, Ui.GRAY_TXT)
    private val ipText = W.label(ctx, "", 12f, Ui.MUTED)
    val view = LinearLayout(ctx)

    init {
        dot.background = Ui.box(ctx, Ui.GRAY_TXT, 4f)
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(W.label(ctx, "PS4", 15f).apply { typeface = Typeface.DEFAULT_BOLD })
        titleRow.addView(
            dot,
            LinearLayout.LayoutParams(W.dp(ctx, 8), W.dp(ctx, 8)).apply {
                marginStart = W.dp(ctx, 8)
                marginEnd = W.dp(ctx, 6)
            }
        )
        titleRow.addView(statusText)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(titleRow)
        col.addView(ipText)
        val copy = IconView(ctx, IconView.COPY).apply { setOnClickListener { onCopy() } }
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        view.background = Ui.box(ctx, Ui.CARD, 14f, Ui.LINE, 1f)
        view.setPadding(W.dp(ctx, 12), W.dp(ctx, 12), W.dp(ctx, 12), W.dp(ctx, 12))
        view.setOnClickListener { onEdit() }
        view.addView(IconView(ctx, IconView.CONSOLE), LinearLayout.LayoutParams(W.dp(ctx, 46), W.dp(ctx, 30)))
        view.addView(
            col,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = W.dp(ctx, 12) }
        )
        view.addView(copy, LinearLayout.LayoutParams(W.dp(ctx, 28), W.dp(ctx, 28)))
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

