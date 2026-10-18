package com.ps4send.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.FilterInputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val PICK_PKG = 1
    private val PICK_FTP = 2
    private val PS4_PORT = 12800
    private val HTTP_PORT = 8000

    private class FtpParams(val ip: String, val port: Int, val user: String, val pass: String)

    private class FtpEntry(val name: String, val dir: Boolean, val size: Long, val parent: Boolean = false)

    private lateinit var tabPkg: TextView
    private lateinit var tabFtp: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var transfer: TransferView
    private lateinit var progressView: TextView
    private lateinit var pkgSection: LinearLayout
    private lateinit var ftpSection: LinearLayout
    private lateinit var pkgLabel: TextView
    private lateinit var ftpLabel: TextView
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var dirField: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    @Volatile
    private var ip = ""

    @Volatile
    private var polling = true

    private var pkgUri: Uri? = null
    private var pkgSize = -1L
    private var ftpUri: Uri? = null
    private var ftpName = ""
    private var ftpSize = -1L
    private var server: ServerSocket? = null
    private val served = AtomicLong(0)

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ps4send", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun wrap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }

    private fun button(label: String, kind: Int = 0, onClick: () -> Unit) = Button(this).apply {
        text = label
        setAllCaps(false)
        textSize = 14f
        setTextColor(
            when (kind) {
                0 -> Color.WHITE
                1 -> Ui.TEXT
                else -> Ui.GRAY_TXT
            }
        )
        background = when (kind) {
            0 -> Ui.box(this@MainActivity, Ui.BLUE, 14f)
            1 -> Ui.box(this@MainActivity, Color.TRANSPARENT, 14f, Ui.BLUE_SOFT, 1.5f)
            else -> Ui.box(this@MainActivity, Ui.GRAY_BTN, 14f)
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun label(t: String, size: Float = 14f, color: Int = Ui.TEXT) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
    }

    private fun field(h: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            hint = h
            inputType = type
            setText(value)
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.GRAY_TXT)
            background = Ui.box(this@MainActivity, Ui.CARD, 10f, Ui.LINE, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun tab(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, dp(11), 0, dp(11))
        setOnClickListener { onClick() }
    }

    private fun dialogTheme() = android.R.style.Theme_DeviceDefault_Dialog_Alert

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        ip = prefs.getString("ip", "") ?: ""

        val header = BadgeTitleView(this)

        statusDot = View(this).apply { background = Ui.box(this@MainActivity, Ui.GRAY_TXT, 4f) }
        statusText = label("", 12f, Ui.GRAY_TXT)
        ipText = label("", 12f, Ui.MUTED)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(label("PS4", 15f).apply { typeface = Typeface.DEFAULT_BOLD })
        titleRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginStart = dp(8)
            marginEnd = dp(6)
        })
        titleRow.addView(statusText)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(titleRow)
        col.addView(ipText)

        val copyIcon = IconView(this, IconView.COPY).apply { setOnClickListener { copyIp() } }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.box(this@MainActivity, Ui.CARD, 14f, Ui.LINE, 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { editIp() }
        }
        card.addView(IconView(this, IconView.CONSOLE), LinearLayout.LayoutParams(dp(46), dp(30)))
        card.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        card.addView(copyIcon, LinearLayout.LayoutParams(dp(28), dp(28)))

        tabPkg = tab("Instalar PKG") { showMode(false) }
        tabFtp = tab("Enviar por FTP") { showMode(true) }
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.box(this@MainActivity, Ui.CARD, 14f, Ui.LINE, 1f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        tabsRow.addView(tabPkg, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tabsRow.addView(tabFtp, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        transfer = TransferView(this).apply { setInk(Ui.INK) }
        progressView = label("", 13f, Ui.MUTED)

        val stepsPkg = "Paso 1: Carga el jailbreak y GoldHEN en la PS4.\n" +
            "Paso 2: Abre Remote Package Installer y déjala en pantalla.\n" +
            "Paso 3: Misma Wi-Fi: toca la tarjeta de arriba para poner la IP.\n" +
            "Paso 4: Elige el .pkg y toca \"Enviar a la PS4\".\n" +
            "Paso 5: Deja esta pantalla encendida hasta que la PS4 termine."

        val stepsFtp = "Paso 1: Carga el jailbreak y GoldHEN en la PS4.\n" +
            "Paso 2: Activa el servidor FTP en la PS4 y anota el puerto (suele ser 2121).\n" +
            "Paso 3: Misma Wi-Fi: toca la tarjeta de arriba para poner la IP.\n" +
            "Paso 4: Toca \"Explorar carpetas\" para elegir el destino. Mantén pulsado un elemento para eliminarlo.\n" +
            "Paso 5: Elige el archivo y toca \"Subir por FTP\".\n" +
            "Paso 6: Espera a que la animación termine en verde."

        pkgLabel = label("Ningún archivo elegido", 13f, Ui.MUTED)
        pkgSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pkgSection.addView(label("Cómo instalar un PKG", 16f).apply { typeface = Typeface.DEFAULT_BOLD }, wrap())
        pkgSection.addView(label(stepsPkg, 13f, Ui.MUTED), wrap())
        pkgSection.addView(button("1. Elegir archivo .pkg", 1) { pick(PICK_PKG) }, wrap())
        pkgSection.addView(pkgLabel, wrap())
        pkgSection.addView(button("2. Enviar a la PS4", 0) { sendPkg() }, wrap())
        pkgSection.addView(button("Parar servidor", 2) {
            stopServer()
            transfer.setMode(TransferView.IDLE)
            setProgress("")
            log("Servidor parado.")
        }, wrap())

        portField = field("Puerto", prefs.getString("port", "2121") ?: "2121", InputType.TYPE_CLASS_NUMBER)
        userField = field("Usuario", prefs.getString("user", "anonymous") ?: "anonymous")
        passField = field(
            "Contraseña (vacía si no tiene)", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        dirField = field("Carpeta de destino en la PS4", prefs.getString("dir", "/data/") ?: "/data/")
        ftpLabel = label("Ningún archivo elegido", 13f, Ui.MUTED)

        val authRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        authRow.addView(portField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        authRow.addView(userField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))

        ftpSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ftpSection.addView(label("Cómo enviar por FTP", 16f).apply { typeface = Typeface.DEFAULT_BOLD }, wrap())
        ftpSection.addView(label(stepsFtp, 13f, Ui.MUTED), wrap())
        ftpSection.addView(authRow, wrap())
        ftpSection.addView(passField, wrap())
        ftpSection.addView(dirField, wrap())
        ftpSection.addView(button("Explorar carpetas de la PS4", 1) { showBrowser() }, wrap())
        ftpSection.addView(button("1. Elegir archivo", 1) { pick(PICK_FTP) }, wrap())
        ftpSection.addView(ftpLabel, wrap())
        ftpSection.addView(button("2. Subir por FTP", 0) { uploadFtp() }, wrap())
        ftpSection.visibility = View.GONE

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(header, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
        form.addView(card, wrap())
        form.addView(tabsRow, wrap())
        form.addView(transfer, wrap())
        form.addView(progressView, wrap())
        form.addView(pkgSection, wrap())
        form.addView(ftpSection, wrap())
        val formScroll = ScrollView(this).apply {
            addView(form)
            isVerticalScrollBarEnabled = false
        }

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.MUTED)
        }
        logScroll = ScrollView(this).apply { addView(logView) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(28), dp(16), dp(8))
        }
        root.addView(formScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(110)))
        setContentView(root)

        showMode(false)
        ipText.text = if (ip.isEmpty()) "Toca para escribir la IP" else ip
        setStatus(if (ip.isEmpty()) 0 else 4)
        startPolling()

        log("Móvil y PS4 en el mismo Wi-Fi. Para instalar PKG: GoldHEN y Remote Package Installer abierta. Para FTP: servidor FTP activo en la PS4.")
    }

    override fun onDestroy() {
        polling = false
        stopServer()
        super.onDestroy()
    }

    private fun showMode(ftp: Boolean) {
        pkgSection.visibility = if (ftp) View.GONE else View.VISIBLE
        ftpSection.visibility = if (ftp) View.VISIBLE else View.GONE
        styleTab(tabPkg, !ftp)
        styleTab(tabFtp, ftp)
    }

    private fun styleTab(t: TextView, active: Boolean) {
        if (active) {
            t.background = Ui.box(this, Ui.BLUE, 11f)
            t.setTextColor(Color.WHITE)
            t.setTypeface(null, Typeface.BOLD)
        } else {
            t.background = null
            t.setTextColor(Ui.MUTED)
            t.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun setStatus(state: Int) {
        val color: Int
        val text: String
        when (state) {
            1 -> {
                color = Ui.GREEN
                text = "Conectada"
            }
            2 -> {
                color = Ui.AMBER
                text = "Sin servicio"
            }
            3 -> {
                color = Ui.RED
                text = "Sin respuesta"
            }
            4 -> {
                color = Ui.MUTED
                text = "Comprobando…"
            }
            else -> {
                color = Ui.GRAY_TXT
                text = "Sin IP"
            }
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.setTextColor(color)
        statusText.text = text
    }

    private fun startPolling() {
        thread {
            while (polling) {
                val target = ip
                var state = 0
                if (target.isNotEmpty()) {
                    val ftpPort = prefs.getString("port", "2121")?.toIntOrNull() ?: 2121
                    var reachable = false
                    var open = false
                    for (pt in intArrayOf(PS4_PORT, ftpPort)) {
                        try {
                            Socket().use { it.connect(InetSocketAddress(target, pt), 1200) }
                            open = true
                            break
                        } catch (e: Exception) {
                            val m = e.message ?: ""
                            if (m.contains("ECONNREFUSED") || m.contains("refused", ignoreCase = true)) {
                                reachable = true
                            }
                        }
                    }
                    state = if (open) 1 else if (reachable) 2 else 3
                }
                ui.post { setStatus(state) }
                try {
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun copyIp() {
        if (ip.isEmpty()) {
            Toast.makeText(this, "Escribe primero la IP", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IP", ip))
        Toast.makeText(this, "IP copiada", Toast.LENGTH_SHORT).show()
    }

    private fun editIp() {
        val input = field("IP de la PS4 (ej: 192.168.1.14)", ip).apply {
            keyListener = DigitsKeyListener.getInstance("0123456789.")
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        holder.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("IP de la PS4")
            .setView(holder)
            .setPositiveButton("Guardar") { _, _ ->
                ip = input.text.toString().trim()
                prefs.edit().putString("ip", ip).apply()
                ipText.text = if (ip.isEmpty()) "Toca para escribir la IP" else ip
                setStatus(if (ip.isEmpty()) 0 else 4)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun log(msg: String) {
        ui.post {
            logView.append(msg + "\n")
            if (logView.length() > 6000) logView.text = logView.text.takeLast(4000)
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setProgress(msg: String) {
        ui.post { progressView.text = msg }
    }

    private fun setTransfer(mode: Int) {
        ui.post { transfer.setMode(mode) }
    }

    private fun mbps(bytes: Long, ms: Long): String {
        if (ms <= 0) return "0 MB/s"
        val v = bytes.toDouble() / 1048576.0 / (ms / 1000.0)
        return String.format("%.1f MB/s", v)
    }

    private fun sizeText(b: Long): String = when {
        b >= 1073741824L -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1048576L -> String.format("%.1f MB", b / 1048576.0)
        b >= 1024L -> "${b / 1024} KB"
        else -> "$b B"
    }

    private fun stopServer() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun pick(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, code)
    }

    private fun queryInfo(uri: Uri): Pair<String, Long> {
        var name = "archivo"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) {
            size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }
        return Pair(name, size)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val (name, size) = queryInfo(uri)
        if (requestCode == PICK_PKG) {
            if (!name.lowercase().endsWith(".pkg")) {
                log("Eso no parece un .pkg: $name")
                return
            }
            pkgUri = uri
            pkgSize = size
            pkgLabel.text = "$name  (${size / 1048576} MB)"
        } else if (requestCode == PICK_FTP) {
            ftpUri = uri
            ftpName = name
            ftpSize = size
            ftpLabel.text = "$name  (${size / 1048576} MB)"
        }
    }

    private fun sendPkg() {
        val uri = pkgUri
        if (uri == null || pkgSize <= 0) {
            log("Primero elige un archivo .pkg.")
            return
        }
        val host = ip.trim()
        if (host.isEmpty()) {
            log("Escribe la IP de la PS4 (toca la tarjeta de arriba).")
            return
        }
        served.set(0)
        transfer.setMode(TransferView.SENDING)
        setProgress("Conectando con la PS4...")

        thread {
            try {
                try {
                    Socket().use { it.connect(InetSocketAddress(host, PS4_PORT), 4000) }
                } catch (e: Exception) {
                    log("Sin respuesta en $host:$PS4_PORT. Revisa: misma Wi-Fi, GoldHEN y Remote Package Installer abierta.")
                    setProgress("Sin respuesta de la PS4")
                    setTransfer(TransferView.ERROR)
                    return@thread
                }

                stopServer()
                val ss = ServerSocket(HTTP_PORT)
                server = ss
                val size = pkgSize
                thread { acceptLoop(ss, uri, size) }

   
