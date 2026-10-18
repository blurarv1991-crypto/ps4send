package com.ps4send.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import kotlin.concurrent.thread

class FtpParams(val ip: String, val port: Int, val user: String, val pass: String)

class FtpEntry(val name: String, val dir: Boolean, val size: Long, val parent: Boolean = false)

object FtpUtil {

    fun <T> session(p: FtpParams, block: (FTPClient) -> T): T {
        val ftp = FTPClient()
        try {
            ftp.connectTimeout = 8000
            ftp.defaultTimeout = 30000
            ftp.connect(p.ip, p.port)
            if (!ftp.login(p.user, p.pass)) {
                throw Exception("Login rechazado: ${ftp.replyString.trim()}")
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            return block(ftp)
        } finally {
            try {
                if (ftp.isConnected) {
                    ftp.logout()
                    ftp.disconnect()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun ensureDir(ftp: FTPClient, path: String): Boolean {
        if (ftp.changeWorkingDirectory(path)) return true
        var cur = if (path.startsWith("/")) "/" else ""
        for (part in path.split("/").filter { it.isNotEmpty() }) {
            cur += "$part/"
            if (!ftp.changeWorkingDirectory(cur)) {
                ftp.makeDirectory(cur)
                if (!ftp.changeWorkingDirectory(cur)) return false
            }
        }
        return true
    }

    fun normalizePath(raw: String): String {
        var s = raw.trim().ifEmpty { "/" }
        if (!s.startsWith("/")) s = "/$s"
        while (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    fun parentOf(p: String): String =
        if (p == "/") "/" else p.substringBeforeLast('/').ifEmpty { "/" }

    fun childPath(p: String, name: String): String =
        if (p == "/") "/$name" else "$p/$name"

    fun sizeText(b: Long): String = when {
        b >= 1073741824L -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1048576L -> String.format("%.1f MB", b / 1048576.0)
        b >= 1024L -> "${b / 1024} KB"
        else -> "$b B"
    }

    fun friendly(e: Exception): String {
        val m = e.toString()
        return when {
            m.contains("ECONNREFUSED") || m.contains("refused", ignoreCase = true) ->
                "El servidor FTP no está activo en la PS4, o el puerto es otro. Actívalo en GoldHEN y revisa el puerto."
            m.contains("timed out", ignoreCase = true) || m.contains("ETIMEDOUT") ->
                "La PS4 no responde. Revisa la IP y que móvil y consola estén en la misma Wi-Fi."
            m.contains("UnknownHost") || m.contains("Unable to resolve") ->
                "La IP no es válida."
            m.contains("Login rechazado") ->
                "El servidor FTP rechazó el usuario o la contraseña."
            else -> m
        }
    }
}

class FtpBrowser(
    private val act: Activity,
    private val ui: Handler,
    private val log: (String) -> Unit
) {

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun themeId() = android.R.style.Theme_DeviceDefault_Dialog_Alert

    private fun input(hint: String): EditText = EditText(act).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.GRAY_TXT)
        background = Ui.box(act, Ui.CARD, 10f, Ui.LINE, 1f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    fun show(p: FtpParams, startPath: String, onChoose: (String) -> Unit) {
        var path = FtpUtil.normalizePath(startPath)
        val entries = mutableListOf<FtpEntry>()
        val labels = mutableListOf<String>()

        val pathText = TextView(act).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val statusLine = TextView(act).apply {
            textSize = 12f
            setTextColor(Ui.MUTED)
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        val adapter = object : ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Ui.TEXT)
                v.textSize = 15f
                return v
            }
        }
        val list = ListView(act)
        list.adapter = adapter
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(pathText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(statusLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, dp(320)))

        fun load(target: String, fallback: Boolean) {
            statusLine.text = "Cargando..."
            thread {
                try {
                    val files = FtpUtil.session(p) { ftp ->
                        if (target != "/" && !ftp.changeWorkingDirectory(target)) {
                            throw Exception("No se puede abrir $target")
                        }
                        ftp.listFiles(target).filterNotNull().filter { it.name != "." && it.name != ".." }
                    }
                    val sorted = files.sortedWith(compareBy<FTPFile>({ !it.isDirectory }, { it.name.lowercase() }))
                    ui.post {
                        path = target
                        entries.clear()
                        labels.clear()
                        if (target != "/") entries.add(FtpEntry("..", true, 0L, true))
                        for (f in sorted) entries.add(FtpEntry(f.name, f.isDirectory, f.size))
                        for (e in entries) {
                            labels.add(
                                when {
                                    e.parent -> ".. (subir un nivel)"
                                    e.dir -> "📁 ${e.name}"
                                    else -> "${e.name}  (${FtpUtil.sizeText(e.size)})"
                                }
                            )
                        }
                        adapter.notifyDataSetChanged()
                        pathText.text = target
                        statusLine.text = if (sorted.isEmpty()) "Carpeta vacía" else "${sorted.size} elementos"
                    }
                } catch (e: Exception) {
                    if (fallback && target != "/") {
                        load("/", false)
                    } else {
                        ui.post { statusLine.text = FtpUtil.friendly(e) }
                    }
                }
            }
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val e = entries[pos]
            when {
                e.parent -> load(FtpUtil.parentOf(path), false)
                e.dir -> load(FtpUtil.childPath(path, e.name), false)
                else -> Toast.makeText(act, "Mantén pulsado para eliminar", Toast.LENGTH_SHORT).show()
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = entries[pos]
            if (!e.parent) confirmDelete(p, path, e) { load(path, false) }
            true
        }

        val dialog = AlertDialog.Builder(act, themeId())
            .setTitle("Carpetas de la PS4")
            .setView(box)
            .setPositiveButton("Usar esta carpeta") { _, _ ->
                onChoose(if (path.endsWith("/")) path else "$path/")
            }
            .setNeutralButton("Carpeta nueva", null)
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            askNewFolder(p, path) { load(path, false) }
        }
        load(path, true)
    }

    private fun confirmDelete(p: FtpParams, dir: String, e: FtpEntry, done: () -> Unit) {
        val full = FtpUtil.childPath(dir, e.name)
        val safe = full.startsWith("/data/") || full.startsWith("/mnt/usb")
        val msg = (if (e.dir) "Carpeta (solo se borra si está vacía):\n$full" else "Archivo:\n$full") +
            (if (safe) "" else "\n\nCuidado: esta ruta no es /data ni un USB. Borrar aquí puede dañar la consola.")
        AlertDialog.Builder(act, themeId())
            .setTitle("¿Eliminar?")
            .setMessage(msg)
            .setPositiveButton("Eliminar") { _, _ ->
                thread {
                    try {
                        val ok = FtpUtil.session(p) { ftp ->
                            if (e.dir) ftp.removeDirectory(full) else ftp.deleteFile(full)
                        }
                        if (ok) {
                            log("Eliminado: $full")
                        } else {
                            log("No se pudo eliminar $full (¿carpeta con contenido o sin permiso?)")
                        }
                    } catch (ex: Exception) {
                        log("Error al eliminar: ${FtpUtil.friendly(ex)}")
                    }
                    ui.post { done() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askNewFolder(p: FtpParams, dir: String, done: () -> Unit) {
        val field = input("Nombre de la carpeta")
        val holder = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        holder.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        AlertDialog.Builder(act, themeId())
            .setTitle("Carpeta nueva en $dir")
            .setView(holder)
            .setPositiveButton("Crear") { _, _ ->
                val name = field.text.toString().trim().replace("/", "")
                if (name.isNotEmpty()) {
                    thread {
                        try {
                            val target = FtpUtil.childPath(dir, name)
                            val ok = FtpUtil.session(p) { ftp -> ftp.makeDirectory(target) }
                            log(if (ok) "Carpeta creada: $target" else "No se pudo crear la carpeta $target")
                        } catch (ex: Exception) {
                            log("Error al crear carpeta: ${FtpUtil.friendly(ex)}")
                        }
                        ui.post { done() }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
