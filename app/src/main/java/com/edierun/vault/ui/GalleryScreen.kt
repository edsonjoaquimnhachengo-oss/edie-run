package com.edierun.vault.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edierun.vault.vault.FileUtil
import com.edierun.vault.vault.Kind
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultItem
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Álbuns automáticos, como na app Fotografias do iPhone. */
enum class Album(val title: String) {
    ALL("Biblioteca"),
    FAV("Favoritos"),
    VIDEO("Vídeos"),
    IMAGE("Fotografias"),
    AUDIO("Áudio"),
    PAGE("Páginas guardadas"),
    DL("Baixados da internet"),
    IMP("Importados")
}

/** Itens de um álbum, do mais antigo ao mais recente (o mais recente fica em baixo, como no iPhone). */
fun albumItems(items: List<VaultItem>, album: Album): List<VaultItem> {
    val f = when (album) {
        Album.ALL -> items.filter { it.kind != Kind.PAGE }
        Album.FAV -> items.filter { it.fav }
        Album.VIDEO -> items.filter { it.kind == Kind.VIDEO }
        Album.IMAGE -> items.filter { it.kind == Kind.IMAGE }
        Album.AUDIO -> items.filter { it.kind == Kind.AUDIO }
        Album.PAGE -> items.filter { it.kind == Kind.PAGE }
        Album.DL -> items.filter { it.src == "dl" }
        Album.IMP -> items.filter { it.src == "imp" }
    }
    return f.sortedBy { it.added }
}

data class ViewerRequest(val album: Album, val startId: String)

private val COLUMN_STEPS = listOf(3, 5, 7)

