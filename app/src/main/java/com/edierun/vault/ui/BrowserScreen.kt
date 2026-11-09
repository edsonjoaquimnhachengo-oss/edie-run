package com.edierun.vault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.edierun.vault.crypto.Crypto
import com.edierun.vault.download.DownloadManager
import com.edierun.vault.download.Quality
import com.edierun.vault.net.TorManager
import com.edierun.vault.vault.FileUtil
import com.edierun.vault.vault.Kind
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    controller: BrowserController,
    vault: Vault,
    onSentToDownloads: () -> Unit,
    onOpenItem: (VaultItem) -> Unit
) {
    val ctx = LocalContext.current
    val ios = LocalIos.current
    val scope = rememberCoroutineScope()
    val torState by TorManager.state.collectAsState()
    val torMessage by TorManager.message.collectAsState()
    val items by vault.items.collectAsState()
    var input by remember { mutableStateOf(controller.url) }
    var imageToSave by remember { mutableStateOf<String?>(null) }
    var shortcuts by remember { mutableStateOf(ShortcutStore.load(vault)) }
    var savingPage by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    val pages = remember(items) { items.filter { it.kind == Kind.PAGE }.sortedByDescending { it.added } }

    SideEffect { controller.overlayOpen = showTabs || showPages || showHistory || finding }

    fun saveShortcuts(list: List<Shortcut>) {
        shortcuts = list
        scope.launch(Dispatchers.IO) { ShortcutStore.save(vault, list) }
    }

    fun savePref(name: String, value: Any) {
        scope.launch(Dispatchers.IO) { vault.setPref(name, value) }
    }

    // preferências do cofre ao abrir o navegador
    LaunchedEffect(Unit) {
        controller.searchBase = vault.getPref("search", "https://duckduckgo.com/?q=")
        controller.configure(
            adBlock = vault.getBoolPref("adBlock", true),
            noImages = vault.getBoolPref("noImages", false),
            nightSites = vault.getBoolPref("nightSites", false),
            textZoom = vault.getPref("textZoom", "100").toIntOrNull() ?: 100
        )
        if (controller.onHome && vault.getBoolPref("torDefault", false) && !controller.torWanted) {
            controller.torWanted = true
            TorManager.start(ctx)
        }
    }

    LaunchedEffect(torState, controller.torWanted) { controller.syncTor(torState) }
    LaunchedEffect(controller.url, controller.currentIndex) { input = controller.url }

    // quando um site com favorito envia o seu ícone, o ícone fica guardado no favorito
    LaunchedEffect(controller.favicon, controller.url) {
        val icon = controller.favicon ?: return@LaunchedEffect
        val host = hostOf(controller.url)
        if (host.isEmpty()) return@LaunchedEffect
        val idx = shortcuts.indexOfFirst { it.host == host && it.icon == null }
        if (idx >= 0) {
            val list = shortcuts.toMutableList()
            list[idx] = list[idx].copy(icon = ShortcutStore.scaleIcon(icon))
            saveShortcuts(list)
        }
    }

    DisposableEffect(controller) {
        controller.onDownloadRequest = { url, mime, name ->
            DownloadManager.enqueueDirect(vault, url, name, mime)
            Toast.makeText(ctx, "A guardar no cofre: ${name ?: "ficheiro"}", Toast.LENGTH_SHORT).show()
        }
        controller.onImageLongPress = { src -> imageToSave = src }
        onDispose {
            controller.onDownloadRequest = null
            controller.onImageLongPress = null
        }
    }

    BackHandler(enabled = finding) {
        finding = false
        controller.clearFind()
    }
    BackHandler(enabled = !finding && !controller.overlayOpen && !controller.onHome && controller.customView == null) {
        if (controller.canBack) controller.webView.goBack() else controller.home()
    }

    val currentHost = hostOf(controller.url)
    val isShortcut = currentHost.isNotEmpty() && shortcuts.any { it.host == currentHost }
    val onSite = !controller.onHome && controller.url.startsWith("http")
    val videoHere = onSite && (controller.current.mediaUrl != null || AdBlock.isKnownVideoPage(controller.url))

    fun savePage() {
        if (savingPage || !onSite) return
        savingPage = true
        val dir = File(ctx.noBackupFilesDir, "tmp/pages").apply { mkdirs() }
        val file = File(dir, Crypto.hex(Crypto.randomBytes(8)) + ".mht")
        val thumb: Bitmap? = controller.snapshot()
        val title = controller.title.ifBlank { currentHost.ifBlank { "Página" } }
        controller.saveArchive(file) { ok ->
            if (!ok) {
                savingPage = false
                FileUtil.wipe(file)
                Toast.makeText(ctx, "Não foi possível guardar esta página.", Toast.LENGTH_LONG).show()
                return@saveArchive
            }
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        vault.importFile(file, "$title.mht", "multipart/related", thumb, source = "web")
                        true
                    } catch (_: Throwable) {
                        false
                    } finally {
                        FileUtil.wipe(file)
                    }
                }
                savingPage = false
                Toast.makeText(
                    ctx,
                    if (saved) "Guardada em Páginas offline. Abre-se sem internet." else "Não foi possível guardar esta página.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun downloadVideo() {
        val page = controller.url
        val media = controller.current.mediaUrl
        val q = Quality.from(vault.getPref("quality", Quality.HIGH.name))
        val directFile = media != null && !media.contains(".m3u8") && !media.contains(".mpd") && !media.contains("googlevideo")
        if (!AdBlock.isKnownVideoPage(page) && directFile) {
            DownloadManager.enqueueDirect(vault, media!!, null, null)
        } else {
            DownloadManager.enqueueMedia(vault, page, q)
        }
        Toast.makeText(ctx, "Enviado para Downloads", Toast.LENGTH_SHORT).show()
        onSentToDownloads()
    }

    fun toggleFavorite() {
        if (!onSite) return
        if (isShortcut) {
            saveShortcuts(shortcuts.filterNot { it.host == currentHost })
            Toast.makeText(ctx, "Removido dos favoritos", Toast.LENGTH_SHORT).show()
        } else {
            val t = controller.title.ifBlank { currentHost }.take(40)
            saveShortcuts(shortcuts + Shortcut(t, controller.url, controller.favicon?.let { ShortcutStore.scaleIcon(it) }))
            Toast.makeText(ctx, "Adicionado aos favoritos", Toast.LENGTH_SHORT).show()
        }
    }

    val toolbarSpace = LocalBottomInset.current + 66.dp

    Box(Modifier.fillMaxSize().background(ios.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = toolbarSpace)) {
            if (finding) {
                // ---------- procurar na página
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PrivateTextField(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            controller.find(it)
                        },
                        hint = "Procurar na página",
                        modifier = Modifier.weight(1f),
                        imeAction = EditorInfo.IME_ACTION_SEARCH,
                        onAction = { controller.findNext(true) }
                    )
                    Text(controller.findResult, style = IosType.footnote, color = ios.secondaryLabel, modifier = Modifier.padding(horizontal = 8.dp))
                    GlassIconButton(Icons.Filled.KeyboardArrowUp, "Anterior", size = 38.dp) { controller.findNext(false) }
                    Spacer(Modifier.width(6.dp))
                    GlassIconButton(Icons.Filled.KeyboardArrowDown, "Seguinte", size = 38.dp) { controller.findNext(true) }
                    Spacer(Modifier.width(6.dp))
                    IosTextButton("OK", bold = true) {
                        finding = false
                        controller.clearFind()
                    }
                }
            } else {
                // ---------- barra de endereço (Tor · endereço · favorito)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val torColor = when {
                        !controller.torWanted -> ios.secondaryLabel
                        torState == TorManager.State.READY && controller.proxyApplied -> ios.green
                        torState == TorManager.State.ERROR -> ios.red
                        else -> ios.orange
                    }
                    GlassIconButton(EdieIcons.Shield, "Tor", size = 42.dp, tint = torColor) {
                        if (controller.torWanted) {
                            controller.torWanted = false
                            if (!DownloadManager.hasActive()) TorManager.stop(ctx)
                            Toast.makeText(ctx, "Tor desligado", Toast.LENGTH_SHORT).show()
                        } else {
                            controller.torWanted = true
                            if (torState == TorManager.State.ERROR) TorManager.stop(ctx)
                            TorManager.start(ctx)
                            Toast.makeText(ctx, "A ligar o Tor…", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    PrivateTextField(
                        value = input,
                        onValueChange = { input = it },
                        hint = "Pesquisar ou escrever endereço",
                        modifier = Modifier.weight(1f),
                        imeAction = EditorInfo.IME_ACTION_GO,
                        onAction = { controller.load(input) }
                    )
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(
                        if (isShortcut) Icons.Filled.Star else EdieIcons.StarBorder,
                        "Favorito",
                        size = 42.dp,
                        tint = if (isShortcut) ios.yellow else null,
                        enabled = onSite
                    ) { toggleFavorite() }
                }
            }

            if (controller.progress in 1..99 && !controller.onHome) {
                LinearProgressIndicator(
                    progress = { controller.progress / 100f },
                    color = ios.accent,
                    trackColor = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }

            // ---------- conteúdo
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(controller.current.id) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { controller.attach() })
                }
                if (controller.onHome) {
                    HomePage(
                        vault = vault,
                        shortcuts = shortcuts,
                        pages = pages.take(10),
                        totalPages = pages.size,
                        onOpenUrl = { controller.load(it) },
                        onOpenItem = onOpenItem,
                        onAllPages = { showPages = true },
                        onRemove = { s -> saveShortcuts(shortcuts.filterNot { it.url == s.url }) },
                        onAdd = { title, url -> saveShortcuts(shortcuts + Shortcut(title, url)) }
                    )
                }
                if (controller.torWanted && !controller.proxyApplied) {
                    TorOverlay(controller, torState, torMessage, onRetry = {
                        TorManager.stop(ctx)
                        TorManager.start(ctx)
                    })
                }
                // botão de vídeo detectado, como no Opera Mini
                if (videoHere && !finding) Box(Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                    Row(
                        Modifier
                            .shadow(10.dp, CircleShape)
                            .clip(CircleShape)
                            .background(ios.accent)
                            .iosClick(pressedScale = 0.94f, pressedAlpha = 0.8f) { downloadVideo() }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(EdieIcons.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Baixar vídeo", color = Color.White, style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }

        // ---------- barra de ferramentas em vidro (recuar, avançar, início, separadores, menu)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 18.dp, end = 18.dp, bottom = LocalBottomInset.current)
                .fillMaxWidth()
                .height(54.dp)
                .glass(RoundedCornerShape(27.dp), haze = null)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Recuar", enabled = !controller.onHome) {
                if (controller.canBack) controller.webView.goBack() else controller.home()
            }
            ToolButton(Icons.AutoMirrored.Filled.ArrowForward, "Avançar", enabled = controller.canForward) {
                controller.webView.goForward()
            }
            ToolButton(if (onSite) Icons.Filled.Refresh else Icons.Filled.Home, if (onSite) "Recarregar" else "Início", enabled = true) {
                if (onSite) controller.webView.reload() else controller.home()
            }
            // separadores, com o número de separadores abertos
            Box(
                Modifier.size(44.dp).iosClick {
                    controller.captureSnapshot()
                    showTabs = true
                },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(22.dp).border(2.dp, ios.accent, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(controller.tabs.size.toString(), color = ios.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            ToolButton(Icons.Filled.Menu, "Menu", enabled = true) { showMenu = true }
        }

        // ---------- painéis por cima do navegador
        if (showTabs) {
            TabsSwitcher(
                controller = controller,
                onClose = { showTabs = false }
            )
        }
        if (showPages) {
            SavedPagesScreen(vault, pages, onOpenItem = onOpenItem, onClose = { showPages = false })
        }
        if (showHistory) {
            HistoryScreen(controller, onOpen = {
                showHistory = false
                controller.load(it)
            }, onClose = { showHistory = false })
        }
    }

    BackHandler(enabled = showTabs) { showTabs = false }
    BackHandler(enabled = showPages) { showPages = false }
    BackHandler(enabled = showHistory) { showHistory = false }

    if (showMenu) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheet,
            containerColor = ios.groupedBackground,
            contentColor = ios.label
        ) {
            BrowserMenu(
                controller = controller,
                onSite = onSite,
                isFavorite = isShortcut,
                savingPage = savingPage,
                pagesCount = pages.size,
                act = { action ->
                    showMenu = false
                    when (action) {
                        MenuAction.NEW_TAB -> controller.newTab()
                        MenuAction.TABS -> {
                            controller.captureSnapshot()
                            showTabs = true
                        }
                        MenuAction.SAVE_OFFLINE -> savePage()
                        MenuAction.OFFLINE_PAGES -> showPages = true
                        MenuAction.DOWNLOAD_VIDEO -> downloadVideo()
                        MenuAction.DOWNLOADS -> onSentToDownloads()
                        MenuAction.FAVORITE -> toggleFavorite()
                        MenuAction.SHARE -> {
                            Session.allowLeaving()
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, controller.url)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Partilhar link"))
                        }
                        MenuAction.COPY -> {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("link", controller.url))
                            Toast.makeText(ctx, "Link copiado", Toast.LENGTH_SHORT).show()
                        }
                        MenuAction.FIND -> {
                            findQuery = ""
                            finding = true
                        }
                        MenuAction.HISTORY -> showHistory = true
                    }
                },
                onAdBlock = {
                    controller.setAdBlockEnabled(it)
                    savePref("adBlock", it)
                },
                onNoImages = {
                    controller.setNoImagesEnabled(it)
                    savePref("noImages", it)
                },
                onNight = {
                    controller.setNightSitesEnabled(it)
                    savePref("nightSites", it)
                },
                onDesktop = { controller.toggleDesktop() },
                onZoom = { delta ->
                    controller.changeTextZoom(delta)
                    savePref("textZoom", controller.textZoom.toString())
                }
            )
        }
    }

    imageToSave?.let { src ->
        IosAlert(
            title = "Guardar imagem no cofre?",
            message = src.take(120),
            onDismiss = { imageToSave = null },
            actions = listOf(
                AlertAction("Cancelar") { imageToSave = null },
                AlertAction("Guardar", bold = true) {
                    imageToSave = null
                    DownloadManager.enqueueDirect(vault, src, null, null)
                    Toast.makeText(ctx, "A guardar no cofre…", Toast.LENGTH_SHORT).show()
                }
            )
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(Modifier.size(44.dp).iosClick(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = if (enabled) ios.accent else ios.tertiaryLabel, modifier = Modifier.size(24.dp))
    }
}

// ---------------------------------------------------------------- menu (estilo Opera Mini)

private enum class MenuAction { NEW_TAB, TABS, SAVE_OFFLINE, OFFLINE_PAGES, DOWNLOAD_VIDEO, DOWNLOADS, FAVORITE, SHARE, COPY, FIND, HISTORY }

@Composable
private fun BrowserMenu(
    controller: BrowserController,
    onSite: Boolean,
    isFavorite: Boolean,
    savingPage: Boolean,
    pagesCount: Int,
    act: (MenuAction) -> Unit,
    onAdBlock: (Boolean) -> Unit,
    onNoImages: (Boolean) -> Unit,
    onNight: (Boolean) -> Unit,
    onDesktop: () -> Unit,
    onZoom: (Int) -> Unit
) {
    val ios = LocalIos.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp)
    ) {
        val tiles = listOf(
            Triple(MenuAction.NEW_TAB, Icons.Filled.Add as ImageVector, "Novo separador"),
            Triple(MenuAction.TABS, EdieIcons.Tabs, "Separadores (${controller.tabs.size})"),
            Triple(MenuAction.SAVE_OFFLINE, EdieIcons.SaveOffline, if (savingPage) "A guardar…" else "Guardar offline"),
            Triple(MenuAction.OFFLINE_PAGES, EdieIcons.Folder, "Páginas offline ($pagesCount)"),
            Triple(MenuAction.DOWNLOAD_VIDEO, EdieIcons.Download, "Baixar vídeo"),
            Triple(MenuAction.DOWNLOADS, EdieIcons.Download, "Downloads"),
            Triple(MenuAction.FAVORITE, if (isFavorite) Icons.Filled.Star else EdieIcons.StarBorder, if (isFavorite) "Tirar dos favoritos" else "Favorito"),
            Triple(MenuAction.SHARE, Icons.Filled.Share, "Partilhar link"),
            Triple(MenuAction.COPY, EdieIcons.Copy, "Copiar link"),
            Triple(MenuAction.FIND, Icons.Filled.Search, "Procurar na página"),
            Triple(MenuAction.HISTORY, EdieIcons.History, "Histórico")
        )
        val needsSite = setOf(MenuAction.SAVE_OFFLINE, MenuAction.DOWNLOAD_VIDEO, MenuAction.FAVORITE, MenuAction.SHARE, MenuAction.COPY, MenuAction.FIND)
        tiles.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                row.forEach { (a, icon, label) ->
                    val enabled = !(a in needsSite && !onSite) && !(a == MenuAction.SAVE_OFFLINE && savingPage)
                    Column(
                        Modifier
                            .weight(1f)
                            .iosClick(enabled = enabled, pressedScale = 0.92f) { act(a) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(ios.cell),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = if (enabled) ios.accent else ios.tertiaryLabel, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            style = IosType.caption2,
                            color = if (enabled) ios.label else ios.tertiaryLabel,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(14.dp))
        IosGroup(
            header = "Velocidade e dados",
            footer = "Ao contrário do Opera Mini, o Edie Run não passa as suas páginas por servidores de terceiros: " +
                "a poupança vem de bloquear anúncios, rastreadores e imagens aqui no telemóvel. A sua navegação fica só consigo."
        ) {
            IosRow(
                "Bloqueador de anúncios",
                subtitle = "${controller.blockedCount} bloqueados nesta sessão",
                icon = EdieIcons.Block,
                iconBg = ios.red
            ) { IosSwitch(controller.adBlock, onAdBlock) }
            IosRow(
                "Poupança de dados",
                subtitle = "Não carrega imagens",
                icon = EdieIcons.NoImage,
                iconBg = ios.green,
                last = true
            ) { IosSwitch(controller.noImages, onNoImages) }
        }
        Spacer(Modifier.height(18.dp))
        IosGroup(header = "Visualização") {
            if (controller.nightSupported) {
                IosRow("Modo nocturno nos sites", subtitle = "Escurece sites claros (com o tema escuro)", icon = EdieIcons.Moon, iconBg = Color(0xFF5E5CE6)) {
                    IosSwitch(controller.nightSites, onNight)
                }
            }
            IosRow("Versão para computador", icon = EdieIcons.Desktop, iconBg = Color(0xFF8E8E93)) {
                IosSwitch(controller.current.desktop) { onDesktop() }
            }
            IosRow("Tamanho do texto", icon = EdieIcons.TextSize, iconBg = ios.orange, last = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("A−") { onZoom(-10) }
                    Text("${controller.textZoom}%", style = IosType.subhead, color = ios.label, modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
                    StepButton("A+") { onZoom(10) }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(
        Modifier.size(width = 44.dp, height = 32.dp).clip(RoundedCornerShape(8.dp)).background(ios.fill).iosClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
    }
}

// ---------------------------------------------------------------- separadores

@Composable
private fun TabsSwitcher(controller: BrowserController, onClose: () -> Unit) {
    val ios = LocalIos.current
    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${controller.tabs.size} ${if (controller.tabs.size == 1) "separador" else "separadores"}", style = IosType.title2, color = ios.label, modifier = Modifier.weight(1f))
                IosTextButton("Concluído", bold = true, onClick = onClose)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = LocalBottomInset.current + 80.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(controller.tabs, key = { _, t -> t.id }) { i, t ->
                    val selected = i == controller.currentIndex
                    Column(
                        Modifier
                            .animateItem()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ios.cell)
                            .border(if (selected) 2.5.dp else 0.dp, if (selected) ios.accent else Color.Transparent, RoundedCornerShape(14.dp))
                            .iosClick(pressedScale = 0.96f) {
                                controller.switchTo(i)
                                onClose()
                            }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (t.onHome) "Página inicial" else t.title.ifBlank { hostOf(t.url) },
                                style = IosType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = ios.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Box(Modifier.size(30.dp).iosClick { controller.closeTab(i) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = ios.secondaryLabel, modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(ios.fill), contentAlignment = Alignment.Center) {
                            val snap = t.snapshot
                            if (snap != null && !t.onHome) {
                                Image(snap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(EdieIcons.Globe, contentDescription = null, tint = ios.tertiaryLabel, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 18.dp, end = 18.dp, bottom = LocalBottomInset.current)
                .fillMaxWidth()
                .height(54.dp)
                .glass(RoundedCornerShape(27.dp), haze = null)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IosTextButton("Fechar todos", color = ios.red) {
                controller.closeAllTabs()
                onClose()
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(ios.accent).iosClick {
                    controller.newTab()
                    onClose()
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Novo separador", tint = Color.White)
            }
            IosTextButton("Concluído", bold = true, onClick = onClose)
        }
    }
}

// ---------------------------------------------------------------- páginas offline (pasta própria)

@Composable
private fun SavedPagesScreen(vault: Vault, pages: List<VaultItem>, onOpenItem: (VaultItem) -> Unit, onClose: () -> Unit) {
    val ios = LocalIos.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<VaultItem?>(null) }
    val shown = remember(pages, query) {
        if (query.isBlank()) pages else pages.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Voltar", size = 42.dp, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Páginas offline", style = IosType.title2, color = ios.label)
                    Text("${pages.size} guardadas · abrem sem internet", style = IosType.footnote, color = ios.secondaryLabel)
                }
            }
            PrivateTextField(query, { query = it }, "Procurar", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            if (shown.isEmpty()) {
                Text(
                    if (pages.isEmpty()) "Ainda não guardou páginas. Num site, abra o menu e toque em \"Guardar offline\"."
                    else "Nada encontrado.",
                    style = IosType.subhead,
                    color = ios.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(40.dp)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomInset.current + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { it.id }) { p ->
                        PageRow(vault, p, onClick = { onOpenItem(p) }, onLongClick = { toDelete = p })
                    }
                }
            }
        }
    }
    toDelete?.let { p ->
        IosAlert(
            title = "Apagar esta página?",
            message = p.name.removeSuffix(".mht"),
            onDismiss = { toDelete = null },
            actions = listOf(
                AlertAction("Cancelar", bold = true) { toDelete = null },
                AlertAction("Apagar", destructive = true) {
                    toDelete = null
                    scope.launch(Dispatchers.IO) { vault.delete(setOf(p.id)) }
                }
            )
        )
    }
}

@Composable
private fun PageRow(vault: Vault, item: VaultItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ios = LocalIos.current
    val bmp by produceState<Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { vault.thumbnail(item) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ios.cell)
            .iosClick(pressedAlpha = 0.6f, onLongClick = onLongClick, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 64.dp, height = 80.dp).clip(RoundedCornerShape(8.dp)).background(ios.fill), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(EdieIcons.Globe, contentDescription = null, tint = ios.secondaryLabel)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name.removeSuffix(".mht"), style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("${Dates.dayLabel(item.added)} · ${FileUtil.humanSize(item.size)}", style = IosType.footnote, color = ios.secondaryLabel)
        }
    }
}

// ---------------------------------------------------------------- histórico (só desta sessão)

@Composable
private fun HistoryScreen(controller: BrowserController, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val ios = LocalIos.current
    val entries = controller.history.reversed()
    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Voltar", size = 42.dp, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Histórico", style = IosType.title2, color = ios.label)
                    Text("Só desta sessão. Apaga-se ao fechar o cofre.", style = IosType.footnote, color = ios.secondaryLabel)
                }
                if (entries.isNotEmpty()) IosTextButton("Limpar", color = ios.red) { controller.history.clear() }
            }
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomInset.current + 20.dp)) {
                if (entries.isEmpty()) {
                    item {
                        Text("Ainda não visitou nenhum site nesta sessão.", style = IosType.subhead, color = ios.secondaryLabel, modifier = Modifier.padding(32.dp))
                    }
                } else {
                    item {
                        IosGroup {
                            entries.forEachIndexed { i, e ->
                                IosRow(
                                    e.title.ifBlank { e.url },
                                    subtitle = "${Dates.time(e.time)} · ${hostOf(e.url)}",
                                    last = i == entries.lastIndex,
                                    onClick = { onOpen(e.url) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TorOverlay(controller: BrowserController, torState: TorManager.State, torMessage: String, onRetry: () -> Unit) {
    val ios = LocalIos.current
    Column(
        Modifier
            .fillMaxSize()
            .background(ios.background.copy(alpha = 0.97f))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(EdieIcons.Shield, contentDescription = null, tint = ios.orange, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        when {
            controller.torUnsupported -> Text(
                "O WebView deste telemóvel é antigo e não permite usar o Tor. " +
                    "Actualize o \"Android System WebView\" na Play Store, ou desligue o Tor.",
                textAlign = TextAlign.Center,
                color = ios.label
            )
            torState == TorManager.State.ERROR -> {
                Text(torMessage, textAlign = TextAlign.Center, color = ios.label)
                Spacer(Modifier.height(14.dp))
                IosPrimaryButton("Tentar outra vez", modifier = Modifier.width(220.dp), onClick = onRetry)
            }
            else -> {
                CircularProgressIndicator(color = ios.accent)
                Spacer(Modifier.height(16.dp))
                Text(torMessage.ifEmpty { "A ligar à rede Tor…" }, textAlign = TextAlign.Center, style = IosType.headline, color = ios.label)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A navegação fica bloqueada até a ligação estar pronta, para o seu IP real não sair. " +
                        "A primeira ligação pode levar 30 a 90 segundos.",
                    textAlign = TextAlign.Center,
                    style = IosType.footnote,
                    color = ios.secondaryLabel
                )
            }
        }
    }
}

// ---------------------------------------------------------------- página inicial

@Composable
private fun HomePage(
    vault: Vault,
    shortcuts: List<Shortcut>,
    pages: List<VaultItem>,
    totalPages: Int,
    onOpenUrl: (String) -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onAllPages: () -> Unit,
    onRemove: (Shortcut) -> Unit,
    onAdd: (String, String) -> Unit
) {
    val ios = LocalIos.current
    var toRemove by remember { mutableStateOf<Shortcut?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp)
    ) {
        Text("Favoritos", style = IosType.title2, color = ios.label, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(14.dp))
        val cells: List<Shortcut?> = shortcuts + listOf(null)
        cells.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                row.forEach { s ->
                    Column(
                        Modifier
                            .weight(1f)
                            .iosClick(
                                pressedAlpha = 0.6f,
                                pressedScale = 0.94f,
                                onLongClick = { if (s != null) toRemove = s },
                                onClick = { if (s == null) adding = true else onOpenUrl(s.url) }
                            )
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ShortcutIcon(s)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s?.title ?: "Adicionar",
                            style = IosType.caption,
                            color = ios.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(EdieIcons.Folder, contentDescription = null, tint = ios.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Páginas offline", style = IosType.title2, color = ios.label, modifier = Modifier.weight(1f))
            if (totalPages > 0) IosTextButton("Ver todas ($totalPages)", onClick = onAllPages)
        }
        Spacer(Modifier.height(10.dp))
        if (pages.isEmpty()) {
            Text(
                "Num site, abra o menu e toque em \"Guardar offline\". A página fica cifrada no cofre e abre-se sem internet.",
                style = IosType.footnote,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pages.forEach { p -> SavedPageCard(vault, p) { onOpenItem(p) } }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Navegação privada: nada fica guardado quando fecha o cofre.",
            style = IosType.footnote,
            color = ios.tertiaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }

    toRemove?.let { s ->
        IosAlert(
            title = "Remover \"${s.title}\"?",
            message = "Sai dos favoritos da página inicial.",
            onDismiss = { toRemove = null },
            actions = listOf(
                AlertAction("Cancelar", bold = true) { toRemove = null },
                AlertAction("Remover", destructive = true) {
                    onRemove(s)
                    toRemove = null
                }
            )
        )
    }

    if (adding) {
        var title by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        IosAlert(
            title = "Novo favorito",
            onDismiss = { adding = false },
            actions = listOf(
                AlertAction("Cancelar") { adding = false },
                AlertAction("Adicionar", bold = true, enabled = url.isNotBlank()) {
                    var u = url.trim()
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                    onAdd(title.trim().ifBlank { hostOf(u) }.take(40), u)
                    adding = false
                }
            )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrivateTextField(title, { title = it }, "Nome (ex.: Notícias)", modifier = Modifier.fillMaxWidth())
                PrivateTextField(url, { url = it }, "Endereço (ex.: site.co.mz)", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private val tileColors = listOf(
    Color(0xFF3A5F8F), Color(0xFF8F3A4E), Color(0xFF3A8F6B), Color(0xFF8F6B3A),
    Color(0xFF5E3A8F), Color(0xFF3A848F), Color(0xFF8F3A84), Color(0xFF6B8F3A)
)

@Composable
private fun ShortcutIcon(s: Shortcut?) {
    val ios = LocalIos.current
    val bg = when {
        s == null -> ios.fill
        s.icon != null -> ios.cell
        else -> tileColors[(s.host.hashCode() and 0x7fffffff) % tileColors.size]
    }
    Box(
        Modifier
            .size(64.dp)
            .shadow(if (ios.isDark || s == null) 0.dp else 4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        when {
            s == null -> Icon(Icons.Filled.Add, contentDescription = null, tint = ios.secondaryLabel, modifier = Modifier.size(28.dp))
            s.icon != null -> Image(s.icon.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
            else -> Text(s.title.take(1).uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SavedPageCard(vault: Vault, item: VaultItem, onClick: () -> Unit) {
    val ios = LocalIos.current
    val bmp by produceState<Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { vault.thumbnail(item) }
    }
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ios.cell)
            .iosClick(pressedAlpha = 0.6f, pressedScale = 0.96f, onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(ios.fill)) {
            val b = bmp
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(EdieIcons.Globe, contentDescription = null, tint = ios.secondaryLabel, modifier = Modifier.align(Alignment.Center))
            }
        }
        Text(
            item.name.removeSuffix(".mht"),
            style = IosType.caption.copy(fontWeight = FontWeight.Medium),
            color = ios.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp)
        )
    }
}
