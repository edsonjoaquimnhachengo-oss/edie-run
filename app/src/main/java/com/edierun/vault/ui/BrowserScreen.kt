package com.edierun.vault.ui

import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val scope = rememberCoroutineScope()
    val torState by TorManager.state.collectAsState()
    val torMessage by TorManager.message.collectAsState()
    val items by vault.items.collectAsState()
    var input by remember { mutableStateOf(controller.url) }
    var imageToSave by remember { mutableStateOf<String?>(null) }
    var shortcuts by remember { mutableStateOf(ShortcutStore.load(vault)) }
    var savingPage by remember { mutableStateOf(false) }

    fun saveShortcuts(list: List<Shortcut>) {
        shortcuts = list
        scope.launch(Dispatchers.IO) { ShortcutStore.save(vault, list) }
    }

    // Preferências do cofre ao abrir o navegador
    LaunchedEffect(Unit) {
        controller.searchBase = vault.getPref("search", "https://duckduckgo.com/?q=")
        if (controller.onHome && vault.getBoolPref("torDefault", false) && !controller.torWanted) {
            controller.torWanted = true
            TorManager.start(ctx)
        }
    }

    LaunchedEffect(torState, controller.torWanted) {
        controller.syncTor(torState)
    }

    LaunchedEffect(controller.url) {
        input = controller.url
    }

    // Quando um site com atalho envia o seu ícone, o ícone fica guardado no atalho
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

    BackHandler(enabled = !controller.onHome && controller.customView == null) {
        if (controller.canBack) controller.webView.goBack() else controller.home()
    }

    val currentHost = hostOf(controller.url)
    val isShortcut = currentHost.isNotEmpty() && shortcuts.any { it.host == currentHost }
    val onSite = !controller.onHome && controller.url.startsWith("http")

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
                        vault.importFile(file, "$title.mht", "multipart/related", thumb)
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
                    if (saved) "Página guardada no cofre. Abre-se sem internet." else "Não foi possível guardar esta página.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- barra superior: Tor, endereço, atalho
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val torColor = when {
                !controller.torWanted -> MaterialTheme.colorScheme.onSurfaceVariant
                torState == TorManager.State.READY && controller.proxyApplied -> Color(0xFF6FCF97)
                torState == TorManager.State.ERROR -> MaterialTheme.colorScheme.error
                else -> Color(0xFFF2C94C)
            }
            IconButton(onClick = {
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
            }) {
                Icon(EdieIcons.Shield, contentDescription = "Tor", tint = torColor)
            }
            PrivateTextField(
                value = input,
                onValueChange = { input = it },
                hint = "Pesquisar ou escrever endereço",
                modifier = Modifier.weight(1f),
                imeAction = EditorInfo.IME_ACTION_GO,
                onAction = { controller.load(input) }
            )
            IconButton(enabled = onSite, onClick = {
                if (isShortcut) {
                    saveShortcuts(shortcuts.filterNot { it.host == currentHost })
                    Toast.makeText(ctx, "Atalho removido", Toast.LENGTH_SHORT).show()
                } else {
                    val t = controller.title.ifBlank { currentHost }.take(40)
                    saveShortcuts(shortcuts + Shortcut(t, controller.url, controller.favicon?.let { ShortcutStore.scaleIcon(it) }))
                    Toast.makeText(ctx, "Adicionado à página inicial", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(
                    if (isShortcut) Icons.Filled.Star else EdieIcons.StarBorder,
                    contentDescription = "Atalho",
                    tint = if (isShortcut) Color(0xFFF2C94C) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (controller.progress in 1..99 && !controller.onHome) {
            LinearProgressIndicator(
                progress = { controller.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        // ---------- conteúdo
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { controller.attach() }
            )
            if (controller.onHome) {
                HomePage(
                    vault = vault,
                    shortcuts = shortcuts,
                    pages = items.filter { it.kind == Kind.PAGE }.take(12),
                    onOpenUrl = { controller.load(it) },
                    onOpenItem = onOpenItem,
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
        }

        // ---------- barra inferior com ícones
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Recuar", enabled = !controller.onHome) {
                if (controller.canBack) controller.webView.goBack() else controller.home()
            }
            ToolButton(Icons.AutoMirrored.Filled.ArrowForward, "Avançar", enabled = controller.canForward) {
                controller.webView.goForward()
            }
            ToolButton(Icons.Filled.Refresh, "Recarregar", enabled = onSite) {
                controller.webView.reload()
            }
            ToolButton(Icons.Filled.Home, "Início", enabled = !controller.onHome) {
                controller.home()
            }
            ToolButton(EdieIcons.SaveOffline, "Guardar offline", enabled = onSite && !savingPage) {
                savePage()
            }
            ToolButton(EdieIcons.Download, "Baixar vídeo", enabled = onSite, highlight = true) {
                val q = Quality.from(vault.getPref("quality", Quality.HIGH.name))
                DownloadManager.enqueueMedia(vault, controller.url, q)
                Toast.makeText(ctx, "Enviado para Downloads", Toast.LENGTH_SHORT).show()
                onSentToDownloads()
            }
        }
    }

    imageToSave?.let { src ->
        AlertDialog(
            onDismissRequest = { imageToSave = null },
            title = { Text("Guardar imagem no cofre?") },
            text = { Text(src.take(140), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    imageToSave = null
                    DownloadManager.enqueueDirect(vault, src, null, null)
                    Toast.makeText(ctx, "A guardar no cofre…", Toast.LENGTH_SHORT).show()
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { imageToSave = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                icon,
                contentDescription = label,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    highlight -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun TorOverlay(controller: BrowserController, torState: TorManager.State, torMessage: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(EdieIcons.Shield, contentDescription = null, tint = Color(0xFFF2C94C), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        when {
            controller.torUnsupported -> Text(
                "O WebView deste telemóvel é antigo e não permite usar o Tor. " +
                    "Actualize o \"Android System WebView\" na Play Store, ou desligue o Tor.",
                textAlign = TextAlign.Center
            )
            torState == TorManager.State.ERROR -> {
                Text(torMessage, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onRetry) { Text("Tentar outra vez") }
            }
            else -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(torMessage.ifEmpty { "A ligar à rede Tor…" }, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A navegação fica bloqueada até a ligação estar pronta, para o seu IP real não sair. " +
                        "A primeira ligação pode levar 30 a 90 segundos.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- página inicial

private val tileColors = listOf(
    Color(0xFF3A5F8F), Color(0xFF8F3A4E), Color(0xFF3A8F6B), Color(0xFF8F6B3A),
    Color(0xFF5E3A8F), Color(0xFF3A848F), Color(0xFF8F3A84), Color(0xFF6B8F3A)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomePage(
    vault: Vault,
    shortcuts: List<Shortcut>,
    pages: List<VaultItem>,
    onOpenUrl: (String) -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onRemove: (Shortcut) -> Unit,
    onAdd: (String, String) -> Unit
) {
    var toRemove by remember { mutableStateOf<Shortcut?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(EdieIcons.Globe, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Edie Run", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Navegação privada. Nada fica guardado ao fechar o cofre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        // atalhos em linhas de 4
        val cells: List<Shortcut?> = shortcuts + listOf(null) // o último é "Adicionar"
        cells.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { s ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { if (s == null) adding = true else onOpenUrl(s.url) },
                                onLongClick = { if (s != null) toRemove = s }
                            )
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ShortcutIcon(s)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s?.title ?: "Adicionar",
                            fontSize = 12.sp,
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
        Text("Páginas guardadas (offline)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (pages.isEmpty()) {
            Text(
                "Abra um site e toque no ícone de guardar offline (o círculo com o visto), na barra de baixo. " +
                    "A página fica cifrada no cofre e abre-se sem internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pages.forEach { p -> SavedPageCard(vault, p) { onOpenItem(p) } }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Todas as páginas guardadas estão também no Cofre, no filtro Páginas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    toRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remover \"${s.title}\"?") },
            text = { Text("O atalho sai da página inicial.") },
            confirmButton = { TextButton(onClick = { onRemove(s); toRemove = null }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancelar") } }
        )
    }

    if (adding) {
        var title by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Novo atalho") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrivateTextField(title, { title = it }, "Nome (ex.: Notícias)")
                    PrivateTextField(url, { url = it }, "Endereço (ex.: site.co.mz)")
                }
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = {
                    var u = url.trim()
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                    onAdd(title.trim().ifBlank { hostOf(u) }.take(40), u)
                    adding = false
                }) { Text("Adicionar") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ShortcutIcon(s: Shortcut?) {
    val bg = if (s == null) MaterialTheme.colorScheme.surfaceVariant
    else tileColors[(s.host.hashCode() and 0x7fffffff) % tileColors.size]
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (s?.icon != null) Color.White else bg),
        contentAlignment = Alignment.Center
    ) {
        when {
            s == null -> Icon(Icons.Filled.Add, contentDescription = null)
            s.icon != null -> Image(
                s.icon.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(32.dp)
            )
            else -> Text(
                s.title.take(1).uppercase(),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SavedPageCard(vault: Vault, item: VaultItem, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { vault.thumbnail(item) }
    }
    Column(
        Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickableCompat(onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(90.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            val b = bmp
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(EdieIcons.Globe, contentDescription = null, modifier = Modifier.align(Alignment.Center))
            }
        }
        Text(
            item.name.removeSuffix(".mht"),
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)
