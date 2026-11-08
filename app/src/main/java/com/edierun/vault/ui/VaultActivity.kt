package com.edierun.vault.ui

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.viewinterop.AndroidView
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultItem

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
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        browser?.destroy()
        browser = null
        super.onDestroy()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    GALLERY("Cofre", Icons.Filled.Lock),
    BROWSER("Edie Run", Icons.Filled.Search),
    DOWNLOADS("Downloads", Icons.Filled.KeyboardArrowDown),
    SETTINGS("Definições", Icons.Filled.Settings)
}

@Composable
private fun VaultRoot(vault: Vault, browser: BrowserController, onLock: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.GALLERY) }
    var viewing by remember { mutableStateOf<VaultItem?>(null) }
    var downloadPrefill by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        Session.pendingUrl?.let {
            downloadPrefill = it
            Session.pendingUrl = null
            tab = Tab.DOWNLOADS
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.GALLERY -> GalleryScreen(vault, onOpen = { viewing = it })
                    Tab.BROWSER -> BrowserScreen(
                        browser,
                        vault,
                        onSentToDownloads = { tab = Tab.DOWNLOADS },
                        onOpenItem = { viewing = it }
                    )
                    Tab.DOWNLOADS -> DownloadsScreen(vault, downloadPrefill, onPrefillUsed = { downloadPrefill = "" })
                    Tab.SETTINGS -> SettingsScreen(vault, onLock)
                }
            }
        }

        viewing?.let { item ->
            ViewerScreen(
                vault = vault,
                item = item,
                onClose = { viewing = null },
                onNavigate = { viewing = it }
            )
        }

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
            !(tab == Tab.BROWSER && !browser.onHome)
    ) {
        tab = Tab.GALLERY
    }
}
