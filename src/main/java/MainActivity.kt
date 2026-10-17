package com.ps4send.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.FilterInputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val PICK_PKG = 1
    private val PICK_FTP = 2
    private val PS4_PORT = 12800
    private val HTTP_PORT = 8000

    private lateinit var ipField: EditText
    private lateinit var pkgSection: LinearLayout
    private lateinit var ftpSection: LinearLayout
    private lateinit var pkgLabel: TextView
    private lateinit var ftpLabel: TextView
    private lateinit var progressView: TextView
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var dirField: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var pkgUri: Uri? = null
    private var pkgSize = -1L
    private var ftpUri: Uri? = null
    private var ftpName = ""
    private var ftpSize = -1L
    private var server: ServerSocket? = null

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ps4send", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun wrap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun field(h: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            hint = h
            inputType = type
            setText(value)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ipField = EditText(this).apply {
            hint = "IP de la PS4 (ej: 192.168.1.14)"
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            setText(prefs.getString("ip", ""))
        }

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRow.addView(button("Instalar PKG") { showMode(false) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        modeRow.addView(button("Enviar por FTP") { showMode(true) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        pkgLabel = TextView(this).apply { text = "Ningún archivo elegido" }
        pkgSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pkgSection.addView(button("1. Elegir archivo .pkg") { pick(PICK_PKG) }, wrap())
        pkgSection.addView(pkgLabel, wrap())
        pkgSection.addView(button("2. Enviar a la PS4") { sendPkg() }, wrap())
        pkgSection.addView(button("Parar servidor") {
            stopServer()
            log("Servidor parado.")
        }, wrap())

        portField = field("Puerto", prefs.getString("port", "2121") ?: "2121", InputType.TYPE_CLASS_NUMBER)
        userField = field("Usuario", prefs.getString("user", "anonymous") ?: "anonymous")
        passField = field(
            "Contraseña (vacía si no tiene)", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        dirField = field("Carpeta de destino en la PS4", prefs.getString("dir", "/data/") ?: "/data/")
        ftpLabel = TextView(this).apply { text = "Ningún archivo elegido" }
        progressView = TextView(this)

        val authRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        authRow.addView(portField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        authRow.addView(userField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))

        ftpSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ftpSection.addView(authRow, wrap())
        ftpSection.addView(passField, wrap())
        ftpSection.addView(dirField, wrap())
        ftpSection.addView(button("1. Elegir archivo") { pick(PICK_FTP) }, wrap())
        ftpSection.addView(ftpLabel, wrap())
        ftpSection.addView(button("2. Subir por FTP") { uploadFtp() }, wrap())
        ftpSection.addView(progressView, wrap())
        ftpSection.visibility = View.GONE

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(ipField, wrap())
        form.addView(modeRow, wrap())
        form.addView(pkgSection, wrap())
        form.addView(ftpSection, wrap())
        val formScroll = ScrollView(this).apply { addView(form) }

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply { addView(logView) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(8))
        }
        root.addView(formScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f))
        setContentView(root)

        log("Móvil y PS4 en el mismo Wi-Fi. Para instalar PKG: GoldHEN y Remote Package Installer abierta. Para FTP: servidor FTP activo en la PS4.")
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun showMode(ftp: Boolean) {
        pkgSection.visibility = if (ftp) View.GONE else View.VISIBLE
        ftpSection.visibility = if (ftp) View.VISIBLE else View.GONE
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
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) {
            log("Escribe la IP de la PS4.")
            return
        }
        prefs.edit().putString("ip", ip).apply()

        thread {
            try {
                try {
                    Socket().use { it.connect(InetSocketAddress(ip, PS4_PORT), 4000) }
                } catch (e: Exception) {
                    log("Sin respuesta en $ip:$PS4_PORT. Revisa: misma Wi-Fi, GoldHEN y Remote Package Installer abierta.")
                    return@thread
                }

                stopServer()
                val ss = ServerSocket(HTTP_PORT)
                server = ss
                val size = pkgSize
                thread { acceptLoop(ss, uri, size) }

                val myIp = DatagramSocket().use {
                    it.connect(InetAddress.getByName(ip), PS4_PORT)
                    it.localAddress.hostAddress
                }
                val url = "http://$myIp:$HTTP_PORT/paquete.pkg"
                log("Sirviendo: $url")

                val conn = URL("http://$ip:$PS4_PORT/api/install").openConnection() as HttpURLConnection
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
                log("Orden enviada. Deja esta pantalla abierta hasta que la PS4 termine.")
            } catch (e: Exception) {
                log("Error: $e")
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

                log("[PS4] " + reqLine.take(40) + (range?.let { "  $it" } ?: ""))

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
                        left -= n
                    }
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
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
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) {
            log("Escribe la IP de la PS4.")
            return
        }
        val port = portField.text.toString().toIntOrNull() ?: 2121
        val user = userField.text.toString().trim().ifEmpty { "anonymous" }
        val pass = passField.text.toString()
        var dir = dirField.text.toString().trim().ifEmpty { "/data/" }
        if (!dir.endsWith("/")) dir += "/"
        val name = ftpName
        val size = ftpSize
        prefs.edit()
            .putString("ip", ip)
            .putString("port", port.toString())
            .putString("user", user)
            .putString("dir", dir)
            .apply()

        thread {
            val ftp = FTPClient()
            try {
                ftp.connectTimeout = 8000
                ftp.defaultTimeout = 30000
                log("Conectando a $ip:$port ...")
                ftp.connect(ip, port)
                if (!ftp.login(user, pass)) {
                    log("Login rechazado: ${ftp.replyString.trim()}")
                    return@thread
                }
                ftp.enterLocalPassiveMode()
                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                ftp.bufferSize = 256 * 1024
                if (!ensureDir(ftp, dir)) {
                    log("No pude entrar ni crear la carpeta $dir")
                    return@thread
                }
                val raw = contentResolver.openInputStream(uri)
                if (raw == null) {
                    log("No pude abrir el archivo.")
                    return@thread
                }
                val ins = object : FilterInputStream(raw) {
                    var total = 0L
                    var lastPct = -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            total += n
                            val pct = if (size > 0) (total * 100 / size).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                setProgress("Subiendo $name: $pct%")
                            }
                        }
                        return n
                    }
                }
                val ok = ins.use { ftp.storeFile(name, it) }
                if (ok) {
                    setProgress("Listo: $name")
                    log("Subido a $dir$name")
                } else {
                    log("Falló la subida: ${ftp.replyString.trim()}")
                }
            } catch (e: Exception) {
                log("Error FTP: $e")
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
      
  