@Composable
fun GalleryScreen(vault: Vault, onOpen: (ViewerRequest) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val ios = LocalIos.current
    val scope = rememberCoroutineScope()
    val items by vault.items.collectAsState()
    var collections by rememberSaveable { mutableStateOf(false) }
    var openAlbum by rememberSaveable { mutableStateOf<Album?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var columns by remember { mutableIntStateOf(vault.getPref("cols", "3").toIntOrNull()?.takeIf { it in COLUMN_STEPS } ?: 3) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var originals by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VaultItem?>(null) }
    var exportTarget by remember { mutableStateOf<VaultItem?>(null) }
    var restoreItems by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    val restorer = rememberGalleryRestorer(vault)
    val haze = rememberHazeState()
    val bottomInset = LocalBottomInset.current

    val album = openAlbum ?: if (collections) null else Album.ALL

    fun endSelection() {
        selecting = false
        selection = emptySet()
    }

    BackHandler(enabled = selecting) { endSelection() }
    BackHandler(enabled = !selecting && openAlbum != null) { openAlbum = null }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = mutableListOf<Uri>()
            var failed = 0
            uris.forEachIndexed { i, uri ->
                importStatus = "A cifrar ${i + 1} de ${uris.size}…"
                try {
                    withContext(Dispatchers.IO) { importUri(ctx, vault, uri) }
                    ok += uri
                } catch (_: Throwable) {
                    failed++
                }
            }
            importStatus = null
            if (failed > 0) Toast.makeText(ctx, "$failed ficheiro(s) não puderam ser importados", Toast.LENGTH_SHORT).show()
            if (ok.isNotEmpty()) originals = ok
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = exportTarget
        exportTarget = null
        if (uri != null && item != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { vault.exportTo(item, it) } != null
                    } catch (_: Throwable) {
                        false
                    }
                }
                Toast.makeText(
                    ctx,
                    if (ok) "Exportado. Essa cópia está fora do cofre e sem cifra." else "Não foi possível exportar.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun startImport() {
        Session.allowLeaving()
        picker.launch(arrayOf("image/*", "video/*", "audio/*"))
    }

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().background(ios.background)) {
            val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                (if (openAlbum == null) 118.dp else 70.dp)

            // ---------- conteúdo (fonte do desfoque)
            Box(Modifier.fillMaxSize().hazeSource(haze)) {
                if (album != null) {
                    val list = remember(items, album) { albumItems(items, album) }
                    val currentColumns by rememberUpdatedState(columns)
                    PhotoGrid(
                        vault = vault,
                        album = album,
                        list = list,
                        columns = columns,
                        topPad = topPad,
                        bottomPad = bottomInset + 70.dp,
                        selecting = selecting,
                        selection = selection,
                        onPinch = { zoomIn ->
                            val i = COLUMN_STEPS.indexOf(currentColumns)
                            val next = if (zoomIn) COLUMN_STEPS.getOrNull(i - 1) else COLUMN_STEPS.getOrNull(i + 1)
                            if (next != null) {
                                columns = next
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                scope.launch(Dispatchers.IO) { vault.setPref("cols", next.toString()) }
                            }
                        },
                        onClick = { item ->
                            if (selecting) {
                                selection = if (item.id in selection) selection - item.id else selection + item.id
                            } else {
                                onOpen(ViewerRequest(album, item.id))
                            }
                        },
                        onLongClick = { item ->
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            selecting = true
                            selection = selection + item.id
                        },
                        onImport = { startImport() }
                    )
                } else {
                    CollectionsView(
                        vault = vault,
                        items = items,
                        topPad = topPad,
                        bottomPad = bottomInset + 24.dp,
                        onOpenAlbum = { openAlbum = it }
                    )
                }
            }

            // ---------- cabeçalho com efeito de borda (o conteúdo desvanece por baixo)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(topPad + 24.dp)
                    .background(Brush.verticalGradient(listOf(ios.background, ios.background.copy(alpha = 0.92f), ios.background.copy(alpha = 0f))))
            )
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                if (openAlbum != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Voltar", size = 44.dp) {
                            endSelection()
                            openAlbum = null
                        }
                        Text(
                            openAlbum!!.title,
                            style = IosType.headline,
                            color = ios.label,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (selecting) GlassPill("Cancelar") { endSelection() }
                        else GlassPill("Seleccionar") { selecting = true }
                    }
                } else {
                    val subtitle = when {
                        selecting -> if (selection.isEmpty()) "Seleccione itens" else "${selection.size} seleccionado(s)"
                        collections -> "${items.count { it.kind != Kind.PAGE }} itens"
                        items.none { it.kind != Kind.PAGE } -> "Vazio"
                        else -> "${items.count { it.kind != Kind.PAGE }} itens"
                    }
                    IosLargeHeader(
                        title = if (collections) "Colecções" else "Biblioteca",
                        subtitle = subtitle
                    ) {
                        if (!collections) {
                            if (selecting) GlassPill("Cancelar") { endSelection() }
                            else {
                                GlassPill("Seleccionar") { selecting = true }
                                GlassIconButton(Icons.Filled.Add, "Importar") { startImport() }
                            }
                        } else {
                            GlassIconButton(Icons.Filled.Add, "Importar") { startImport() }
                        }
                    }
                    IosSegmented(
                        options = listOf(false, true),
                        selected = collections,
                        label = { if (it) "Colecções" else "Biblioteca" },
                        onSelect = {
                            endSelection()
                            collections = it
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).width(250.dp)
                    )
                }
                importStatus?.let {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(it, style = IosType.footnote, color = ios.secondaryLabel)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(CircleShape))
                    }
                }
            }

            // ---------- barra de acções da selecção
            AnimatedVisibility(
                visible = selecting,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp)
            ) {
                val sel = items.filter { it.id in selection }
                val allFav = sel.isNotEmpty() && sel.all { it.fav }
                Row(
                    Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .height(56.dp)
                        .glass(RoundedCornerShape(28.dp))
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolbarIcon(Icons.Filled.Share, "Exportar", enabled = sel.size == 1) {
                        val one = sel.first()
                        exportTarget = one
                        Session.allowLeaving()
                        exporter.launch(one.name)
                    }
                    ToolbarIcon(EdieIcons.Restore, "Devolver à galeria", enabled = sel.isNotEmpty()) {
                        restoreItems = sel
                    }
                    ToolbarIcon(if (allFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorito", enabled = sel.isNotEmpty()) {
                        val ids = selection
                        scope.launch(Dispatchers.IO) { vault.setFavorite(ids, !allFav) }
                        endSelection()
                    }
                    ToolbarIcon(Icons.Filled.Edit, "Mudar o nome", enabled = sel.size == 1) {
                        renameTarget = sel.first()
                    }
                    ToolbarIcon(Icons.Filled.Delete, "Apagar", enabled = sel.isNotEmpty(), tint = ios.red) {
                        confirmDelete = true
                    }
                }
            }
        }
    }

    if (restoreItems.isNotEmpty()) {
        val list = restoreItems
        RestoreDialog(
            count = list.size,
            onCopy = { restoreItems = emptyList(); endSelection(); restorer(list, false) },
            onMove = { restoreItems = emptyList(); endSelection(); restorer(list, true) },
            onDismiss = { restoreItems = emptyList() }
        )
    }

    if (confirmDelete) {
        IosAlert(
            title = "Apagar ${selection.size} ${if (selection.size == 1) "item" else "itens"}?",
            message = "Saem do cofre para sempre. Não há pasta de apagados recentemente.",
            onDismiss = { confirmDelete = false },
            actions = listOf(
                AlertAction("Cancelar", bold = true) { confirmDelete = false },
                AlertAction("Apagar", destructive = true) {
                    val ids = selection
                    confirmDelete = false
                    endSelection()
                    scope.launch(Dispatchers.IO) { vault.delete(ids) }
                }
            )
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        IosAlert(
            title = "Mudar o nome",
            onDismiss = { renameTarget = null },
            actions = listOf(
                AlertAction("Cancelar") { renameTarget = null },
                AlertAction("Guardar", bold = true, enabled = newName.isNotBlank()) {
                    val n = newName
                    renameTarget = null
                    endSelection()
                    scope.launch(Dispatchers.IO) { vault.rename(target, n) }
                }
            )
        ) {
            PrivateTextField(newName, { newName = it }, "Nome", modifier = Modifier.fillMaxWidth())
        }
    }

    if (originals.isNotEmpty()) {
        IosAlert(
            title = "${originals.size} ${if (originals.size == 1) "item guardado" else "itens guardados"} no cofre",
            message = "Quer apagar os originais da galeria do telemóvel?",
            onDismiss = { originals = emptyList() },
            actions = listOf(
                AlertAction("Apagar originais", destructive = true) {
                    val list = originals
                    originals = emptyList()
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) {
                            list.count { uri ->
                                try {
                                    !DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                                } catch (_: Throwable) {
                                    true
                                }
                            }
                        }
                        val msg = if (failed == 0) "Originais apagados."
                        else "$failed original(is) não puderam ser apagados por aqui. Apague-os na galeria."
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                },
                AlertAction("Manter", bold = true) { originals = emptyList() }
            )
        )
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, label: String, enabled: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(
        Modifier.size(48.dp).iosClick(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) (tint ?: ios.accent) else ios.tertiaryLabel,
            modifier = Modifier.size(24.dp)
        )
    }
}

