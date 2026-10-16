package com.ps4send.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.method.DigitsKeyListener
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val PICK = 1
    private val PS4_PORT = 12800
    private val HTTP_PORT = 8000

    private lateinit var ipField: EditText
    private lateinit var fileLabel: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private var fileUri: Uri? = null
    private var fileSize = -1L
    private var server: ServerSocket? = null

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ps4send", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }

        ipField = EditText(this).apply {
            hint = "IP de la PS4 (ej: 192.168.1.14)"
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            setText(prefs.getString("ip", ""))
        }
        val pickBtn = Button(this).apply {
            text = "1. Elegir archivo .pkg"
            setOnClickListener { pick() }
        }
        fileLabel = TextView(this).apply { text = "Ningún archivo elegido" }
        val sendBtn = Button(this).apply {
            text = "2. Enviar a la PS4"
            setOnClickListener { send() }
        }
        val stopBtn = Button(this).apply {
            text = "Parar servidor"
            setOnClickListener {
                stopServer()
                log("Servidor parado.")
            }
        }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(ipField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(pickBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(fileLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(sendBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(stopBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        log("Móvil y PS4 en el mismo Wi-Fi. En la PS4: GoldHEN cargado y Remote Package Installer abierta.")
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun log(msg: String) {
        ui.post {
            logView.append(msg + "\n")
            if (logView.length() > 6000) logView.text = logView.text.takeLast(4000)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun stopServer() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

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
        if (!name.lowercase().endsWith(".pkg")) {
            log("Eso no parece un .pkg: $name")
            return
        }
        fileUri = uri
        fileSize = size
        fileLabel.text = "$name  (${size / 1048576} MB)"
    }

    private fun send() {
        val uri = fileUri
        if (uri == null || fileSize <= 0) {
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
                val size = fileSize
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
}
