package com.ps4send.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.FilterInputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val PICK_PKG = 1
        const val PICK_FTP = 2
        const val PS4_PORT = 12800
        const val HTTP_PORT = 8000
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ps4send", MODE_PRIVATE) }
    private val served = AtomicLong(0)
    private val pkgServer = PkgServer(this, HTTP_PORT) { n -> served.addAndGet(n) }
    private val browser = FtpBrowser(this, ui) { m -> log(m) }

    private lateinit var statusCard: StatusCard
    private lateinit var tabPkg: UiBtn
    private lateinit var tabFtp: UiBtn
    private lateinit var fileIllus: FileIllustrationView
    private lateinit var transfer: TransferView
    private lateinit var progressView: TextView
    private lateinit var pkgSection: LinearLayout
    private lateinit var ftpSection: LinearLayout
    private lateinit var pkgTitle: TextView
    private lateinit var pkgSub: TextView
    private lateinit var ftpLabel: TextView
    private lateinit var btnSend: UiBtn
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var dirField: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var tMode = TransferView.IDLE
    private var pkgUri: Uri? = null
    private var pkgSize = -1L
    private var ftpUri: Uri? = null
    private var ftpName = ""
    private var ftpSize = -1L

    private fun dp(v: Int) = W.dp(this, v)

    private fun wrap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        statusCard = StatusCard(this, ui, { editIp() }, { copyIp() })
        statusCard.ftpPort = prefs.getString("port", "2121")?.toIntOrNull() ?: 2121
        statusCard.setIp(prefs.getString("ip", "") ?: "")

        tabPkg = W.tab(this, "Instalar PKG", IconView.CUBE) { showMode(false) }
        tabFtp = W.tab(this, "Enviar por FTP", IconView.FOLDER_UP) { showMode(true) }
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.box(this@MainActivity, Ui.CARD, 14f, Ui.LINE, 1f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        tabsRow.addView(tabPkg.root, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tabsRow.addView(tabFtp.root, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        fileIllus = FileIllustrationView(this)
        transfer = TransferView(this).apply { setInk(Ui.INK) }
        val stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stage.addView(fileIllus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        stage.addView(transfer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        progressView = W.centered(this, "", 13f, Ui.MUTED)

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

        pkgTitle = W.centered(this, "Selecciona un archivo .pkg", 17f, Ui.TEXT).apply { typeface = Typeface.DEFAULT_BOLD }
        pkgSub = W.centered(this, "El archivo se enviará directamente a tu PS4.", 13f, Ui.MUTED)
        val btnPick = W.button(this, "Elegir archivo .pkg", IconView.FOLDER_OPEN, 1) { pick(PICK_PKG) }
        btnSend = W.button(this, "Enviar a la PS4", IconView.SEND, 2) { sendPkg() }
        val btnStop = W.button(this, "Parar servidor", IconView.CHEVRON_DOWN, 2) {
            pkgServer.stop()
            setTransfer(TransferView.IDLE)
            setProgress("")
            log("Servidor parado.")
        }
        btnStop.icon.visibility = View.GONE
        pkgSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pkgSection.addView(pkgTitle, wrap())
        pkgSection.addView(pkgSub, wrap())
        pkgSection.addView(btnPick.root, wrap())
        pkgSection.addView(btnSend.root, wrap())
        pkgSection.addView(btnStop.root, wrap())
        pkgSection.addView(W.accordion(this, stepsPkg), wrap())

        portField = W.field(this, "Puerto", prefs.getString("port", "2121") ?: "2121", InputType.TYPE_CLASS_NUMBER)
        userField = W.field(this, "Usuario", prefs.getString("user", "anonymous") ?: "anonymous")
        passField = W.field(
            this, "Contraseña (vacía si no tiene)", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        dirField = W.field(this, "Carpeta de destino en la PS4", prefs.getString("dir", "/data/") ?: "/data/")
        ftpLabel = W.centered(this, "Ningún archivo elegido", 13f, Ui.MUTED)
        val authRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        authRow.addView(portField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        authRow.addView(userField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
        val btnBrowse = W.button(this, "Explorar carpetas de la PS4", IconView.FOLDER_OPEN, 1) { showBrowser() }
        val btnFile = W.button(this, "Elegir archivo", IconView.FOLDER_UP, 1) { pick(PICK_FTP) }
        val btnUpload = W.button(this, "Subir por FTP", IconView.SEND, 0) { uploadFtp() }
        ftpSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ftpSection.addView(W.centered(this, "Enviar por FTP", 17f, Ui.TEXT).apply { typeface = Typeface.DEFAULT_BOLD }, wrap())
        ftpSection.addView(W.centered(this, "Conecta tu móvil con la PS4 y envía archivos mediante FTP.", 13f, Ui.MUTED), wrap())
        ftpSection.addView(authRow, wrap())
        ftpSection.addView(passField, wrap())
        ftpSection.addView(dirField, wrap())
        ftpSection.addView(btnBrowse.root, wrap())
        ftpSection.addView(btnFile.root, wrap())
        ftpSection.addView(ftpLabel, wrap())
        ftpSection.addView(btnUpload.root, wrap())
        ftpSection.addView(W.accordion(this, stepsFtp), wrap())
        ftpSection.visibility = View.GONE

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(BadgeTitleView(this), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
        form.addView(statusCard.view, wrap())
        form.addView(tabsRow, wrap())
        form.addView(stage, wrap())
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
        statusCard.start()
        log("Móvil y PS4 en el mismo Wi-Fi. Para instalar PKG: GoldHEN y Remote Package Installer abierta. Para FTP: servidor FTP activo en la PS4.")
    }

    override fun onDestroy() {
        statusCard.stop()
        pkgServer.stop()
        super.onDestroy()
    }

    private fun showMode(ftp: Boolean) {
        pkgSection.visibility = if (ftp) View.GONE else View.VISIBLE
        ftpSection.visibility = if (ftp) View.VISIBLE else View.GONE
        W.styleTab(this, tabPkg, !ftp)
        W.styleTab(this, tabFtp, ftp)
        refreshStage()
    }

    private fun refreshStage() {
        val pkgMode = pkgSection.visibility == View.VISIBLE
        val idle = tMode == TransferView.IDLE
        fileIllus.visibility = if (pkgMode && idle) View.VISIBLE else View.GONE
        transfer.visibility = if (pkgMode && idle) View.GONE else View.VISIBLE
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
        ui.post {
            tMode = mode
            transfer.setMode(mode)
            refreshStage()
        }
    }

    private fun mbps(bytes: Long, ms: Long): String {
        if (ms <= 0) return "0 MB/s"
        return String.format("%.1f MB/s", bytes.toDouble() / 1048576.0 / (ms / 1000.0))
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
            pkgTitle.text = name
            pkgSub.text = "${FtpUtil.sizeText(size)} · listo para enviar"
            W.applyStyle(this, btnSend, 0)
            setTransfer(TransferView.IDLE)
            setProgress("")
        } else if (requestCode == PICK_FTP) {
            ftpUri = uri
            ftpName = name
            ftpSize = size
            ftpLabel.text = "$name  (${FtpUtil.sizeText(size)})"
            setTransfer(TransferView.IDLE)
            setProgress("")
        }
    }

    private fun copyIp() {
        val ip = statusCard.ip
        if (ip.isEmpty()) {
            Toast.makeText(this, "Escribe primero la IP", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IP", ip))
        Toast.makeText(this, "IP copiada", Toast.LENGTH_SHORT).show()
    }

    private fun editIp() {
        val input = W.field(this, "IP de la PS4 (ej: 192.168.1.14)", statusCard.ip).apply {
            keyListener = DigitsKeyListener.getInstance("0123456789.")
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        holder.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("IP de la PS4")
            .setView(holder)
            .setPositiveButton("Guardar") { _, _ ->
                val v = input.text.toString().trim()
                prefs.edit().putString("ip", v).apply()
                statusCard.setIp(v)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ftpParams(): FtpParams? {
        val host = statusCard.ip.trim()
        if (host.isEmpty()) {
            log("Escribe la IP de la PS4 (toca la tarjeta de arriba).")
            return null
        }
        val port = portField.text.toString().toIntOrNull() ?: 2121
        statusCard.ftpPort = port
        val user = userField.text.toString().trim().ifEmpty { "anonymous" }
        return FtpParams(host, port, user, passField.text.toString())
    }

    private fun showBrowser() {
        val p = ftpParams() ?: return
        browser.show(p, dirField.text.toString()) { chosen -> dirField.setText(chosen) }
    }

    private fun sendPkg() {
        val uri = pkgUri
        if (uri == null || pkgSize <= 0) {
            log("Primero elige un archivo .pkg.")
            return
        }
        val host = statusCard.ip.trim()
        if (host.isEmpty()) {
            log("Escribe la IP de la PS4 (toca la tarjeta de arriba).")
            return
        }
        val size = pkgSize
        served.set(0)
        setTransfer(TransferView.SENDING)
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
                pkgServer.start(uri, size)
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
                    monitorPkg(size)
                } else {
                    pkgServer.stop()
                    setProgress("La PS4 rechazó la orden")
                    setTransfer(TransferView.ERROR)
                }
            } catch (e: Exception) {
                log("Error: ${FtpUtil.friendly(e)}")
                setProgress("Error al enviar")
                setTransfer(TransferView.ERROR)
            }
        }
    }

    private fun monitorPkg(size: Long) {
        thread {
            val t0 = System.currentTimeMillis()
            var lastB = 0L
            var lastT = t0
            var lastLog = -10
            while (pkgServer.running) {
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
        prefs.edit().putString("port", p.port.toString()).putString("user", p.user).putString("dir", dir).apply()
        setTransfer(TransferView.SENDING)
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
                if (!FtpUtil.ensureDir(ftp, dir)) {
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
                log(FtpUtil.friendly(e))
                setProgress("No se pudo enviar")
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