// ---------------------------------------------------------------- grelha

@Composable
private fun PhotoGrid(
    vault: Vault,
    album: Album,
    list: List<VaultItem>,
    columns: Int,
    topPad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    selecting: Boolean,
    selection: Set<String>,
    onPinch: (zoomIn: Boolean) -> Unit,
    onClick: (VaultItem) -> Unit,
    onLongClick: (VaultItem) -> Unit,
    onImport: () -> Unit
) {
    val ios = LocalIos.current
    val gridState = rememberLazyGridState()
    val currentPinch by rememberUpdatedState(onPinch)

    // abre no fim (os mais recentes), como no iPhone; e acompanha quando chegam itens novos
    var lastSize by remember(album) { mutableIntStateOf(-1) }
    LaunchedEffect(album, list.size) {
        if (list.isEmpty()) {
            lastSize = 0
            return@LaunchedEffect
        }
        if (lastSize < 0) gridState.scrollToItem(list.lastIndex)
        else if (list.size > lastSize) gridState.animateScrollToItem(list.lastIndex)
        lastSize = list.size
    }

    if (list.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(top = topPad, bottom = bottomPad, start = 40.dp, end = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(EdieIcons.Shield, contentDescription = null, tint = ios.tertiaryLabel, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                if (album == Album.ALL) "Sem fotografias nem vídeos" else "Este álbum está vazio",
                style = IosType.title3,
                color = ios.label,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Traga fotos e vídeos do telemóvel, ou guarde vídeos da internet no separador Downloads.",
                style = IosType.subhead,
                color = ios.secondaryLabel,
                textAlign = TextAlign.Center
            )
            if (album == Album.ALL) {
                Spacer(Modifier.height(18.dp))
                IosPrimaryButton("Importar", modifier = Modifier.width(180.dp), onClick = onImport)
            }
        }
        return
    }

    val firstVisible by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    val dateLabel = list.getOrNull(firstVisible)?.let { Dates.dayLabel(it.added) } ?: ""

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // pinça para mudar o tamanho das miniaturas (3, 5 ou 7 por linha)
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var acc = 1f
                        var fired = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                acc *= event.calculateZoom()
                                if (!fired && acc > 1.2f) {
                                    currentPinch(true); fired = true
                                }
                                if (!fired && acc < 0.83f) {
                                    currentPinch(false); fired = true
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            items(list, key = { it.id }) { item ->
                PhotoCell(
                    vault = vault,
                    item = item,
                    small = columns >= 7,
                    selecting = selecting,
                    selected = item.id in selection,
                    modifier = Modifier.animateItem(),
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) }
                )
            }
        }
        // data do que está à vista, em vidro, como no iPhone
        if (dateLabel.isNotEmpty() && gridState.isScrollInProgress) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPad + 6.dp)
                    .glass(CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(dateLabel, style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
            }
        }
    }
}

private val overlayText = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    shadow = Shadow(Color(0x99000000), Offset(0f, 1f), 4f)
)

