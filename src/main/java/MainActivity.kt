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
                                val myIp = DatagramSocket().use {
                    it.connect(InetAddress.getByName(host), PS4_PORT)
                    it.localAddress.hostAddress
                }
                val url = "http://$myIp:$HTTP_PORT/paquete.pkg"
                log("Sirviendo: $url")

                val conn = URL("http://$host:$PS4_PORT/api/install").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use {
                    it.write("""{"type":"direct","packages":["$url"]}""".toByteArray())
                }
                val resp = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    conn.errorStream?.bufferedReader()?.readText() ?: e.toString()
                }
                log("Respuesta de la PS4: $resp")
                if (resp.contains("success", ignoreCase = true)) {
                    log("Orden enviada. Deja esta pantalla abierta hasta que la PS4 termine.")
                    monitorPkg(ss, size)
                } else {
                    stopServer()
                    setProgress("La PS4 rechazó la orden")
                    setTransfer(TransferView.ERROR)
                }
            } catch (e: Exception) {
                log("Error: $e")
                setProgress("Error al enviar")
                setTransfer(TransferView.ERROR)
            }
        }
    }

    private fun monitorPkg(ss: ServerSocket, size: Long) {
        thread {
            val t0 = System.currentTimeMillis()
            var lastB = 0L
            var lastT = t0
            var lastLog = -10
            while (!ss.isClosed) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
                val b = served.get()
                val now = System.currentTimeMillis()
                val pct = if (size > 0) minOf(100L, b * 100 / size).toInt() else 0
                val speed = mbps(b - lastB, now - lastT)
                lastB = b
                lastT = now
                setProgress("La PS4 descarga: ~$pct% · $speed")
                if (pct >= lastLog + 10) {
                    lastLog = pct - pct % 10
                    log("~$pct% · $speed")
                }
                if (b >= size) {
                    val avg = mbps(b, now - t0)
                    setProgress("Descarga completa · promedio $avg")
                    log("La PS4 recibió todo el archivo (promedio $avg).")
                    setTransfer(TransferView.SUCCESS)
                    break
                }
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket, uri: Uri, size: Long) {
        while (!ss.isClosed) {
            val c = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            thread { handle(c, uri, size) }
        }
    }

    private fun handle(c: Socket, uri: Uri, size: Long) {
        try {
            c.use { s ->
                s.soTimeout = 30000
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val reqLine = reader.readLine() ?: return
                var range: String? = null
                while (true) {
                    val l = reader.readLine() ?: break
                    if (l.isEmpty()) break
                    if (l.startsWith("Range:", ignoreCase = true)) range = l.substring(6).trim()
                }
                val out = s.getOutputStream()

                if (!reqLine.contains("/paquete.pkg")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }

                var start = 0L
                var end = size - 1
                var partial = false
                val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
                if (m != null && (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty())) {
                    if (m.groupValues[1].isEmpty()) {
                        start = maxOf(0L, size - m.groupValues[2].toLong())
                    } else {
                        start = m.groupValues[1].toLong()
                        if (m.groupValues[2].isNotEmpty()) end = minOf(m.groupValues[2].toLong(), size - 1)
                    }
                    if (start > end || start >= size) {
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                    partial = true
                }

                val length = end - start + 1
                val head = StringBuilder()
                head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append("Content-Type: application/octet-stream\r\n")
                head.append("Accept-Ranges: bytes\r\n")
                head.append("Content-Length: $length\r\n")
                if (partial) head.append("Content-Range: bytes $start-$end/$size\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())

                if (reqLine.startsWith("HEAD")) return

                contentResolver.openInputStream(uri)?.use { ins ->
                    var toSkip = start
                    while (toSkip > 0) {
                        val n = ins.skip(toSkip)
                        if (n > 0) {
                            toSkip -= n
                        } else {
                            if (ins.read() < 0) break
                            toSkip--
                        }
                    }
                    val buf = ByteArray(256 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        served.addAndGet(n.toLong())
                        left -= n
                    }
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun ftpParams(): FtpParams? {
        val host = ip.trim()
        if (host.isEmpty()) {
            log("Escribe la IP de la PS4 (toca la tarjeta de arriba).")
            return null
        }
        val port = portField.text.toString().toIntOrNull() ?: 2121
        val user = userField.text.toString().trim().ifEmpty { "anonymous" }
        return FtpParams(host, port, user, passField.text.toString())
    }

    private fun <T> ftpSession(p: FtpParams, block: (FTPClient) -> T): T {
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

    private fun normalizePath(raw: String): String {
        var s = raw.trim().ifEmpty { "/" }
        if (!s.startsWith("/")) s = "/$s"
        while (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    private fun parentOf(p: String): String =
        if (p == "/") "/" else p.substringBeforeLast('/').ifEmpty { "/" }

    private fun childPath(p: String, name: String): String =
        if (p == "/") "/$name" else "$p/$name"

    private fun showBrowser() {
        val p = ftpParams() ?: return
        var path = normalizePath(dirField.text.toString())
        val entries = mutableListOf<FtpEntry>()
        val labels = mutableListOf<String>()

        val pathText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val statusLine = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.MUTED)
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        val fileAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Ui.TEXT)
                v.textSize = 15f
                return v
            }
        }
        val list = ListView(this)
        list.adapter = fileAdapter
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(pathText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(statusLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, dp(320)))

        fun load(target: String, fallback: Boolean) {
            statusLine.text = "Cargando..."
            thread {
                try {
                    val files = ftpSession(p) { ftp ->
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
                                    else -> "${e.name}  (${sizeText(e.size)})"
                                }
                            )
                        }
                        fileAdapter.notifyDataSetChanged()
                        pathText.text = target
                        statusLine.text = if (sorted.isEmpty()) "Carpeta vacía" else "${sorted.size} elementos"
                    }
                } catch (e: Exception) {
                    if (fallback && target != "/") {
                        load("/", false)
                    } else {
                        ui.post { statusLine.text = "Error: $e" }
                    }
                }
            }
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val e = entries[pos]
            when {
                e.parent -> load(parentOf(path), false)
                e.dir -> load(childPath(path, e.name), false)
                else -> Toast.makeText(this, "Mantén pulsado para eliminar", Toast.LENGTH_SHORT).show()
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = entries[pos]
            if (!e.parent) confirmDelete(p, path, e) { load(path, false) }
            true
        }

        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Carpetas de la PS4")
            .setView(box)
            .setPositiveButton("Usar esta carpeta") { _, _ ->
                dirField.setText(if (path.endsWith("/")) path else "$path/")
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
        val full = childPath(dir, e.name)
        val safe = full.startsWith("/data/") || full.startsWith("/mnt/usb")
        val msg = (if (e.dir) "Carpeta (solo se borra si está vacía):\n$full" else "Archivo:\n$full") +
            (if (safe) "" else "\n\nCuidado: esta ruta no es /data ni un USB. Borrar aquí puede dañar la consola.")
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("¿Eliminar?")
            .setMessage(msg)
            .setPositiveButton("Eliminar") { _, _ ->
                thread {
                    try {
                        val ok = ftpSession(p) { ftp ->
                            if (e.dir) ftp.removeDirectory(full) else ftp.deleteFile(full)
                        }
                        if (ok) {
                            log("Eliminado: $full")
                        } else {
                            log("No se pudo eliminar $full (¿carpeta con contenido o sin permiso?)")
                        }
                    } catch (ex: Exception) {
                        log("Error al eliminar: $ex")
                    }
                    ui.post { done() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askNewFolder(p: FtpParams, dir: String, done: () -> Unit) {
        val input = field("Nombre de la carpeta")
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        holder.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Carpeta nueva en $dir")
            .setView(holder)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim().replace("/", "")
                if (name.isNotEmpty()) {
                    thread {
                        try {
                            val target = childPath(dir, name)
                            val ok = ftpSession(p) { ftp -> ftp.makeDirectory(target) }
                            log(if (ok) "Carpeta creada: $target" else "No se pudo crear la carpeta $target")
                        } catch (ex: Exception) {
                            log("Error al crear carpeta: $ex")
                        }
                        ui.post { done() }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ensureDir(ftp: FTPClient, path: String): Boolean {
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

    private fun uploadFtp() {
        val uri = ftpUri
        if (uri == null) {
            log("Primero elige un archivo.")
            return
        }
        val p = ftpParams() ?: return
        var dir = dirField.text.toString().trim().ifEmpty { "/data/" }
        if (!dir.endsWith("/")) dir += "/"
        val name = ftpName
        val size = ftpSize
        prefs.edit()
            .putString("port", p.port.toString())
            .putString("user", p.user)
            .putString("dir", dir)
            .apply()
        transfer.setMode(TransferView.SENDING)
        setProgress("Conectando...")

        thread {
            val ftp = FTPClient()
            try {
                ftp.connectTimeout = 8000
                ftp.defaultTimeout = 30000
                log("Conectando a ${p.ip}:${p.port} ...")
                ftp.connect(p.ip, p.port)
                if (!ftp.login(p.user, p.pass)) {
                    log("Login rechazado: ${ftp.replyString.trim()}")
                    setProgress("Login rechazado")
                    setTransfer(TransferView.ERROR)
                    return@thread
                }
                ftp.enterLocalPassiveMode()
                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                ftp.bufferSize = 256 * 1024
                if (!ensureDir(ftp, dir)) {
                    log("No pude entrar ni crear la carpeta $dir")
                    setProgress("Carpeta no disponible")
                    setTransfer(TransferView.ERROR)
                    return@thread
                }
                val raw = contentResolver.openInputStream(uri)
                if (raw == null) {
                    log("No pude abrir el archivo.")
                    setProgress("No se pudo abrir el archivo")
                    setTransfer(TransferView.ERROR)
                    return@thread
                }
                val startMs = System.currentTimeMillis()
                val ins = object : FilterInputStream(raw) {
                    var total = 0L
                    var lastLogPct = -10
                    var lastMs = startMs
                    var lastBytes = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            total += n
                            val now = System.currentTimeMillis()
                            if (now - lastMs >= 500) {
                                val speed = mbps(total - lastBytes, now - lastMs)
                                val pct = if (size > 0) (total * 100 / size).toInt() else 0
                                setProgress("Subiendo $name: $pct% · $speed")
                                if (pct >= lastLogPct + 10) {
                                    lastLogPct = pct - pct % 10
                                    log("$pct% · $speed")
                                }
                                lastMs = now
                                lastBytes = total
                            }
                        }
                        return n
                    }
                }
                val ok = ins.use { ftp.storeFile(name, it) }
                val ms = System.currentTimeMillis() - startMs
                if (ok) {
                    val avg = mbps(maxOf(size, 0L), ms)
                    setProgress("Listo: $name · promedio $avg")
                    log("Subido a $dir$name en ${ms / 1000} s (promedio $avg)")
                    setTransfer(TransferView.SUCCESS)
                } else {
                    log("Falló la subida: ${ftp.replyString.trim()}")
                    setProgress("Falló la subida")
                    setTransfer(TransferView.ERROR)
                }
            } catch (e: Exception) {
                log("Error FTP: $e")
                setProgress("Error FTP")
                setTransfer(TransferView.ERROR)
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
    }
}
   
