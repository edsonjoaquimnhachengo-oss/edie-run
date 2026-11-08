package com.edierun.vault.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.edierun.vault.download.DownloadManager
import com.edierun.vault.net.TorManager
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.Executor

/**
 * O navegador Edie Run. Funciona sempre em modo privado: sem cache em disco, sem
 * histórico guardado, sem formulários memorizados, sem cookies de terceiros.
 * Tudo o que a sessão criou é apagado quando o cofre fecha.
 * Com o Tor ligado, todo o tráfego do navegador passa pela rede Tor.
 */
class BrowserController(private val context: Context) {
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(100)
        private set
    var canBack by mutableStateOf(false)
        private set
    var canForward by mutableStateOf(false)
        private set
    var customView by mutableStateOf<View?>(null)
        private set

    /** Está na página inicial (atalhos e páginas guardadas), sem nenhum site aberto. */
    var onHome by mutableStateOf(true)
        private set

    /** Ícone do site aberto, recebido do próprio site. */
    var favicon by mutableStateOf<Bitmap?>(null)
        private set
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** O utilizador quer navegar pelo Tor. */
    var torWanted by mutableStateOf(false)

    /** O WebView já está configurado para usar o Tor. */
    var proxyApplied by mutableStateOf(false)
        private set
    var torUnsupported by mutableStateOf(false)
        private set

    @Volatile private var blockAll = false
    @Volatile private var httpsOnly = false

    var searchBase = "https://duckduckgo.com/?q="
    var onDownloadRequest: ((url: String, mime: String?, name: String?) -> Unit)? = null
    var onImageLongPress: ((String) -> Unit)? = null

    private val direct = Executor { it.run() }
    private var pendingLoad: String? = null