@Composable
private fun PhotoCell(
    vault: Vault,
    item: VaultItem,
    small: Boolean,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val ios = LocalIos.current
    val bmp by produceState<Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { vault.thumbnail(item) }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .background(ios.fill)
            .iosClick(pressedAlpha = 0.7f, onLongClick = onLongClick, onClick = onClick)
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (item.kind) {
                    Kind.PAGE -> Icon(EdieIcons.Globe, contentDescription = null, tint = ios.secondaryLabel)
                    Kind.AUDIO -> Text("♪", fontSize = 26.sp, color = ios.secondaryLabel)
                    else -> Text(
                        item.name.substringAfterLast('.', "?").uppercase().take(4),
                        style = IosType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = ios.secondaryLabel
                    )
                }
            }
        }
        if (!small) {
            if (item.kind == Kind.VIDEO || item.kind == Kind.AUDIO) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.duration > 0) Text(formatTime(item.duration), style = overlayText)
                    else Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            if (item.kind == Kind.PAGE && b != null) {
                Icon(
                    EdieIcons.Globe,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(14.dp)
                )
            }
            if (item.fav) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(13.dp)
                )
            }
        }
        if (selecting) {
            if (selected) Box(Modifier.fillMaxSize().background(Color(0x40FFFFFF)))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(if (small) 16.dp else 22.dp)
                    .clip(CircleShape)
                    .background(if (selected) ios.accent else Color(0x33000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (small) 11.dp else 15.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- colecções

@Composable
private fun CollectionsView(
    vault: Vault,
    items: List<VaultItem>,
    topPad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    onOpenAlbum: (Album) -> Unit
) {
    val ios = LocalIos.current
    val mediaAlbums = listOf(Album.FAV, Album.VIDEO, Album.IMAGE, Album.AUDIO)
    LazyColumn(
        Modifier.fillMaxSize().background(ios.groupedBackground),
        contentPadding = PaddingValues(top = topPad, bottom = bottomPad)
    ) {
        item {
            Text(
                "Álbuns",
                style = IosType.title2,
                color = ios.label,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp)
            )
        }
        mediaAlbums.chunked(2).forEach { pair ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    pair.forEach { a ->
                        AlbumCard(vault, a, albumItems(items, a), Modifier.weight(1f)) { onOpenAlbum(a) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            IosGroup(header = "Pastas") {
                IosRow(
                    "Páginas guardadas",
                    subtitle = "Sites guardados para ler sem internet",
                    icon = EdieIcons.Folder,
                    iconBg = Color(0xFF0A84FF),
                    value = albumItems(items, Album.PAGE).size.toString(),
                    chevron = true,
                    last = true,
                    onClick = { onOpenAlbum(Album.PAGE) }
                )
            }
            Spacer(Modifier.height(22.dp))
            IosGroup(header = "Origem") {
                IosRow(
                    "Baixados da internet",
                    icon = EdieIcons.Download,
                    iconBg = ios.accent,
                    value = albumItems(items, Album.DL).size.toString(),
                    chevron = true,
                    onClick = { onOpenAlbum(Album.DL) }
                )
                IosRow(
                    "Importados do telemóvel",
                    icon = Icons.Filled.Add,
                    iconBg = ios.green,
                    value = albumItems(items, Album.IMP).size.toString(),
                    chevron = true,
                    last = true,
                    onClick = { onOpenAlbum(Album.IMP) }
                )
            }
        }
    }
}

@Composable
private fun AlbumCard(vault: Vault, album: Album, list: List<VaultItem>, modifier: Modifier, onClick: () -> Unit) {
    val ios = LocalIos.current
    val cover = list.lastOrNull()
    val bmp by produceState<Bitmap?>(initialValue = null, cover?.id) {
        value = cover?.let { withContext(Dispatchers.IO) { vault.thumbnail(it) } }
    }
    Column(modifier.iosClick(pressedAlpha = 0.6f, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(ios.fill),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    when (album) {
                        Album.FAV -> Icons.Filled.FavoriteBorder
                        Album.VIDEO -> Icons.Filled.PlayArrow
                        Album.PAGE -> EdieIcons.Globe
                        else -> EdieIcons.Shield
                    },
                    contentDescription = null,
                    tint = ios.tertiaryLabel,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(album.title, style = IosType.subhead.copy(fontWeight = FontWeight.Medium), color = ios.label, maxLines = 1)
        Text(list.size.toString(), style = IosType.subhead, color = ios.secondaryLabel)
    }
}

private fun importUri(ctx: Context, vault: Vault, uri: Uri) {
    val resolver = ctx.contentResolver
    var name = "ficheiro"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx)?.let { name = it }
        }
    }
    val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: FileUtil.guessMime(name)
    val input = resolver.openInputStream(uri) ?: throw java.io.IOException("não foi possível abrir")
    input.use { vault.importStream(name, mime, it, source = "imp") }
}
