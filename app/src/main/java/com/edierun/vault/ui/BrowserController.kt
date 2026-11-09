package com.edierun.vault.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.mutableStateListOf
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

/** Um separador do navegador. */
class BrowserTab(val id: Long) {
    lateinit var webView: WebView
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var onHome by mutableStateOf(true)
    var favicon by mutableStateOf<Bitmap?>(null)
    /** Último vídeo detectado na página (ficheiro ou lista de reprodução). */
    var mediaUrl by mutableStateOf<String?>(null)
    var desktop by mutableStateOf(false)
    var snapshot by mutableStateOf<Bitmap?>(null)
}

data class HistoryEntry(val url: String, val title: String, val time: Long)

/**
 * O navegador Edie Run. Sempre privado: o histórico e a cache só existem durante a sessão
 * e são apagados quando o cofre fecha. Com o Tor ligado, todo o tráfego passa pela rede Tor.
 *
 * Velocidade: cache de sessão activa, bloqueador de anúncios local, imagens mistas permitidas
 * (fora do modo Tor), pré-renderização fora do ecrã. Nada passa por servidores de terceiros.
 */
class BrowserController(private val context: Context) {
    val tabs = mutableStateListOf<BrowserTab>()
    var currentIndex by mutableIntStateOf(0)
        private set
    val current: BrowserTab get() = tabs[currentIndex.coerceIn(0, tabs.lastIndex)]

    // atalhos para o separador actual
    val url: String get() = current.url
    val title: String get() = current.title
    val progress: Int get() = current.progress
    val canBack: Boolean get() = current.canBack
    val canForward: Boolean get() = current.canForward
    val onHome: Boolean get() = current.onHome
    val favicon: Bitmap? get() = current.favicon
    val webView: WebView get() = current.webView

    var customView by mutableStateOf<View?>(null)
        private set
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** Há um painel aberto por cima do navegador (separadores, menu, páginas...). */
    var overlayOpen by mutableStateOf(false)

    var torWanted by mutableStateOf(false)
    var proxyApplied by mutableStateOf(false)
        private set
    var torUnsupported by mutableStateOf(false)
        private set

    @Volatile private var blockAll = false
    @Volatile private var httpsOnly = false

    // ---------- opções (estado para a interface + cópia volátil para as linhas de fundo)
    var adBlock by mutableStateOf(true)
        private set
    @Volatile private var adBlockOn = true
    var noImages by mutableStateOf(false)
        private set
    var nightSites by mutableStateOf(false)
        private set
    var textZoom by mutableIntStateOf(100)
        private set
    var blockedCount by mutableIntStateOf(0)
        private set

    val history = mutableStateListOf<HistoryEntry>()
    var findResult by mutableStateOf("")
        private set

    var searchBase = "https://duckduckgo.com/?q="
    var onDownloadRequest: ((url: String, mime: String?, name: String?) -> Unit)? = null
    var onImageLongPress: ((String) -> Unit)? = null

    private val direct = Executor { it.run() }
    private val main = Handler(Looper.getMainLooper())
    private var pendingLoad: String? = null
    private var nextId = 1L