    val webView: WebView = createWebView()

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.setGeolocationEnabled(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.userAgentString = DownloadManager.USER_AGENT
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, NO_WEBRTC_JS, setOf("*"))
            } catch (_: Throwable) { }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase() ?: return true
                if (scheme != "http" && scheme != "https") return true // não abre outras apps
                if (scheme == "http" && httpsOnly) {
                    view.loadUrl(u.toString().replaceFirst("http://", "https://"))
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val scheme = request.url.scheme?.lowercase()
                val block = blockAll || (httpsOnly && scheme == "http")
                return if (block) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            }

            override fun onPageStarted(view: WebView, u: String?, icon: Bitmap?) {
                if (u == null || u.startsWith("data:")) return
                if (u == "about:blank") {
                    onHome = true
                    url = ""
                } else {
                    onHome = false
                    if (u != url) favicon = null
                    url = u
                }
                refreshNav(view)
            }

            override fun onPageFinished(view: WebView, u: String?) {
                refreshNav(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                if (u == null || u.startsWith("data:")) return
                if (u == "about:blank") {
                    onHome = true
                    url = ""
                } else {
                    onHome = false
                    url = u
                }
                refreshNav(view)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                title = t ?: ""
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon != null) favicon = icon
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                customView = view
                customCallback = callback
            }

            override fun onHideCustomView() {
                customView = null
                customCallback = null
            }
        }

        wv.setDownloadListener { dUrl, _, contentDisposition, mimetype, _ ->
            val name = URLUtil.guessFileName(dUrl, contentDisposition, mimetype)
            onDownloadRequest?.invoke(dUrl, mimetype, name)
        }

        wv.setOnLongClickListener {
            val r = wv.hitTestResult
            if (r.type == WebView.HitTestResult.IMAGE_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val src = r.extra
                if (src != null && (src.startsWith("http://") || src.startsWith("https://"))) {
                    onImageLongPress?.invoke(src)
                    return@setOnLongClickListener true
                }
            }
            false
        }
        return wv
    }

    private fun refreshNav(view: WebView) {
        canBack = view.canGoBack()
        canForward = view.canGoForward()
    }

    /** Devolve o WebView pronto a ser colocado no ecrã. */
    fun attach(): WebView {
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    fun exitFullscreen() {
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
    }

    fun toUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && t.contains('.') && !t.endsWith('.')) return "https://$t"
        return searchBase + URLEncoder.encode(t, "UTF-8")
    }

    fun load(raw: String) {
        if (raw.isBlank()) return
        var target = toUrl(raw)
        if (httpsOnly && target.startsWith("http://")) target = target.replaceFirst("http://", "https://")
        onHome = false
        url = target
        if (blockAll) {
            pendingLoad = target
            return
        }
        webView.loadUrl(target)
    }

    /** Volta à página inicial. Fica "about:blank" no histórico, para o botão recuar funcionar. */
    fun home() {
        webView.stopLoading()
        onHome = true
        url = ""
        title = ""
        favicon = null
        progress = 100
        if (webView.url != null && webView.url != "about:blank") webView.loadUrl("about:blank")
    }

    /** Miniatura do que está no ecrã (para a página guardada). */
    fun snapshot(maxWidth: Int = 360): Bitmap? {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val f = maxWidth.toFloat() / w
            val bmp = Bitmap.createBitmap(maxWidth, (h * f).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(f, f)
            webView.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** Grava a página aberta (texto, imagens e estilo) num único ficheiro MHTML. */
    fun saveArchive(file: java.io.File, done: (Boolean) -> Unit) {
        try {
            webView.saveWebArchive(file.absolutePath, false) { path -> done(path != null && file.length() > 0) }
        } catch (_: Throwable) {
            done(false)
        }
    }

    /**
     * Sincroniza o navegador com o estado do Tor. Enquanto o Tor é pedido mas ainda
     * não está pronto, todos os pedidos são bloqueados, para o IP real nunca sair.
     */
    fun syncTor(state: TorManager.State) {
        if (torWanted) {
            httpsOnly = true
            if (state == TorManager.State.READY && !proxyApplied) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    torUnsupported = true
                    blockAll = true
                    return
                }
                blockAll = true
                val cfg = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:${TorManager.httpPort}")
                    .build()
                ProxyController.getInstance().setProxyOverride(cfg, direct) {
                    webView.post {
                        proxyApplied = true
                        blockAll = false
                        clearSessionData()
                        val next = pendingLoad
                        pendingLoad = null
                        if (next != null) webView.loadUrl(next) else if (url.isNotEmpty()) webView.reload()
                    }
                }
            } else if (state != TorManager.State.READY) {
                blockAll = true
                if (proxyApplied) {
                    proxyApplied = false
                    ProxyController.getInstance().clearProxyOverride(direct) { }
                }
            }
        } else {
            httpsOnly = false
            if (proxyApplied) {
                ProxyController.getInstance().clearProxyOverride(direct) {
                    webView.post {
                        proxyApplied = false
                        blockAll = false
                        clearSessionData()
                    }
                }
            } else {
                blockAll = false
                pendingLoad?.let { p ->
                    pendingLoad = null
                    webView.loadUrl(p)
                }
            }
        }
    }

    private fun clearSessionData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
    }

    /** Apaga tudo o que o navegador acumulou nesta sessão. */
    fun wipe() {
        try {
            exitFullscreen()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
            clearSessionData()
            @Suppress("DEPRECATION")
            WebViewDatabase.getInstance(context).clearFormData()
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
            if (proxyApplied && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(direct) { }
            }
            proxyApplied = false
        } catch (_: Throwable) {
        }
        url = ""
        title = ""
        favicon = null
        onHome = true
    }

    fun destroy() {
        wipe()
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** Desactiva WebRTC, que pode revelar o IP real mesmo com proxy. */
        private const val NO_WEBRTC_JS = """
            (function(){try{
              ['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','RTCSessionDescription','RTCIceCandidate']
                .forEach(function(k){try{Object.defineProperty(window,k,{value:undefined,writable:false,configurable:false});}catch(e){}});
              if(navigator.mediaDevices){try{navigator.mediaDevices.getUserMedia=function(){return Promise.reject(new Error('bloqueado'));};}catch(e){}}
            }catch(e){}})();
        """
    }
}
