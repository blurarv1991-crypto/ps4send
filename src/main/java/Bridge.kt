package com.ps4send.app

import java.io.InputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/* Modo puente: la PS4 baja el archivo desde el celular y el celular lo trae del enlace, sin guardarlo */
object Bridge {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    @Volatile private var total = 0L
    private val served = AtomicLong(0)
    private val upSem = java.util.concurrent.Semaphore(6)
    @Volatile private var finalUrl = ""
    @Volatile private var lastUrl = ""
    @Volatile private var logger: (String) -> Unit = { }
    private val reqCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun stop() {
        stopped = true
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    /* Abre el enlace siguiendo redirecciones a mano (también de http a https) */
    private fun open(src: String, range: String?): HttpURLConnection {
        var u = src
        for (i in 0..8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", UA)
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location") ?: throw Exception("Redirección sin destino")
                u = URL(URL(u), loc).toString()
                c.disconnect()
                continue
            }
            lastUrl = u
            return c
        }
        throw Exception("Demasiadas redirecciones")
    }

    /* Comprueba que el enlace entrega un archivo (no una página) y devuelve su tamaño */
    private fun probe(src: String): Long {
        val c = open(src, "bytes=0-0")
        finalUrl = lastUrl
        val code = c.responseCode
        if (code != 200 && code != 206) {
            c.disconnect()
            throw Exception("El servidor respondió $code")
        }
        val type = (c.contentType ?: "").lowercase()
        if (type.contains("text/html")) {
            c.disconnect()
            throw Exception("El enlace abre una página web, no el archivo")
        }
        val len = if (code == 206) {
            (c.getHeaderField("Content-Range") ?: "").substringAfter('/', "").trim().toLongOrNull() ?: -1L
        } else {
            c.contentLengthLong
        }
        c.disconnect()
        if (len <= 0L) throw Exception("No pude saber el tamaño del archivo")
        return len
    }

    private fun localIp(host: String): String {
        DatagramSocket().use { d ->
            d.connect(InetAddress.getByName(host), 12800)
            val ip = d.localAddress?.hostAddress ?: ""
            if (ip.isEmpty() || ip == "0.0.0.0") throw Exception("No pude saber la IP del celular. ¿Estás en el Wi-Fi?")
            return ip
        }
    }

    private fun safeName(src: String): String {
        var n = try {
            URLDecoder.decode(src.substringBefore('?').substringAfterLast('/'), "UTF-8")
        } catch (e: Exception) {
            "juego.pkg"
        }
        n = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        if (n.isEmpty()) n = "juego"
        if (!n.lowercase().endsWith(".pkg")) n += ".pkg"
        return n
    }

