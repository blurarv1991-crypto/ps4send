package com.ps4send.app

import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/* Lee nombre, ID y portada de un .pkg de PS4 pidiendo solo unos pocos bytes por HTTP (sin bajar el archivo) */
object PkgInfo {

    class Info(val title: String, val titleId: String, val size: Long, val icon: ByteArray?, val category: String, val version: String)

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private class Part(val data: ByteArray, val total: Long)

    /* Pide len bytes desde start, siguiendo redirecciones a mano */
    private fun get(src: String, start: Long, len: Int): Part {
        var u = src
        for (i in 0..8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Range", "bytes=$start-${start + len - 1L}")
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location") ?: throw Exception("Redirección sin destino")
                u = URL(URL(u), loc).toString()
                c.disconnect()
                continue
            }
            if (code != 200 && code != 206) {
                c.disconnect()
                throw Exception("El servidor respondió $code")
            }
            if ((c.contentType ?: "").lowercase().contains("text/html")) {
                c.disconnect()
                throw Exception("El enlace abre una página web, no el archivo")
            }
            if (code == 200 && start > 0L) {
                c.disconnect()
                throw Exception("El servidor no deja pedir partes del archivo")
            }
            val total = if (code == 206) {
                (c.getHeaderField("Content-Range") ?: "").substringAfter('/', "").trim().toLongOrNull() ?: -1L
            } else {
                c.contentLengthLong
            }
            val b = ByteArray(len)
            var got = 0
            val ins = c.inputStream
            while (got < len) {
                val n = ins.read(b, got, len - got)
                if (n < 0) break
                got += n
            }
            c.disconnect()
            return Part(if (got < len) b.copyOf(got) else b, total)
        }
        throw Exception("Demasiadas redirecciones")
    }

    fun read(url: String): Info {
        val h = get(url, 0L, 0x40)
        if (h.data.size < 0x20) throw Exception("Archivo demasiado corto")
        val hb = ByteBuffer.wrap(h.data)
        if (hb.getInt(0) != 0x7F434E54) throw Exception("No parece un .pkg de PS4")
        val count = hb.getInt(0x10)
        val tableOff = hb.getInt(0x18).toLong() and 0xFFFFFFFFL
        if (count <= 0 || count > 4096) throw Exception("Cabecera de .pkg no válida")
        val t = get(url, tableOff, count * 32)
        val tb = ByteBuffer.wrap(t.data)
        var sfoOff = -1L
        var sfoSize = 0
        var iconOff = -1L
        var iconSize = 0
        for (k in 0 until t.data.size / 32) {
            val p = k * 32
            val id = tb.getInt(p)
            val encrypted = tb.getInt(p + 8) < 0
            val off = tb.getInt(p + 16).toLong() and 0xFFFFFFFFL
            val size = tb.getInt(p + 20)
            if (encrypted || size <= 0) continue
            if (id == 0x1000 && size <= 262144) {
                sfoOff = off
                sfoSize = size
            } else if (id == 0x1200 && size <= 3000000) {
                iconOff = off
                iconSize = size
            }
        }
        var title = ""
        var titleId = ""
        var category = ""
        var version = ""
        if (sfoOff >= 0L) {
            try {
                val m = sfo(get(url, sfoOff, sfoSize).data)
                title = m["TITLE"] ?: ""
                titleId = m["TITLE_ID"] ?: ""
                category = m["CATEGORY"] ?: ""
                version = m["APP_VER"] ?: m["VERSION"] ?: ""
            } catch (e: Exception) {
            }
        }
        var icon: ByteArray? = null
        if (iconOff >= 0L) {
            try {
                val d = get(url, iconOff, iconSize).data
                if (d.size > 8 && d[1] == 'P'.code.toByte() && d[2] == 'N'.code.toByte() && d[3] == 'G'.code.toByte()) icon = d
            } catch (e: Exception) {
            }
        }
        return Info(title, titleId, h.total, icon, category, version)
    }

    private fun cstr(d: ByteArray, from: Int): String {
        if (from < 0 || from >= d.size) return ""
        var e = from
        while (e < d.size && d[e] != 0.toByte()) e++
        return String(d, from, e - from, Charsets.UTF_8)
    }

    /* PARAM.SFO: pares clave/valor en texto */
    private fun sfo(d: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        if (d.size < 0x14) return out
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != 0x46535000) return out
        val keyTab = b.getInt(8)
        val dataTab = b.getInt(0xC)
        val n = b.getInt(0x10)
        for (i in 0 until minOf(n, 256)) {
            val e = 0x14 + i * 16
            if (e + 16 > d.size) break
            val kOff = b.getShort(e).toInt() and 0xFFFF
            val fmt = b.getShort(e + 2).toInt() and 0xFFFF
            val len = b.getInt(e + 4)
            val dOff = dataTab + b.getInt(e + 12)
            if (fmt != 0x0204 && fmt != 0x0004) continue
            if (dOff < 0 || dOff >= d.size) continue
            val l = minOf(len, d.size - dOff)
            val value = String(d, dOff, l, Charsets.UTF_8).trimEnd('\u0000')
            out[cstr(d, keyTab + kOff)] = value
        }
        return out
    }
}
