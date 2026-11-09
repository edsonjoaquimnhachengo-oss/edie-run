package com.edierun.vault.ui

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * O cofre aberto. Protegido contra capturas de ecrã e gravação, e com a miniatura
 * em branco na lista de apps recentes. Fecha-se (e apaga a chave da memória) assim
 * que a app deixa de estar visível.
 */
class VaultActivity : ComponentActivity() {
    private var browser: BrowserController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val vault = Session.vault
        if (vault == null) {
            finish()
            return
        }
        AppTheme.load(vault)
        val controller = BrowserController(this)
        browser = controller
        setContent {
            EdieTheme {
                VaultRoot(vault, controller, onLock = { lockNow() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Session.vault == null) finish()
        Session.clearAllowLeaving()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !Session.leavingAllowed) lockNow()
    }

    private fun lockNow() {
        browser?.wipe()
        Session.lock(applicationContext)
        AppTheme.reset()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        browser?.destroy()
        browser = null
        super.onDestroy()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    GALLERY("Cofre", EdieIcons.Photos),
    BROWSER("Edie Run", EdieIcons.Globe),
    DOWNLOADS("Downloads", EdieIcons.Download),
    SETTINGS("Definições", Icons.Filled.Settings)
}

private val TAB_BAR_HEIGHT = 64.dp

@Composable
private fun VaultRoot(vault: Vault, browser: BrowserController, onLock: () -> Unit) {
    val ios = LocalIos.current
    var tab by rememberSaveable { mutableStateOf(Tab.GALLERY) }
    var viewing by remember { mutableStateOf<ViewerRequest?>(null) }
    // guarda o último pedido para a animação de saída ainda ter o que mostrar
    val lastViewing = remember { arrayOfNulls<ViewerRequest>(1) }
    var downloadPrefill by remember { mutableStateOf("") }
    val rootHaze = rememberHazeState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = navBottom + TAB_BAR_HEIGHT + 16.dp

    viewing?.let { lastViewing[0] = it }

    LaunchedEffect(Unit) {
        Session.pendingUrl?.let {
            downloadPrefill = it
            Session.pendingUrl = null
            tab = Tab.DOWNLOADS
        }
    }

    Box(Modifier.fillMaxSize().background(ios.background)) {
        CompositionLocalProvider(LocalBottomInset provides bottomInset) {
            Box(Modifier.fillMaxSize().hazeSource(rootHaze)) {
                when (tab) {
                    Tab.GALLERY -> GalleryScreen(vault, onOpen = { viewing = it })
                    Tab.BROWSER -> BrowserScreen(
                        browser,
                        vault,
                        onSentToDownloads = { tab = Tab.DOWNLOADS },
                        onOpenItem = { item -> viewing = ViewerRequest(Album.PAGE, item.id) }
                    )
                    Tab.DOWNLOADS -> DownloadsScreen(vault, downloadPrefill, onPrefillUsed = { downloadPrefill = "" })
                    Tab.SETTINGS -> SettingsScreen(vault, onLock)
                }
            }
        }

        // ---------- barra de separadores flutuante, em vidro
        CompositionLocalProvider(LocalHaze provides rootHaze) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 18.dp, end = 18.dp, bottom = navBottom + 10.dp)
                    .fillMaxWidth()
                    .height(TAB_BAR_HEIGHT)
                    .glass(RoundedCornerShape(32.dp))
                    .padding(5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tab.entries.forEach { t ->
                    TabItem(t, selected = tab == t, modifier = Modifier.weight(1f)) { tab = t }
                }
            }
        }

        // ---------- visualizador em ecrã inteiro
        AnimatedVisibility(
            visible = viewing != null,
            enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.92f),
            exit = fadeOut(tween(200)) + scaleOut(tween(220), targetScale = 0.92f)
        ) {
            lastViewing[0]?.let { req ->
                key(req) {
                    ViewerScreen(vault, req, onClose = { viewing = null })
                }
            }
        }

        // ---------- vídeo de um site em ecrã inteiro
        browser.customView?.let { v ->
            BackHandler { browser.exitFullscreen() }
            key(v) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            (v.parent as? ViewGroup)?.removeView(v)
                            addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    },
                    onRelease = { frame -> frame.removeAllViews() }
                )
            }
        }
    }

    BackHandler(
        enabled = viewing == null && browser.customView == null && tab != Tab.GALLERY &&
            !(tab == Tab.BROWSER && (!browser.onHome || browser.overlayOpen))
    ) {
        tab = Tab.GALLERY
    }
}

@Composable
private fun TabItem(t: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ios = LocalIos.current
    val selAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "tab")
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(27.dp))
            .background(ios.fill.copy(alpha = ios.fill.alpha * selAlpha))
            .iosClick(pressedScale = 0.92f, pressedAlpha = 0.7f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                t.icon,
                contentDescription = t.label,
                tint = if (selected) ios.accent else ios.label,
                modifier = Modifier.size(24.dp)
            )
            Text(
                t.label,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) ios.accent else ios.label,
                maxLines = 1
            )
        }
    }
}