    private fun skipFully(i: InputStream, n: Long) {
        var left = n
        val b = ByteArray(65536)
        while (left > 0L) {
            val r = i.read(b, 0, minOf(b.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    /* Baja un trozo [s0, e0] del enlace completo en memoria (con 3 intentos) */
    private fun fetchSeg(src: String, s0: Long, e0: Long): ByteArray {
        val want = (e0 - s0 + 1L).toInt()
        var last: Exception? = null
        for (attempt in 0..2) {
            upSem.acquire()
            try {
                val range = "bytes=$s0-$e0"
                var c = try {
                    open(finalUrl.ifEmpty { src }, range)
                } catch (e: Exception) {
                    open(src, range)
                }
                if (c.responseCode >= 400 && finalUrl.isNotEmpty()) {
                    c.disconnect()
                    c = open(src, range)
                }
                val ins = c.inputStream
                if (c.responseCode == 200 && s0 > 0L) skipFully(ins, s0)
                val b = ByteArray(want)
                var got = 0
                while (got < want) {
                    val n = ins.read(b, got, want - got)
                    if (n < 0) throw Exception("Se cortó la descarga")
                    got += n
                }
                c.disconnect()
                return b
            } catch (e: Exception) {
                last = e
            } finally {
                upSem.release()
            }
            if (stopped) break
        }
        throw last ?: Exception("No pude bajar un trozo")
    }

    private fun serve(s: Socket, src: String) {
        try {
            s.soTimeout = 60000
            val rd = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val first = rd.readLine() ?: return
            var range: String? = null
            while (true) {
                val l = rd.readLine() ?: break
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("range:")) range = l.substringAfter(':').trim()
            }
            val head = first.startsWith("HEAD")
            var start = 0L
            var end = total - 1
            if (range != null && range.startsWith("bytes=")) {
                val r = range.removePrefix("bytes=").substringBefore(',')
                val a = r.substringBefore('-')
                val b = r.substringAfter('-', "")
                if (a.isEmpty()) {
                    start = maxOf(0L, total - (b.toLongOrNull() ?: 0L))
                } else {
                    start = a.toLongOrNull() ?: 0L
                    if (b.isNotEmpty()) end = minOf(end, b.toLongOrNull() ?: end)
                }
            }
            val out = s.getOutputStream()
            if (start >= total || start > end) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                return
            }
            val partial = range != null
            val len = end - start + 1
            val h = StringBuilder()
            h.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            h.append("Content-Type: application/octet-stream\r\n")
            h.append("Accept-Ranges: bytes\r\n")
            h.append("Content-Length: $len\r\n")
            h.append("Connection: close\r\n")
            if (partial) h.append("Content-Range: bytes $start-$end/$total\r\n")
            h.append("\r\n")
            out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            if (head) return
            val id = reqCount.incrementAndGet()
            val verbose = id <= 8
            if (verbose) logger("PS4 pidió #$id: ${FtpUtil.sizeText(start)} a ${FtpUtil.sizeText(end)} (${FtpUtil.sizeText(len)})")
            s.tcpNoDelay = true
            try { s.sendBufferSize = 1 shl 20 } catch (e: Exception) { }
            val seg = 4L * 1024L * 1024L
            val nSeg = ((len + seg - 1L) / seg).toInt()
            val futs = arrayOfNulls<java.util.concurrent.Future<ByteArray>>(nSeg)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            var sent = 0L
            val t2 = System.currentTimeMillis()
            try {
                var next = 0
                var i = 0
                while (i < nSeg && !stopped) {
                    while (next < nSeg && next < i + 5) {
                        val k = next
                        val s0 = start + k * seg
                        val e0 = minOf(end, s0 + seg - 1L)
                        futs[k] = pool.submit(java.util.concurrent.Callable { fetchSeg(src, s0, e0) })
                        next++
                    }
                    val blk = futs[i]!!.get()
                    futs[i] = null
                    out.write(blk)
                    sent += blk.size
                    served.addAndGet(blk.size.toLong())
                    i++
                }
                out.flush()
            } finally {
                pool.shutdownNow()
            }
            if (verbose) {
                val ms = maxOf(1L, System.currentTimeMillis() - t2)
                logger("  #$id: ${FtpUtil.sizeText(sent)} en $ms ms (${sent / 1000L / ms} MB/s)")
            }
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    fun start(host: String, src: String, log: (String) -> Unit, progress: (String) -> Unit, done: (Boolean, String) -> Unit) {
        stopped = false
        logger = log
        reqCount.set(0)
        finalUrl = ""
        Thread {
            try {
                log("Comprobando el enlace...")
                total = probe(src)
                log("Archivo de ${FtpUtil.sizeText(total)}. Abriendo el puente...")
                val ip = localIp(host)
                val ss = ServerSocket(0)
                server = ss
                served.set(0)
                Thread {
                    while (!stopped) {
                        try {
                            val s = ss.accept()
                            Thread { serve(s, src) }.start()
                        } catch (e: Exception) {
                            break
                        }
                    }
                }.start()
                val url = "http://$ip:${ss.localPort}/${safeName(src)}"
                log("Puente listo: $url")
                var sentOk = false
                var sentMsg = ""
                val latch = CountDownLatch(1)
                UrlInstall.send(host, url, log) { ok, msg ->
                    sentOk = ok
                    sentMsg = msg
                    latch.countDown()
                }
                latch.await(30, TimeUnit.SECONDS)
                if (!sentOk) {
                    stop()
                    done(false, if (sentMsg.isEmpty()) "La PS4 no contestó." else sentMsg)
                    return@Thread
                }
                log(sentMsg)
                var lastB = 0L
                var lastT = System.currentTimeMillis()
                var lastMove = lastT
                var finishedAt = 0L
                while (!stopped) {
                    Thread.sleep(1000)
                    val now = System.currentTimeMillis()
                    val cur = minOf(served.get(), total)
                    val mbs = if (now > lastT) (cur - lastB) * 1000L / (now - lastT) / 1000000L else 0L
                    if (cur != lastB) lastMove = now
                    lastB = cur
                    lastT = now
                    val pct = (cur * 100L / total).toInt()
                    progress("$pct%  ·  ${FtpUtil.sizeText(cur)} de ${FtpUtil.sizeText(total)}  ·  $mbs MB/s")
                    if (cur >= total) {
                        if (finishedAt == 0L) finishedAt = now
                        if (now - finishedAt > 20000L) break
                    } else if (now - lastMove > 120000L) {
                        break
                    }
                }
                val cancelled = stopped
                val complete = served.get() >= total
                stop()
                when {
                    complete -> done(true, "Descarga completa. La PS4 la está instalando.")
                    cancelled -> done(false, "Cancelado")
                    else -> done(false, "La PS4 dejó de pedir datos. Revisa Descargas en la PS4.")
                }
            } catch (e: Exception) {
                stop()
                done(false, "No pude abrir el enlace: ${e.message}")
            }
        }.start()
    }
}
