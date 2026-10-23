package com.ps4send.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val PICK_PKG = 1
        const val PICK_FTP = 2
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ps4send", MODE_PRIVATE) }
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
    private lateinit var btnCancelPkg: UiBtn
    private lateinit var btnConnect: UiBtn
    private lateinit var btnFile: UiBtn
    private lateinit var btnUpload: UiBtn
    private lateinit var btnCancelFtp: UiBtn
    private lateinit var rowPort: RowView
    private lateinit var rowUser: RowView
    private lateinit var rowPass: RowView
    private lateinit var rowDir: RowView
    private lateinit var logView: TextView
    private lateinit var logBox: LinearLayout

    private var tMode = TransferView.IDLE
    private var busy = false
    private var pkgUri: Uri? = null
    private var pkgSize = -1L
    private var ftpUri: Uri? = null
    private var ftpName = ""
    private var ftpSize = -1L
    private var sender: PkgSender? = null
    private var uploader: FtpUploader? = null

    private var httpPort = 8000
    private var ftpPortV = 2121
    private var ftpUserV = "anonymous"
    private var ftpPassV = ""
    private var ftpDirV = "/data/"
    class QItem(val uri: Uri, val name: String, val size: Long)

private var pkgQ = listOf<QItem>()
private var ftpQ = listOf<QItem>()
private var qIndex = 0
private var curMode = "PKG"
private var curName = ""
private var curUri = ""
private var curDest = ""
private var runHost = ""
private var runDir = "/data/"
private var runParams: FtpParams? = null
private var lastNotif = 0L
private var qStop = false
private lateinit var btnScan: UiBtn
private lateinit var btnHist: UiBtn
private val dlgTheme = android.R.style.Theme_DeviceDefault_Dialog_Alert

private fun qSize() = if (curMode == "PKG") pkgQ.size else ftpQ.size

private fun pfx() = if (qSize() > 1) "(${qIndex + 1}/${qSize()}) " else ""

private fun notif(text: String) {
    try {
        TransferService.start(this, text)
    } catch (e: Exception) {
    }
}

private fun runPkg() {
    val q = pkgQ[qIndex]
    curMode = "PKG"
    curName = q.name
    curUri = q.uri.toString()
    curDest = ""
    val s = PkgSender(this, httpPort)
    sender = s
    begin(pfx() + "Conectando con la PS4...")
    s.send(runHost, q.uri, q.size, { t -> setProgress(pfx() + t) }, { m -> log(m) }, { c, t -> onDone(c, t) })
}

private fun runFtpItem() {
    val q = ftpQ[qIndex]
    val p = runParams ?: return
    curMode = "FTP"
    curName = q.name
    curUri = q.uri.toString()
    curDest = runDir + q.name
    val u = FtpUploader(this)
    uploader = u
    begin(pfx() + "Conectando...")
    u.upload(p, runDir, q.uri, q.name, q.size, { t -> setProgress(pfx() + t) }, { m -> log(m) }, { c, t -> onDone(c, t) })
}

private fun onDone(code: Int, text: String) {
    ui.post {
        val ok = code == TransferView.SUCCESS
        if (code != TransferView.IDLE) {
            History.add(this, HistoryItem(curMode, curName, curDest, curUri, System.currentTimeMillis(), ok))
        }
        if (ok && qIndex + 1 < qSize()) {
            qIndex++
            ui.postDelayed({
                if (qStop) finish(TransferView.IDLE, "Cancelado")
                else if (curMode == "PKG") runPkg() else runFtpItem()
            }, 3000)
        } else {
            finish(code, text)
        }
    }
}

