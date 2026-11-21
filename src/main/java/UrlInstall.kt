package com.ps4send.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/* Le manda a Remote Package Installer (puerto 12800) un enlace directo para que la PS4 lo descargue sola */
object UrlInstall {

    fun send(host: String, url: String, log: (String) -> Unit, done: (Boolean, String) -> Unit) {
        Thread {
            var enviado = false
            try {
                val body = JSONObject()
                    .put("type", "direct")
                    .put("packages", JSONArray().put(url))
                    .toString()
                val c = URL("http://$host:12800/api/install").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 15000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                enviado = true
                val code = c.responseCode
                val txt = try {
                    (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                } catch (e: Exception) {
                    ""
                }
                log("Respuesta de la PS4 ($code): ${txt.take(200)}")
                val ok = code in 200..299 && !txt.contains("fail", ignoreCase = true)
                if (ok) done(true, "Enlace enviado. Mira Descargas en la PS4.")
                else done(false, "La PS4 no aceptó el enlace (código $code).")
            } catch (e: SocketTimeoutException) {
                if (enviado) done(true, "Orden enviada, la PS4 no contestó. Mira Descargas en la PS4.")
                else done(false, "No pude conectar con la PS4. Abre Remote Package Installer y revisa la IP.")
            } catch (e: Exception) {
                done(false, "No pude enviar el enlace: ${e.message}")
            }
        }.start()
    }
}
