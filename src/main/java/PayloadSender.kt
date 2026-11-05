package com.ps4send.app

import android.content.Context
import android.net.Uri
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

object PayloadSender {
    fun send(
        ctx: Context,
        host: String,
        port: Int,
        uri: Uri,
        log: (String) -> Unit,
        done: (Boolean) -> Unit
    ) {
        thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                val out = s.getOutputStream()
                val input = ctx.contentResolver.openInputStream(uri)
                    ?: throw Exception("No pude abrir el archivo")
                var total = 0L
                input.use { i ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
                out.flush()
                s.shutdownOutput()
                Thread.sleep(500)
                s.close()
                log("Payload enviado: $total bytes a $host:$port")
                done(true)
            } catch (e: Exception) {
                log("Error enviando payload: ${e.message}")
                done(false)
            }
        }
    }
}
