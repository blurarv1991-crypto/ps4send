package com.ps4send.app

import android.content.Context
import android.net.Uri
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

object Fmt {
    fun mbps(bytes: Long, ms: Long): String {
        if (ms <= 0) return "0 MB/s"
        return String.format("%.1f MB/s", bytes.toDouble() / 1048576.0 / (ms / 1000.0))
    }
}

object Msg {
    fun friendly(e: Exception): String {
        val m = e.toString()
        return when {
            m.contains("EADDRINUSE") || m.contains("Address already in use") ->
                "El puerto del servidor del .pkg está ocupado. Cierra la app y ábrela de nuevo, o cambia el puerto en Ajustes."
            m.contains("ENOSPC") || m.contains("No space left") ->
                "No hay espacio suficiente."
            m.contains("ECONNREFUSED") || m.contains("refused", ignoreCase = true) ->
                "La PS4 rechazó la conexión: el servicio no está activo o el puerto es otro."
            m.contains("timed out", ignoreCase = true) || m.contains("ETIMEDOUT") ->
                "La PS4 no responde. Revisa la IP y que móvil y consola estén en la misma Wi-Fi."
            m.contains("UnknownHost") || m.contains("Unable to resolve") ->
                "La IP no es válida."
            m.contains("Login rechazado") ->
                "El servidor FTP rechazó el usuario o la contraseña."
            m.contains("Connection reset") || m.contains("Broken pipe") ||
                m.contains("ECONNRESET") || m.contains("EPIPE") || m.contains("abort", ignoreCase = true) ->
                "Se perdió la conexión con la PS4. Revisa la Wi-Fi y vuelve a intentarlo."
            m.contains("FileNotFound") || m.contains("SecurityException") ->
                "No se puede leer el archivo. Vuélvelo a elegir."
            else -> m
        }
    }
}

class FtpUploader(private val ctx: Context) {

    @Volatile
    private var cancelled = false

    @Volatile
    private var client: FTPClient? = null

    fun cancel() {
        cancelled = true
        thread {
            try {
                client?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun upload(
        p: FtpParams,
        dir: String,
        uri: Uri,
        name: String,
        size: Long,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        onResult: (Int, String) -> Unit
    ) {
        thread {
            val ftp = FTPClient()
            client = ftp
            try {
                ftp.connectTimeout = 8000
                ftp.defaultTimeout = 30000
                onLog("Conectando a ${p.ip}:${p.port} ...")
                ftp.connect(p.ip, p.port)
                if (!ftp.login(p.user, p.pass)) {
                    val m = "El servidor FTP rechazó el usuario o la contraseña."
                    onLog(m)
                    onResult(TransferView.ERROR, m)
                    return@thread
                }
                ftp.enterLocalPassiveMode()
                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                ftp.bufferSize = 256 * 1024
                if (!FtpUtil.ensureDir(ftp, dir)) {
                    val m = "No pude entrar ni crear la carpeta $dir"
                    onLog(m)
                    onResult(TransferView.ERROR, m)
                    return@thread
                }
                val raw = ctx.contentResolver.openInputStream(uri)
                if (raw == null) {
                    val m = "No se puede leer el archivo. Vuélvelo a elegir."
                    onLog(m)
                    onResult(TransferView.ERROR, m)
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
                                val speed = Fmt.mbps(total - lastBytes, now - lastMs)
                                val pct = if (size > 0) (total * 100 / size).toInt() else 0
                                onProgress("Subiendo $name: $pct% · $speed")
                                if (pct >= lastLogPct + 10) {
                                    lastLogPct = pct - pct % 10
                                    onLog("$pct% · $speed")
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
                if (cancelled) {
                    onLog("Subida cancelada. Puede haber quedado el archivo a medias en $dir")
                    onResult(TransferView.IDLE, "Cancelado")
                } else if (ok) {
                    val avg = Fmt.mbps(maxOf(size, 0L), ms)
                    onLog("Subido a $dir$name en ${ms / 1000} s (promedio $avg)")
                    onResult(TransferView.SUCCESS, "Listo: $name · promedio $avg")
                } else {
                    val m = "Falló la subida: ${ftp.replyString.trim()}"
                    onLog(m)
                    onResult(TransferView.ERROR, m)
                }
            } catch (e: Exception) {
                if (cancelled) {
                    onLog("Subida cancelada. Puede haber quedado el archivo a medias en $dir")
                    onResult(TransferView.IDLE, "Cancelado")
                } else {
                    val m = Msg.friendly(e)
                    onLog(m)
                    onResult(TransferView.ERROR, m)
                }
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

class PkgSender(private val ctx: Context, private val httpPort: Int) {

    private val served = AtomicLong(0)
    private val server = PkgServer(ctx, httpPort) { n -> served.addAndGet(n) }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        server.stop()
    }

    fun send(
        host: String,
        uri: Uri,
        size: Long,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        onResult: (Int, String) -> Unit
    ) {
        thread {
            try {
                try {
                    Socket().use { it.connect(InetSocketAddress(host, 12800), 4000) }
                } catch (e: Exception) {
                    val m = "La PS4 no responde en el puerto 12800. Abre Remote Package Installer y revisa GoldHEN."
                    onLog(m)
                    onResult(TransferView.ERROR, m)
                    return@thread
                }
                server.start(uri, size)
                val myIp = DatagramSocket().use {
                    it.connect(InetAddress.getByName(host), 12800)
                    it.localAddress.hostAddress
                }
                val url = "http://$myIp:$httpPort/paquete.pkg"
                onLog("Sirviendo: $url")

                val conn = URL("http://$host:12800/api/install").openConnection() as HttpURLConnection
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
                onLog("Respuesta de la PS4: $resp")
                if (!resp.contains("success", ignoreCase = true)) {
                    server.stop()
                    val m = "La PS4 rechazó la orden. Revisa el espacio libre y que el nombre del archivo no tenga caracteres raros."
                    onResult(TransferView.ERROR, m)
                    return@thread
                }
                onLog("Orden enviada. Deja esta pantalla abierta hasta que la PS4 termine.")
                monitor(size, onProgress, onLog, onResult)
            } catch (e: Exception) {
                server.stop()
                if (cancelled) {
                    onLog("Envío cancelado.")
                    onResult(TransferView.IDLE, "Cancelado")
                } else {
                    val m = Msg.friendly(e)
                    onLog(m)
                    onResult(TransferView.ERROR, m)
                }
            }
        }
    }

    private fun monitor(
        size: Long,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        onResult: (Int, String) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        var lastB = 0L
        var lastT = t0
        var lastLog = -10
        while (server.running && !cancelled) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                break
            }
            val b = served.get()
            val now = System.currentTimeMillis()
            val pct = if (size > 0) minOf(100L, b * 100 / size).toInt() else 0
            val speed = Fmt.mbps(b - lastB, now - lastT)
            lastB = b
            lastT = now
            onProgress("La PS4 descarga: ~$pct% · $speed")
            if (pct >= lastLog + 10) {
                lastLog = pct - pct % 10
                onLog("~$pct% · $speed")
            }
            if (b >= size) {
                val avg = Fmt.mbps(b, now - t0)
                onLog("La PS4 recibió todo el archivo (promedio $avg).")
                onResult(TransferView.SUCCESS, "Descarga completa · promedio $avg")
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                }
                server.stop()
                return
            }
        }
        server.stop()
        if (cancelled) {
            onLog("Envío cancelado.")
            onResult(TransferView.IDLE, "Cancelado")
        } else {
            onResult(TransferView.IDLE, "Servidor detenido")
        }
    }
}
