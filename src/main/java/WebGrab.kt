package com.ps4send.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URL

/* Navegador dentro de la app: abres la página, resuelves el captcha tú mismo y, cuando la página
   empieza la descarga, se captura el enlace real junto con las cookies para mandarlo a la PS4 */
class WebGrab(private val act: Activity) {

    private fun dp(v: Int) = W.dp(act, v)

    fun open(startUrl: String, onCaptured: (String, Bridge.Auth) -> Unit) {
        val d = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val main = Handler(Looper.getMainLooper())
        val web = WebView(act)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val where = TextView(act).apply {
            textSize = 13f
            setTextColor(Ui.MUTED)
            maxLines = 1
            text = "Cargando..."
        }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(28), dp(8), dp(4))
        }
        bar.addView(
            TextView(act).apply {
                text = "Resuelve el captcha y toca Descargar"
                textSize = 14f
                setTextColor(Ui.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        bar.addView(
            TextView(act).apply {
                text = "Cerrar"
                textSize = 15f
                setTextColor(Ui.BLUE_LIGHT)
                setPadding(dp(12), dp(8), dp(8), dp(8))
                setOnClickListener { d.dismiss() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        val sub = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        sub.addView(where)
        sub.addView(
            TextView(act).apply {
                text = "Cuando empiece la descarga se enviará sola a la PS4."
                textSize = 12f
                setTextColor(Ui.MUTED)
            }
        )
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(sub, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                where.text = try { URL(url).host } catch (e: Exception) { url }
            }
        }

        var captured = false
        web.setDownloadListener { url, userAgent, _, mimetype, _ ->
            val low = url.lowercase()
            if (!(low.startsWith("http://") || low.startsWith("https://"))) {
                main.post { Toast.makeText(act, "Esta descarga no se puede enviar a la PS4.", Toast.LENGTH_LONG).show() }
            } else if (low.contains(".apk") || mimetype == "application/vnd.android.package-archive") {
                main.post { Toast.makeText(act, "Eso es una app de Android, no un .pkg. Sigue en la página.", Toast.LENGTH_LONG).show() }
            } else if (!captured) {
                captured = true
                val cookie = CookieManager.getInstance().getCookie(url) ?: ""
                val ref = web.url ?: startUrl
                val ua = if (userAgent.isNullOrEmpty()) web.settings.userAgentString else userAgent
                val host = try { URL(url).host } catch (e: Exception) { "" }
                main.post {
                    d.dismiss()
                    onCaptured(url, Bridge.Auth(host, cookie, ref, ua))
                }
            }
        }

        d.setContentView(root)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.window?.statusBarColor = Ui.BG
        d.window?.navigationBarColor = Ui.BG
        d.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && web.canGoBack()) {
                web.goBack()
                true
            } else {
                false
            }
        }
        d.setOnDismissListener {
            main.post {
                web.stopLoading()
                web.destroy()
            }
        }
        d.show()
        web.loadUrl(startUrl)
    }
}