private fun scanPs4() {
    btnScan.label.text = "Buscando..."
    btnScan.root.isEnabled = false
    thread {
        val ips = Scanner.find(12800).ifEmpty { Scanner.find(ftpPortV) }
        ui.post {
            btnScan.label.text = "Buscar PS4"
            btnScan.root.isEnabled = true
            if (ips.isEmpty()) {
                log("No encontré la PS4. Abre Remote Package Installer o el FTP y revisa el Wi-Fi.")
            } else if (ips.size == 1) {
                prefs.edit().putString("ip", ips[0]).apply()
                statusCard.setIp(ips[0])
                log("PS4 encontrada: ${ips[0]}")
            } else {
                AlertDialog.Builder(this, dlgTheme)
                    .setTitle("Elige tu PS4")
                    .setItems(ips.toTypedArray()) { _, i ->
                        prefs.edit().putString("ip", ips[i]).apply()
                        statusCard.setIp(ips[i])
                    }
                    .show()
            }
        }
    }
}

private fun showHistory() {
    val items = History.all(this)
    if (items.isEmpty()) {
        Toast.makeText(this, "Aún no hay envíos", Toast.LENGTH_SHORT).show()
        return
    }
    val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
    val labels = items.map {
        (if (it.ok) "✓ " else "✗ ") + it.name + "\n" + it.mode + " · " + fmt.format(java.util.Date(it.time))
    }
    AlertDialog.Builder(this, dlgTheme)
        .setTitle("Historial")
        .setItems(labels.toTypedArray()) { _, i -> historyActions(items[i]) }
        .setNeutralButton("Vaciar") { _, _ -> History.clear(this) }
        .setNegativeButton("Cerrar", null)
        .show()
}

private fun historyActions(h: HistoryItem) {
    val opts = mutableListOf("Repetir envío")
    if (h.mode == "FTP" && h.dest.isNotEmpty()) opts.add("Borrar de la PS4")
    opts.add("Quitar del historial")
    AlertDialog.Builder(this, dlgTheme)
        .setTitle(h.name)
        .setItems(opts.toTypedArray()) { _, i ->
            when (opts[i]) {
                "Repetir envío" -> repeatSend(h)
                "Borrar de la PS4" -> deleteRemote(h)
                else -> History.remove(this, h)
            }
        }
        .show()
}

private fun repeatSend(h: HistoryItem) {
    if (busy) {
        log("Ya hay un envío en curso.")
        return
    }
    try {
        val uri = Uri.parse(h.uri)
        val (name, size) = queryInfo(uri)
        if (size <= 0) {
            log("El archivo ya no está disponible.")
            return
        }
        if (h.mode == "PKG") {
            pkgQ = listOf(QItem(uri, name, size))
            sendPkg()
        } else {
            ftpQ = listOf(QItem(uri, name, size))
            uploadFtp(h.dest.substringBeforeLast('/') + "/")
        }
    } catch (e: Exception) {
        log("No se puede abrir el archivo: ${e.message}")
    }
}

