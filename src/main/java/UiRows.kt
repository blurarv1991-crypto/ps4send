package com.ps4send.app

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

class RowView(val root: LinearLayout, val value: TextView)

object Rows {

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.box(ctx, Ui.CARD, 14f, Ui.LINE, 1f)
    }

    fun divider(ctx: Context): View = View(ctx).apply { setBackgroundColor(Ui.LINE) }

    fun row(ctx: Context, kind: Int, caption: String, value: String, onClick: () -> Unit): RowView {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(W.dp(ctx, 14), W.dp(ctx, 13), W.dp(ctx, 12), W.dp(ctx, 13))
            setOnClickListener { onClick() }
        }
        root.addView(IconView(ctx, kind), LinearLayout.LayoutParams(W.dp(ctx, 22), W.dp(ctx, 22)))
        root.addView(
            W.label(ctx, caption, 13f, Ui.MUTED),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = W.dp(ctx, 10) }
        )
        val v = W.label(ctx, value, 13f, Ui.TEXT)
        v.maxLines = 1
        v.ellipsize = TextUtils.TruncateAt.END
        root.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = W.dp(ctx, 8) })
        root.addView(
            IconView(ctx, IconView.CHEVRON_RIGHT, Ui.MUTED),
            LinearLayout.LayoutParams(W.dp(ctx, 20), W.dp(ctx, 20))
        )
        return RowView(root, v)
    }

    fun collapsible(
        ctx: Context,
        title: String,
        iconKind: Int,
        content: View,
        startOpen: Boolean = false
    ): LinearLayout {
        val down = IconView(ctx, IconView.CHEVRON_DOWN, Ui.MUTED)
        val up = IconView(ctx, IconView.CHEVRON_UP, Ui.MUTED)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(W.dp(ctx, 2), W.dp(ctx, 10), W.dp(ctx, 2), W.dp(ctx, 10))
        }
        head.addView(IconView(ctx, iconKind, Ui.INK), LinearLayout.LayoutParams(W.dp(ctx, 22), W.dp(ctx, 22)))
        head.addView(
            W.label(ctx, title, 13f, Ui.MUTED),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = W.dp(ctx, 8) }
        )
        head.addView(down, LinearLayout.LayoutParams(W.dp(ctx, 22), W.dp(ctx, 22)))
        head.addView(up, LinearLayout.LayoutParams(W.dp(ctx, 22), W.dp(ctx, 22)))

        fun setOpen(open: Boolean) {
            content.visibility = if (open) View.VISIBLE else View.GONE
            down.visibility = if (open) View.GONE else View.VISIBLE
            up.visibility = if (open) View.VISIBLE else View.GONE
        }
        setOpen(startOpen)
        head.setOnClickListener { setOpen(content.visibility != View.VISIBLE) }

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }
}
