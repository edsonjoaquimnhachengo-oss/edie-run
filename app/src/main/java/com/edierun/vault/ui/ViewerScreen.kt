package com.edierun.vault.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.edierun.vault.player.StreamServer
import com.edierun.vault.vault.FileUtil
import com.edierun.vault.vault.Kind
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private const val DISMISS_DISTANCE = 220f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vault: Vault, request: ViewerRequest, onClose: () -> Unit) {
    val ios = LocalIos.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val items by vault.items.collectAsState()
    val list = remember(items, request.album) { albumItems(items, request.album) }

    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val startIndex = remember { list.indexOfFirst { it.id == request.startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]
    val activity = ctx.findActivity()

    BackHandler { onClose() }
    LaunchedEffect(pager.currentPage) { zoomed = false }

    // barras do sistema: escondidas quando os controlos estão escondidos
    DisposableEffect(chrome) {
        val window = activity?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) ctrl?.show(WindowInsetsCompat.Type.systemBars()) else ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = current
        if (uri != null) {
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

    val dragFraction = (dragY / 900f).coerceIn(0f, 1f)
    val lightBg = chrome && !ios.isDark && (current.kind == Kind.IMAGE || current.kind == Kind.PAGE)
    val bg = if (lightBg) Color.White else Color.Black
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(bg.copy(alpha = 1f - dragFraction))) {
        HorizontalPager(
            state = pager,
            key = { list[it].id },
            beyondViewportPageCount = 1,
            pageSpacing = 14.dp,
            userScrollEnabled = !zoomed && dragY == 0f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragY
                    val s = 1f - dragFraction * 0.3f
                    scaleX = s
                    scaleY = s
                }
        ) { page ->
            val item = list[page]
            val settled = page == pager.settledPage
            when (item.kind) {
                Kind.IMAGE -> ZoomableImage(
                    vault = vault,
                    item = item,
                    onTap = { chrome = !chrome },
                    onZoomChange = { z -> if (page == pager.currentPage) zoomed = z },
                    onDismissDrag = { dy -> dragY = (dragY + dy).coerceAtLeast(0f) },
                    onDismissEnd = {
                        if (dragY > DISMISS_DISTANCE) {
                            onClose()
                        } else {
                            scope.launch { animate(dragY, 0f, animationSpec = spring(dampingRatio = 0.8f)) { v, _ -> dragY = v } }
                        }
                    }
                )
                Kind.VIDEO, Kind.AUDIO -> if (settled) {
                    VlcPlayer(
                        vault = vault,
                        item = item,
                        controls = chrome,
                        onToggle = { chrome = !chrome },
                        onAutoHide = { chrome = false },
                        bottomOffset = bottomInset + 78.dp
                    )
                } else {
                    StillThumb(vault, item)
                }
                Kind.PAGE -> if (settled) PageViewer(vault, item, topInset + 62.dp, bottomInset + 78.dp) else StillThumb(vault, item)
                Kind.OTHER -> OtherViewer()
            }
        }

        // ---------- barra superior
        AnimatedVisibility(
            visible = chrome && dragY == 0f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.55f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Voltar", size = 44.dp, onClick = onClose)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        Dates.dayLabel(current.added),
                        style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = if (lightBg) Color.Black else Color.White
                    )
                    Text(
                        Dates.time(current.added),
                        style = IosType.caption,
                        color = if (lightBg) Color(0x99000000) else Color(0xB3FFFFFF)
                    )
                }
                Spacer(Modifier.width(44.dp))
            }
        }

        // ---------- barra inferior (partilhar, favorito, informação, apagar)
        AnimatedVisibility(
            visible = chrome && dragY == 0f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .glass(RoundedCornerShape(28.dp), haze = null)
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ViewerAction(Icons.Filled.Share, "Exportar") {
                    Session.allowLeaving()
                    exporter.launch(current.name)
                }
                ViewerAction(
                    if (current.fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "Favorito",
                    tint = if (current.fav) ios.red else null
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    val id = current.id
                    val fav = !current.fav
                    scope.launch(Dispatchers.IO) { vault.setFavorite(setOf(id), fav) }
                }
                ViewerAction(Icons.Filled.Info, "Informação") { showInfo = true }
                ViewerAction(Icons.Filled.Delete, "Apagar") { confirmDelete = true }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            containerColor = ios.groupedBackground,
            contentColor = ios.label
        ) {
            InfoSheet(current, onRename = { renaming = true })
        }
    }

    if (renaming) {
        var newName by remember(current.id) { mutableStateOf(current.name) }
        IosAlert(
            title = "Mudar o nome",
            onDismiss = { renaming = false },
            actions = listOf(
                AlertAction("Cancelar") { renaming = false },
                AlertAction("Guardar", bold = true, enabled = newName.isNotBlank()) {
                    val item = current
                    val n = newName
                    renaming = false
                    scope.launch(Dispatchers.IO) { vault.rename(item, n) }
                }
            )
        ) {
            PrivateTextField(newName, { newName = it }, "Nome", modifier = Modifier.fillMaxWidth())
        }
    }

    if (confirmDelete) {
        val what = when (current.kind) {
            Kind.VIDEO -> "este vídeo"
            Kind.IMAGE -> "esta fotografia"
            Kind.PAGE -> "esta página"
            else -> "este ficheiro"
        }
        IosAlert(
            title = "Apagar $what?",
            message = "Sai do cofre para sempre.",
            onDismiss = { confirmDelete = false },
            actions = listOf(
                AlertAction("Cancelar", bold = true) { confirmDelete = false },
                AlertAction("Apagar", destructive = true) {
                    val id = current.id
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) { vault.delete(setOf(id)) }
                }
            )
        )
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(Modifier.size(48.dp).iosClick(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint ?: ios.accent, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun InfoSheet(item: VaultItem, onRename: () -> Unit) {
    val ios = LocalIos.current
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Text(
            item.name,
            style = IosType.headline,
            color = ios.label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        Text(
            "${Dates.fullDay(item.added)} · ${Dates.time(item.added)}",
            style = IosType.subhead,
            color = ios.secondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(16.dp))
        IosGroup {
            IosRow(
                "Tipo",
                value = when (item.kind) {
                    Kind.VIDEO -> "Vídeo"
                    Kind.IMAGE -> "Fotografia"
                    Kind.AUDIO -> "Áudio"
                    Kind.PAGE -> "Página web"
                    Kind.OTHER -> "Ficheiro"
                }
            )
            IosRow("Tamanho", value = FileUtil.humanSize(item.size))
            if (item.duration > 0) IosRow("Duração", value = formatTime(item.duration))
            IosRow(
                "Origem",
                value = when (item.src) {
                    "dl" -> "Internet"
                    "web" -> "Navegador"
                    else -> "Telemóvel"
                }
            )
            IosRow("Formato", value = item.name.substringAfterLast('.', "—").uppercase(), last = true)
        }
        Spacer(Modifier.height(16.dp))
        IosGroup {
            IosRow("Mudar o nome", titleColor = ios.accent, last = true, onClick = onRename)
        }
    }
}

@Composable
private fun StillThumb(vault: Vault, item: VaultItem) {
    val bmp by produceState<Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { vault.thumbnail(item) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (item.kind == Kind.VIDEO) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- imagens

private fun loadDrawable(ctx: Context, vault: Vault, item: VaultItem): Drawable {
    val bytes = vault.openReader(item).use { it.readAll() }
    if (Build.VERSION.SDK_INT >= 28) {
        try {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                val max = maxOf(info.size.width, info.size.height)
                var s = 1
                while (max / s > 4096) s *= 2
                if (s > 1) decoder.setTargetSampleSize(s)
            }
        } catch (_: Throwable) {
            // tenta o método antigo abaixo
        }
    }
    val bmp = Vault.decodeSampled(bytes, 4096) ?: throw java.io.IOException("imagem ilegível")
    return BitmapDrawable(ctx.resources, bmp)
}

/**
 * Imagem com zoom (pinça ou duplo toque), deslocamento quando ampliada, e "puxar para baixo
 * para fechar" quando não está ampliada. Sem ampliação, o deslize horizontal passa para as fotos vizinhas.
 */
@Composable
private fun ZoomableImage(
    vault: Vault,
    item: VaultItem,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit
) {
    val ctx = LocalContext.current
    val result by produceState<Result<Drawable>?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { loadDrawable(ctx, vault, item) } }
    }
    val thumb by produceState<Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { vault.thumbnail(item) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val tap by rememberUpdatedState(onTap)
    val zoomCb by rememberUpdatedState(onZoomChange)
    val dragCb by rememberUpdatedState(onDismissDrag)
    val endCb by rememberUpdatedState(onDismissEnd)
    val scope = rememberCoroutineScope()

    fun clamp(o: Offset, s: Float, w: Float, h: Float): Offset {
        val mx = w * (s - 1f) / 2f
        val my = h * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { p ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val (targetS, targetO) = if (scale > 1f) {
                            1f to Offset.Zero
                        } else {
                            val s = 2.5f
                            s to clamp(Offset((w / 2f - p.x) * (s - 1f), (h / 2f - p.y) * (s - 1f)), s, w, h)
                        }
                        val startS = scale
                        val startO = offset
                        zoomCb(targetS > 1f)
                        scope.launch {
                            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f)) { t, _ ->
                                scale = startS + (targetS - startS) * t
                                offset = Offset(startO.x + (targetO.x - startO.x) * t, startO.y + (targetO.y - startO.y) * t)
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 = indefinido, 1 = zoom/deslocar, 2 = fechar
                    var total = Offset.Zero
                    val slop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pressed >= 2 || scale > 1.01f) {
                            mode = 1
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val s = (scale * zoom).coerceIn(1f, 6f)
                            scale = s
                            offset = if (s > 1f) clamp(offset + pan, s, w, h) else Offset.Zero
                            zoomCb(s > 1.01f)
                            event.changes.forEach { it.consume() }
                        } else if (pressed == 1) {
                            total += pan
                            if (mode == 0 && total.y > slop && total.y > abs(total.x) * 1.4f) mode = 2
                            if (mode == 2) {
                                dragCb(pan.y)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (mode == 2) endCb()
                    if (scale < 1.02f) {
                        scale = 1f
                        offset = Offset.Zero
                        zoomCb(false)
                    }
                }
            }
    ) {
        val r = result
        val d = r?.getOrNull()
        when {
            d != null -> {
                val painter = remember(d) { DrawablePainter(d) }
                DisposableEffect(d) {
                    (d as? Animatable)?.start()
                    onDispose { (d as? Animatable)?.stop() }
                }
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }
            r != null -> Text(
                "Não foi possível abrir esta imagem.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> {
                // enquanto a imagem completa é decifrada, mostra a miniatura (abre logo, como no iPhone)
                thumb?.let {
                    Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Desenha qualquer Drawable (incluindo GIF e WebP animados) num Image do Compose. */
private class DrawablePainter(private val drawable: Drawable) : Painter() {
    private var tick by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            tick++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
            handler.postAtTime(what, time)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what)
        }
    }

    init {
        drawable.callback = callback
    }

    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        else Size.Unspecified

    override fun DrawScope.onDraw() {
        @Suppress("UNUSED_VARIABLE")
        val frame = tick
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

// ---------------------------------------------------------------- vídeo e áudio (VLC)

private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun VlcPlayer(
    vault: Vault,
    item: VaultItem,
    controls: Boolean,
    onToggle: () -> Unit,
    onAutoHide: () -> Unit,
    bottomOffset: Dp
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = ctx.findActivity()
    val url = remember { StreamServer.register({ vault.openReader(item) }, item.mime) }
    val libVlc = remember { LibVLC(ctx.applicationContext, arrayListOf("--audio-time-stretch", "--network-caching=400")) }
    val player = remember { MediaPlayer(libVlc) }

    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var time by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(item.duration) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedIdx by remember { mutableIntStateOf(2) }
    var fill by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val autoHide by rememberUpdatedState(onAutoHide)

    DisposableEffect(Unit) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; ended = false }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EndReached -> { playing = false; ended = true }
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EncounteredError -> {
                    playing = false
                    error = "Não foi possível reproduzir este ficheiro."
                }
                else -> {}
            }
        }
        onDispose {
            player.setEventListener(null)
            try { player.stop() } catch (_: Throwable) { }
            try { player.detachViews() } catch (_: Throwable) { }
            player.release()
            libVlc.release()
            StreamServer.unregister(url)
            view.keepScreenOn = false
        }
    }

    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { }
    }

    LaunchedEffect(controls, playing, dragging) {
        if (controls && playing && dragging == null) {
            delay(3500)
            autoHide()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (length > 0) player.setTime((time + deltaMs).coerceIn(0L, length))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                VLCVideoLayout(c).also { layout ->
                    player.attachViews(layout, null, false, false)
                    val media = Media(libVlc, Uri.parse(url))
                    media.setHWDecoderEnabled(true, false)
                    player.setMedia(media)
                    media.release()
                    player.play()
                }
            }
        )

        if (item.kind == Kind.AUDIO) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF5E5CE6), Color(0xFFFF375F)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = Color.White, fontSize = 80.sp)
                }
                Spacer(Modifier.height(20.dp))
                Text(item.name, color = Color.White, style = IosType.headline, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle() }
        )

        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
        }

        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundGlass("−10", 54.dp) { seekBy(-10_000) }
                    RoundGlass(if (playing) "❚❚" else "▶", 74.dp) {
                        when {
                            ended -> { player.stop(); player.play() }
                            playing -> player.pause()
                            else -> player.play()
                        }
                    }
                    RoundGlass("+10", 54.dp) { seekBy(10_000) }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = bottomOffset)
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(22.dp), haze = null, tint = Color(0x99202022))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    val pos = dragging ?: if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = dragging?.let { (it * length).toLong() } ?: time
                        Text(formatTime(shown), color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = pos,
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { if (length > 0) player.setTime((it * length).toLong()) }
                                dragging = null
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color(0x4DFFFFFF)
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text("−" + formatTime((length - shown).coerceAtLeast(0)), color = Color.White, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SmallControl("${SPEEDS[speedIdx]}x".replace(".0x", "x")) {
                            speedIdx = (speedIdx + 1) % SPEEDS.size
                            player.setRate(SPEEDS[speedIdx])
                        }
                        if (item.kind == Kind.VIDEO) {
                            SmallControl(if (fill) "Ajustar" else "Preencher") {
                                fill = !fill
                                player.setVideoScale(if (fill) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                            }
                            SmallControl("Rodar") {
                                landscape = !landscape
                                activity?.requestedOrientation =
                                    if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundGlass(label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .glass(CircleShape, haze = null, tint = Color(0x80202022))
            .iosClick(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = if (size > 60.dp) 26.sp else 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallControl(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.iosClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

// ---------------------------------------------------------------- páginas guardadas (offline)

/**
 * Lê uma página guardada sem qualquer acesso à internet: a rede e o JavaScript ficam desligados.
 * O ficheiro é decifrado para a área privada da app só enquanto está aberto e é apagado ao fechar.
 */
@Composable
private fun PageViewer(vault: Vault, item: VaultItem, topPadding: Dp, bottomPadding: Dp) {
    val ctx = LocalContext.current
    val tempDir = remember { File(ctx.noBackupFilesDir, "tmp/view") }
    val result by produceState<Result<File>?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { vault.decryptToTemp(item, tempDir) } }
    }
    val file = result?.getOrNull()
    DisposableEffect(file) {
        onDispose { file?.let { f -> Thread { FileUtil.wipe(f) }.start() } }
    }
    Box(Modifier.fillMaxSize().background(Color.White).padding(top = topPadding, bottom = bottomPadding)) {
        when {
            file != null -> key(file.path) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = false
                            settings.blockNetworkLoads = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = false
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                            }
                            loadUrl(Uri.fromFile(file).toString())
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.clearCache(true)
                        wv.destroy()
                    }
                )
            }
            result != null -> Text("Não foi possível abrir esta página.", color = Color.Black, modifier = Modifier.align(Alignment.Center))
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

// ---------------------------------------------------------------- outros

@Composable
private fun OtherViewer() {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sem pré-visualização", color = Color.White, style = IosType.headline)
            Spacer(Modifier.height(8.dp))
            Text(
                "Este tipo de ficheiro não abre aqui. Pode exportá-lo com o botão de partilhar, em baixo.",
                color = Color(0xFFBBBBBB),
                style = IosType.subhead,
                textAlign = TextAlign.Center
            )
        }
    }
}