    val nightSupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)

    init {
        newTab()
    }

    // ---------------------------------------------------------------- separadores

    fun newTab(open: String? = null) {
        val tab = BrowserTab(nextId++)
        tab.webView = createWebView(tab)
        tabs.add(tab)
        currentIndex = tabs.lastIndex
        if (open != null) load(open)
    }

    fun switchTo(index: Int) {
        if (index in tabs.indices) currentIndex = index
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val t = tabs[index]
        tabs.removeAt(index)
        destroyWebView(t.webView)
        if (tabs.isEmpty()) {
            newTab()
        } else {
            if (index < currentIndex || currentIndex > tabs.lastIndex) currentIndex = (currentIndex - 1).coerceAtLeast(0)
        }
    }

    fun closeAllTabs() {
        val old = tabs.toList()
        tabs.clear()
        old.forEach { destroyWebView(it.webView) }
        newTab()
    }

    fun captureSnapshot() {
        current.snapshot = snapshot(400)
    }

    // ---------------------------------------------------------------- criação do WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView {
        val wv = WebView(context)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // cache só desta sessão (apagada ao fechar o cofre): torna as páginas muito mais rápidas
        s.cacheMode = WebSettings.LOAD_DEFAULT
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
        s.loadsImagesAutomatically = true
        s.offscreenPreRaster = true
        applySettings(wv, tab)
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
                val u = request.url
                val scheme = u.scheme?.lowercase()
                if (blockAll || (httpsOnly && scheme == "http")) return empty()
                if (adBlockOn && !request.isForMainFrame && AdBlock.isBlocked(u)) {
                    main.post { blockedCount++ }
                    return empty()
                }
                val str = u.toString()
                if (!request.isForMainFrame && AdBlock.looksLikeMedia(str)) {
                    main.post { tab.mediaUrl = str }
                }
                return null
            }

            override fun onPageStarted(view: WebView, u: String?, icon: Bitmap?) {
                if (u == null || u.startsWith("data:")) return
                if (u == "about:blank") {
                    tab.onHome = true
                    tab.url = ""
                } else {
                    tab.onHome = false
                    if (u != tab.url) {
                        tab.favicon = null
                        tab.mediaUrl = null
                    }
                    tab.url = u
                }
                refreshNav(tab)
            }

            override fun onPageFinished(view: WebView, u: String?) {
                refreshNav(tab)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                if (u == null || u.startsWith("data:")) return
                if (u == "about:blank") {
                    tab.onHome = true
                    tab.url = ""
                } else {
                    tab.onHome = false
                    tab.url = u
                    if (!isReload && (history.isEmpty() || history.last().url != u)) {
                        history.add(HistoryEntry(u, view.title ?: u, System.currentTimeMillis()))
                        if (history.size > 300) history.removeAt(0)
                    }
                }
                refreshNav(tab)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                tab.title = t ?: ""
                if (t != null && history.isNotEmpty() && history.last().url == tab.url) {
                    history[history.lastIndex] = history.last().copy(title = t)
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon != null) tab.favicon = icon
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

        wv.setFindListener { active, count, _ ->
            if (tab === current) findResult = if (count == 0) "0/0" else "${active + 1}/$count"
        }
        return wv
    }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY))

    private fun applySettings(wv: WebView, tab: BrowserTab) {
        val s = wv.settings
        s.blockNetworkImage = noImages
        s.textZoom = textZoom
        s.userAgentString = if (tab.desktop) DESKTOP_UA else DownloadManager.USER_AGENT
        // imagens http dentro de sites https: permitidas (senão muitas imagens não aparecem), excepto no modo Tor
        s.mixedContentMode = if (httpsOnly) WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (nightSupported) {
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, nightSites)
            } catch (_: Throwable) { }
        }
    }

    private fun applyAll() {
        tabs.forEach { applySettings(it.webView, it) }
    }

    private fun refreshNav(tab: BrowserTab) {
        tab.canBack = tab.webView.canGoBack()
        tab.canForward = tab.webView.canGoForward()
    }

    // ---------------------------------------------------------------- opções

    fun configure(adBlock: Boolean, noImages: Boolean, nightSites: Boolean, textZoom: Int) {
        this.adBlock = adBlock
        adBlockOn = adBlock
        this.noImages = noImages
        this.nightSites = nightSites
        this.textZoom = textZoom.coerceIn(60, 200)
        applyAll()
    }

    fun setAdBlockEnabled(on: Boolean) {
        adBlock = on
        adBlockOn = on
        if (!onHome) webView.reload()
    }

    fun setNoImagesEnabled(on: Boolean) {
        noImages = on
        applyAll()
        if (!onHome) webView.reload()
    }

    fun setNightSitesEnabled(on: Boolean) {
        nightSites = on
        applyAll()
    }

    fun changeTextZoom(delta: Int) {
        textZoom = (textZoom + delta).coerceIn(60, 200)
        applyAll()
    }

    fun toggleDesktop() {
        val t = current
        t.desktop = !t.desktop
        applySettings(t.webView, t)
        if (!t.onHome) t.webView.reload()
    }

    fun find(query: String) {
        if (query.isBlank()) {
            webView.clearMatches()
            findResult = ""
        } else {
            webView.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) = webView.findNext(forward)

    fun clearFind() {
        webView.clearMatches()
        findResult = ""
    }

    // ---------------------------------------------------------------- navegação

    fun attach(): WebView {
        val wv = webView
        (wv.parent as? ViewGroup)?.removeView(wv)
        return wv
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
        val t = current
        t.onHome = false
        t.url = target
        if (blockAll) {
            pendingLoad = target
            return
        }
        t.webView.loadUrl(target)
    }

    fun home() {
        val t = current
        t.webView.stopLoading()
        t.onHome = true
        t.url = ""
        t.title = ""
        t.favicon = null
        t.mediaUrl = null
        t.progress = 100
        if (t.webView.url != null && t.webView.url != "about:blank") t.webView.loadUrl("about:blank")
    }

    fun snapshot(maxWidth: Int = 360): Bitmap? {
        val wv = webView
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) return null
        return try {
            val f = maxWidth.toFloat() / w
            val bmp = Bitmap.createBitmap(maxWidth, (h * f).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(f, f)
            wv.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    fun saveArchive(file: java.io.File, done: (Boolean) -> Unit) {
        try {
            webView.saveWebArchive(file.absolutePath, false) { path -> done(path != null && file.length() > 0) }
        } catch (_: Throwable) {
            done(false)
        }
    }

    // ---------------------------------------------------------------- Tor

    fun syncTor(state: TorManager.State) {
        if (torWanted) {
            if (!httpsOnly) {
                httpsOnly = true
                applyAll()
            }
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
                    main.post {
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
            if (httpsOnly) {
                httpsOnly = false
                applyAll()
            }
            if (proxyApplied) {
                ProxyController.getInstance().clearProxyOverride(direct) {
                    main.post {
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
        tabs.forEach { it.webView.clearCache(true) }
    }

    // ---------------------------------------------------------------- limpeza

    /** Apaga tudo o que o navegador acumulou nesta sessão (cache, cookies, histórico, formulários). */
    fun wipe() {
        try {
            exitFullscreen()
            tabs.forEach { t ->
                t.webView.stopLoading()
                t.webView.clearHistory()
                t.webView.clearCache(true)
                t.webView.clearFormData()
                t.webView.clearMatches()
            }
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
        history.clear()
        blockedCount = 0
    }

    private fun destroyWebView(wv: WebView) {
        try {
            wv.stopLoading()
            wv.clearHistory()
            wv.clearCache(true)
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    fun destroy() {
        wipe()
        tabs.forEach { destroyWebView(it.webView) }
        tabs.clear()
    }

    companion object {
        private val EMPTY = ByteArray(0)

        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

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
