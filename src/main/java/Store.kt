package com.ps4send.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* Tienda: lista de enlaces directos a .pkg, con nombre real y portada leídos del propio archivo */
class StoreScreen(
    private val act: Activity,
    private val ui: Handler,
    private val onDirect: (String) -> Unit,
    private val onBridge: (String) -> Unit
) {

    private class Item(val url: String) {
        var title = ""
        var tid = ""
        var size = -1L
        var cover = ""
        var state = 0 // 0 = leyendo, 1 = listo, 2 = error
        var err = ""
    }

    private val prefs = act.getSharedPreferences("ps4send", Context.MODE_PRIVATE)
    private val items = ArrayList<Item>()
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val bmps = HashMap<String, Bitmap?>()
    private var dlg: Dialog? = null
    private var list: LinearLayout? = null
    private val dlgTheme = android.R.style.Theme_DeviceDefault_Dialog_Alert

    init {
        load()
        for (item in items) {
            if (item.state == 0) fetch(item)
        }
    }

    private fun dp(v: Int) = W.dp(act, v)

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("storeItems", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val item = Item(o.getString("url"))
                item.title = o.optString("title", "")
                item.tid = o.optString("tid", "")
                item.size = o.optLong("size", -1L)
                item.cover = o.optString("cover", "")
                item.state = o.optInt("state", 0)
                item.err = o.optString("err", "")
                items.add(item)
            }
        } catch (e: Exception) {
        }
    }

    private fun save() {
        val a = JSONArray()
        for (x in items) {
            a.put(
                JSONObject()
                    .put("url", x.url)
                    .put("title", x.title)
                    .put("tid", x.tid)
                    .put("size", x.size)
                    .put("cover", x.cover)
                    .put("state", x.state)
                    .put("err", x.err)
            )
        }
        prefs.edit().putString("storeItems", a.toString()).apply()
    }

    /* Lee nombre, ID y portada del .pkg en segundo plano */
    private fun fetch(item: Item) {
        item.state = 0
        item.err = ""
        pool.execute {
            try {
                val info = PkgInfo.read(item.url)
                var cover = ""
                val ic = info.icon
                if (ic != null) {
                    val f = File(act.filesDir, "cover_" + Integer.toHexString(item.url.hashCode()) + ".png")
                    f.writeBytes(ic)
                    cover = f.name
                }
                ui.post {
                    bmps.remove(cover)
                    item.title = info.title
                    item.tid = info.titleId
                    item.size = info.size
                    item.cover = cover
                    item.state = 1
                    item.err = ""
                    save()
                    refresh()
                }
            } catch (e: Exception) {
                ui.post {
                    item.state = 2
                    item.err = e.message ?: "error"
                    save()
                    refresh()
                }
            }
        }
    }

    private fun name(item: Item): String {
        if (item.title.isNotEmpty()) return item.title
        return try {
            java.net.URLDecoder.decode(item.url.substringBefore('?').substringAfterLast('/'), "UTF-8")
        } catch (e: Exception) {
            item.url
        }
    }

    private fun cover(file: String): Bitmap? {
        if (file.isEmpty()) return null
        if (bmps.containsKey(file)) return bmps[file]
        val b = try {
            val o = BitmapFactory.Options()
            o.inSampleSize = 2
            BitmapFactory.decodeFile(File(act.filesDir, file).absolutePath, o)
        } catch (e: Exception) {
            null
        }
        bmps[file] = b
        return b
    }

    fun show() {
        val d = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pageBg()
            setPadding(dp(16), dp(28), dp(16), dp(8))
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(act).apply {
                text = "Tienda"
                textSize = 24f
                setTextColor(Ui.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        head.addView(
            TextView(act).apply {
                text = "Cerrar"
                textSize = 15f
                setTextColor(Ui.BLUE_LIGHT)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { d.dismiss() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        root.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(
            W.centered(act, "Agrega enlaces directos de .pkg. Toca una tarjeta para descargarla en la PS4.", 13f, Ui.MUTED),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        )
        val add = W.button(act, "Agregar enlaces", IconView.FOLDER_OPEN, 0) { askUrls() }
        root.addView(add.root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        val lst = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(act).apply {
            addView(lst)
            isVerticalScrollBarEnabled = false
        }
        root.addView(sc, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.statusBarColor = Ui.BG
        d.window?.navigationBarColor = Ui.BG
        d.setOnDismissListener {
            dlg = null
            list = null
        }
        dlg = d
        list = lst
        refresh()
        d.show()
    }

    private fun refresh() {
        val l = list ?: return
        l.removeAllViews()
        if (items.isEmpty()) {
            l.addView(
                W.centered(act, "Todavía no hay enlaces. Toca \"Agregar enlaces\".", 14f, Ui.MUTED),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) }
            )
            return
        }
        for (item in items) {
            l.addView(card(item), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun card(item: Item): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.gradBox(act, Ui.CARD_TOP, Ui.CARD, 16f, Ui.LINE, 1f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { options(item) }
        }
        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.box(act, Ui.CARD_TOP, 12f, Ui.LINE, 1f)
        }
        val bm = cover(item.cover)
        if (bm != null) img.setImageBitmap(bm)
        row.addView(img, LinearLayout.LayoutParams(dp(72), dp(72)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            TextView(act).apply {
                text = name(item)
                textSize = 15f
                setTextColor(Ui.TEXT)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        col.addView(
            TextView(act).apply {
                textSize = 12f
                if (item.state == 0) {
                    text = "Leyendo datos..."
                    setTextColor(Ui.MUTED)
                } else if (item.state == 2) {
                    text = "No pude leer los datos: ${item.err}"
                    setTextColor(Ui.RED)
                } else {
                    val parts = ArrayList<String>()
                    if (item.tid.isNotEmpty()) parts.add(item.tid)
                    if (item.size > 0L) parts.add(FtpUtil.sizeText(item.size))
                    text = parts.joinToString(" · ")
                    setTextColor(Ui.MUTED)
                }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) }
        )
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        return row
    }

    private fun options(item: Item) {
        val ops = arrayOf(
            "Descargar en la PS4 (directo, enlaces http)",
            "Descargar en la PS4 por el celular (puente)",
            "Actualizar datos",
            "Copiar enlace",
            "Quitar de la lista"
        )
        AlertDialog.Builder(act, dlgTheme)
            .setTitle(name(item))
            .setItems(ops) { _, which ->
                when (which) {
                    0 -> {
                        dlg?.dismiss()
                        onDirect(item.url)
                    }
                    1 -> {
                        dlg?.dismiss()
                        onBridge(item.url)
                    }
                    2 -> {
                        fetch(item)
                        save()
                        refresh()
                    }
                    3 -> {
                        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("enlace", item.url))
                        Toast.makeText(act, "Enlace copiado", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        items.remove(item)
                        if (item.cover.isNotEmpty()) {
                            File(act.filesDir, item.cover).delete()
                            bmps.remove(item.cover)
                        }
                        save()
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun askUrls() {
        var ini = ""
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pegado = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (pegado.contains("http://") || pegado.contains("https://")) ini = pegado
        } catch (e: Exception) {
        }
        val input = W.field(
            act, "Un enlace por línea", ini,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        )
        input.setSingleLine(false)
        input.minLines = 4
        input.maxLines = 8
        input.gravity = Gravity.TOP or Gravity.START
        val holder = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        holder.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        AlertDialog.Builder(act, dlgTheme)
            .setTitle("Agregar enlaces")
            .setView(holder)
            .setPositiveButton("Agregar") { _, _ -> addUrls(input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addUrls(raw: String) {
        val found = raw.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        var added = 0
        for (u in found) {
            if (items.any { it.url == u }) continue
            val item = Item(u)
            items.add(added, item)
            fetch(item)
            added++
        }
        if (added == 0) {
            Toast.makeText(act, "No encontré enlaces nuevos (deben empezar con http:// o https://)", Toast.LENGTH_LONG).show()
        }
        save()
        refresh()
    }
}