private fun deleteRemote(h: HistoryItem) {
    val p = ftpParams() ?: return
    AlertDialog.Builder(this, dlgTheme)
        .setTitle("Borrar de la PS4")
        .setMessage(h.dest)
        .setPositiveButton("Borrar") { _, _ ->
            thread {
                try {
                    FtpUtil.session(p) { ftp ->
                        if (ftp.deleteFile(h.dest)) log("Borrado: ${h.dest}")
                        else log("No se pudo borrar: ${h.dest}")
                    }
                } catch (e: Exception) {
                    log(Msg.friendly(e))
                }
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

    private fun dp(v: Int) = W.dp(this, v)

    private fun wrap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }

    private fun cancelButton(): UiBtn {
        val b = W.button(this, "Cancelar", IconView.CLOSE, 1) { cancelAll() }
        b.root.background = Ui.box(this, Color.TRANSPARENT, 14f, Ui.RED, 1.5f)
        b.label.setTextColor(Ui.RED)
        b.icon.setTint(Ui.RED)
        b.root.visibility = View.GONE
        return b
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
}
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        applyKeepOn(prefs.getBoolean("keepOn", true))

        httpPort = prefs.getString("httpPort", "8000")?.toIntOrNull() ?: 8000
        ftpPortV = prefs.getString("port", "2121")?.toIntOrNull() ?: 2121
        ftpUserV = prefs.getString("user", "anonymous") ?: "anonymous"
        ftpDirV = prefs.getString("dir", "/data/") ?: "/data/"

        statusCard = StatusCard(this, ui, { editIp() }, { copyIp() })
        statusCard.ftpPort = ftpPortV
        statusCard.setIp(prefs.getString("ip", "") ?: "")

        val gear = IconView(this, IconView.GEAR, Ui.BLUE_SOFT).apply { setOnClickListener { showSettings() } }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(BadgeTitleView(this), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        headerRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        headerRow.addView(gear, LinearLayout.LayoutParams(dp(30), dp(30)))

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
            "Paso 4: Toca \"Iniciar conexión\". Con \"Ruta\" eliges la carpeta; mantén pulsado un elemento para eliminarlo.\n" +
            "Paso 5: Elige el archivo y toca \"Subir por FTP\".\n" +
            "Paso 6: Espera a que la animación termine en verde."

        pkgTitle = W.centered(this, "Selecciona un archivo .pkg", 17f, Ui.TEXT).apply { typeface = Typeface.DEFAULT_BOLD }
        pkgSub = W.centered(this, "El archivo se enviará directamente a tu PS4.", 13f, Ui.MUTED)
        val btnPick = W.button(this, "Elegir archivo .pkg", IconView.FOLDER_OPEN, 1) { pick(PICK_PKG) }
        btnSend = W.button(this, "Enviar a la PS4", IconView.SEND, 2) { sendPkg() }
        btnCancelPkg = cancelButton()
        pkgSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pkgSection.addView(pkgTitle, wrap())
        pkgSection.addView(pkgSub, wrap())
        pkgSection.addView(btnPick.root, wrap())
        pkgSection.addView(btnSend.root, wrap())
        pkgSection.addView(btnCancelPkg.root, wrap())
        pkgSection.addView(W.accordion(this, stepsPkg), wrap())

        rowPort = Rows.row(this, IconView.PLUG, "Puerto:", ftpPortV.toString()) { editPort() }
        rowUser = Rows.row(this, IconView.USER, "Usuario:", ftpUserV) { editUser() }
        rowPass = Rows.row(this, IconView.LOCK, "Contraseña:", "(vacía)") { editPass() }
        rowDir = Rows.row(this, IconView.FOLDER_OPEN, "Ruta:", ftpDirV) { chooseDir() }
        val rowsCard = Rows.card(this)
        rowsCard.addView(rowPort.root)
        rowsCard.addView(Rows.divider(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        rowsCard.addView(rowUser.root)
        rowsCard.addView(Rows.divider(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        rowsCard.addView(rowPass.root)
        rowsCard.addView(Rows.divider(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        rowsCard.addView(rowDir.root)

        btnConnect = W.button(this, "Iniciar conexión", IconView.WIFI, 0) { connectFtp() }
        btnFile = W.button(this, "Elegir archivo", IconView.FOLDER_UP, 1) { pick(PICK_FTP) }
        btnUpload = W.button(this, "Subir por FTP", IconView.SEND, 0) { uploadFtp() }
        btnCancelFtp = cancelButton()
        ftpLabel = W.centered(this, "Ningún archivo elegido", 13f, Ui.MUTED)
        btnFile.root.visibility = View.GONE
        btnUpload.root.visibility = View.GONE
        ftpLabel.visibility = View.GONE

        ftpSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ftpSection.addView(W.centered(this, "Enviar por FTP", 17f, Ui.TEXT).apply { typeface = Typeface.DEFAULT_BOLD }, wrap())
        ftpSection.addView(W.centered(this, "Conecta tu móvil con la PS4 y envía archivos mediante FTP.", 13f, Ui.MUTED), wrap())
        ftpSection.addView(rowsCard, wrap())
        ftpSection.addView(btnConnect.root, wrap())
        ftpSection.addView(btnFile.root, wrap())
        ftpSection.addView(ftpLabel, wrap())
        ftpSection.addView(btnUpload.root, wrap())
        ftpSection.addView(btnCancelFtp.root, wrap())
        ftpSection.addView(W.accordion(this, stepsFtp), wrap())
        ftpSection.visibility = View.GONE

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.MUTED)
            setPadding(dp(30), dp(2), dp(4), dp(10))
        }
        logBox = Rows.collapsible(this, "Registro", IconView.INFO, logView, false)
        logBox.visibility = if (prefs.getBoolean("showLog", true)) View.VISIBLE else View.GONE
btnScan = W.button(this, "Buscar PS4", IconView.WIFI, 1) { scanPs4() }
btnHist = W.button(this, "Historial", IconView.INFO, 1) { showHistory() }
val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
toolsRow.addView(btnScan.root, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
toolsRow.addView(btnHist.root, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
        form.addView(statusCard.view, wrap())
        form.addView(toolsRow, wrap())
        form.addView(tabsRow, wrap())
        form.addView(stage, wrap())
        form.addView(progressView, wrap())
        form.addView(pkgSection, wrap())
        form.addView(ftpSection, wrap())
        form.addView(logBox, wrap())
        val formScroll = ScrollView(this).apply {
            addView(form)
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(28), dp(16), dp(8))
        }
        root.addView(formScroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        showMode(false)
        statusCard.start()
        log("Móvil y PS4 en el mismo Wi-Fi. Para instalar PKG: GoldHEN y Remote Package Installer abierta. Para FTP: servidor FTP activo en la PS4.")
    }

    override fun onDestroy() {
        statusCard.stop()
        cancelAll()
        TransferService.stop(this)
        super.onDestroy()
    }

    private fun applyKeepOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
        }
    }

    private fun setProgressOld(msg: String) {
        ui.post { progressView.text = msg }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        val show = if (b) View.VISIBLE else View.GONE
        btnCancelPkg.root.visibility = show
        btnCancelFtp.root.visibility = show
    }

    private fun beginOld(text: String) {
        setBusy(true)
        tMode = TransferView.SENDING
        transfer.setMode(TransferView.SENDING)
        refreshStage()
        progressView.text = text
    }

    private fun finishOld(code: Int, text: String) {
        ui.post {
            setBusy(false)
            tMode = code
            transfer.setMode(code)
            progressView.text = text
            refreshStage()
        }
    }

    private fun resetStage() {
        if (busy) return
        tMode = TransferView.IDLE
        transfer.setMode(TransferView.IDLE)
        refreshStage()
        progressView.text = ""
    }

    private fun cancelAllOld() {
        sender?.cancel()
        uploader?.cancel()
    }
    private fun setProgress(msg: String) {
    ui.post {
        progressView.text = msg
        val now = System.currentTimeMillis()
        if (busy && now - lastNotif > 2000) {
            lastNotif = now
            notif(msg)
        }
    }
}

private fun begin(text: String) {
    setBusy(true)
    notif(text)
    tMode = TransferView.SENDING
    transfer.setMode(TransferView.SENDING)
    refreshStage()
    progressView.text = text
}

private fun finish(code: Int, text: String) {
    ui.post {
        setBusy(false)
        TransferService.stop(this)
        tMode = code
        transfer.setMode(code)
        progressView.text = text
        refreshStage()
    }
}

private fun cancelAll() {
    qStop = true
    sender?.cancel()
    uploader?.cancel()
}
    private fun prompt(
        title: String,
        hint: String,
        current: String,
        type: Int,
        digitsOnly: Boolean,
        onOk: (String) -> Unit
    ) {
        val input = W.field(this, hint, current, type)
        if (digitsOnly) input.keyListener = DigitsKeyListener.getInstance("0123456789.")
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        holder.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton("Guardar") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editIp() {
        prompt("IP de la PS4", "IP (ej: 192.168.1.14)", statusCard.ip, InputType.TYPE_CLASS_TEXT, true) { v ->
            prefs.edit().putString("ip", v).apply()
            statusCard.setIp(v)
        }
    }

    private fun editPort() {
        prompt("Puerto del FTP", "Puerto", ftpPortV.toString(), InputType.TYPE_CLASS_NUMBER, false) { v ->
            val n = v.toIntOrNull()
            if (n == null || n < 1 || n > 65535) {
                Toast.makeText(this, "Puerto no válido", Toast.LENGTH_SHORT).show()
            } else {
                ftpPortV = n
                rowPort.value.text = n.toString()
                statusCard.ftpPort = n
                prefs.edit().putString("port", n.toString()).apply()
            }
        }
    }

    private fun editUser() {
        prompt("Usuario del FTP", "Usuario", ftpUserV, InputType.TYPE_CLASS_TEXT, false) { v ->
            ftpUserV = v.ifEmpty { "anonymous" }
            rowUser.value.text = ftpUserV
            prefs.edit().putString("user", ftpUserV).apply()
        }
    }

    private fun editPass() {
        prompt(
            "Contraseña del FTP", "Contraseña (vacía si no tiene)", ftpPassV,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, false
        ) { v ->
            ftpPassV = v
            rowPass.value.text = if (v.isEmpty()) "(vacía)" else "••••••"
        }
    }

    private fun setDir(d: String) {
        ftpDirV = d
        rowDir.value.text = d
        prefs.edit().putString("dir", d).apply()
    }

    private fun chooseDir() {
        val p = ftpParams() ?: return
        browser.show(p, ftpDirV) { chosen -> setDir(chosen) }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val http = W.field(this, "Puerto del servidor del .pkg", httpPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val keepOn = Switch(this).apply {
            text = "Mantener la pantalla encendida"
            isChecked = prefs.getBoolean("keepOn", true)
            setTextColor(Ui.TEXT)
        }
        val showLog = Switch(this).apply {
            text = "Mostrar el registro"
            isChecked = prefs.getBoolean("showLog", true)
            setTextColor(Ui.TEXT)
        }
        box.addView(W.label(this, "Puerto del servidor del .pkg (por defecto 8000)", 12f, Ui.MUTED))
        box.addView(http, wrap())
        box.addView(keepOn, wrap())
        box.addView(showLog, wrap())
        box.addView(W.label(this, "PS4 Send 1.0", 12f, Ui.MUTED), wrap())
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ajustes")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val n = http.text.toString().toIntOrNull()
                if (n != null && n in 1024..65535) httpPort = n
                prefs.edit()
                    .putString("httpPort", httpPort.toString())
                    .putBoolean("keepOn", keepOn.isChecked)
                    .putBoolean("showLog", showLog.isChecked)
                    .apply()
                applyKeepOn(keepOn.isChecked)
                logBox.visibility = if (showLog.isChecked) View.VISIBLE else View.GONE
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun pickOld(code: Int) {
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

    private fun onActivityResultOld(requestCode: Int, resultCode: Int, data: Intent?) {
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
            resetStage()
        } else if (requestCode == PICK_FTP) {
            ftpUri = uri
            ftpName = name
            ftpSize = size
            ftpLabel.text = "$name  (${FtpUtil.sizeText(size)})"
            resetStage()
        }
    }
private fun pick(code: Int) {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
    startActivityForResult(i, code)
}

override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode != RESULT_OK || data == null) return
    val uris = mutableListOf<Uri>()
    val cd = data.clipData
    if (cd != null) {
        for (n in 0 until cd.itemCount) uris.add(cd.getItemAt(n).uri)
    } else {
        data.data?.let { uris.add(it) }
    }
    val items = uris.map { u ->
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        val (name, size) = queryInfo(u)
        QItem(u, name, size)
    }
    if (items.isEmpty()) return
    if (requestCode == PICK_PKG) {
        val good = items.filter { it.name.lowercase().endsWith(".pkg") }
        if (good.isEmpty()) {
            log("Eso no parece un .pkg: ${items[0].name}")
            return
        }
        pkgQ = good
        pkgTitle.text = if (good.size == 1) good[0].name else "${good.size} archivos .pkg"
        pkgSub.text = "${FtpUtil.sizeText(good.sumOf { it.size })} · listo para enviar"
        W.applyStyle(this, btnSend, 0)
        resetStage()
    } else if (requestCode == PICK_FTP) {
        ftpQ = items
        ftpLabel.text = if (items.size == 1) {
            "${items[0].name}  (${FtpUtil.sizeText(items[0].size)})"
        } else {
            "${items.size} archivos  (${FtpUtil.sizeText(items.sumOf { it.size })})"
        }
        resetStage()
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

    private fun ftpParams(): FtpParams? {
        val host = statusCard.ip.trim()
        if (host.isEmpty()) {
            log("Escribe la IP de la PS4 (toca la tarjeta de arriba).")
            return null
        }
        return FtpParams(host, ftpPortV, ftpUserV.ifEmpty { "anonymous" }, ftpPassV)
    }

    private fun connectFtp() {
        val p = ftpParams() ?: return
        progressView.text = "Conectando..."
        thread {
            try {
                FtpUtil.session(p) { ftp -> ftp.printWorkingDirectory() }
                log("Conexión FTP correcta con ${p.ip}:${p.port}")
                setProgress("Conectado al FTP")
                ui.post { showConnected(true) }
            } catch (e: Exception) {
                val m = Msg.friendly(e)
                log(m)
                setProgress(m)
                ui.post { showConnected(false) }
            }
        }
    }

    private fun showConnected(c: Boolean) {
        val v = if (c) View.VISIBLE else View.GONE
        btnFile.root.visibility = v
        btnUpload.root.visibility = v
        ftpLabel.visibility = v
        btnConnect.label.text = if (c) "Reconectar" else "Iniciar conexión"
    }
private fun sendPkg() {
    if (busy) {
        log("Ya hay un envío en curso.")
        return
    }
    if (pkgQ.isEmpty()) {
        log("Primero elige un archivo .pkg.")
        return
    }
    val host = statusCard.ip.trim()
    if (host.isEmpty()) {
        log("Escribe la IP de la PS4 (toca la tarjeta de arriba).")
        return
    }
    runHost = host
    qStop = false
    qIndex = 0
    curMode = "PKG"
    runPkg()
}

private fun uploadFtp(dirOverride: String? = null) {
    if (busy) {
        log("Ya hay un envío en curso.")
        return
    }
    if (ftpQ.isEmpty()) {
        log("Primero elige un archivo.")
        return
    }
    val p = ftpParams() ?: return
    var dir = dirOverride ?: ftpDirV.ifEmpty { "/data/" }
    if (!dir.endsWith("/")) dir += "/"
    runParams = p
    runDir = dir
    qStop = false
    qIndex = 0
    curMode = "FTP"
    runFtpItem()
}
    private fun sendPkgOld() {
        if (busy) {
            log("Ya hay un envío en curso.")
            return
        }
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
        val s = PkgSender(this, httpPort)
        sender = s
        begin("Conectando con la PS4...")
        s.send(host, uri, pkgSize, { t -> setProgress(t) }, { m -> log(m) }, { c, t -> finish(c, t) })
    }

    private fun uploadFtpOld() {
        if (busy) {
            log("Ya hay un envío en curso.")
            return
        }
        val uri = ftpUri
        if (uri == null) {
            log("Primero elige un archivo.")
            return
        }
        val p = ftpParams() ?: return
        var dir = ftpDirV.ifEmpty { "/data/" }
        if (!dir.endsWith("/")) dir += "/"
        val u = FtpUploader(this)
        uploader = u
        begin("Conectando...")
        u.upload(p, dir, uri, ftpName, ftpSize, { t -> setProgress(t) }, { m -> log(m) }, { c, t -> finish(c, t) })
    }
}
