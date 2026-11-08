package com.edierun.vault.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.edierun.vault.player.StreamServer
import com.edierun.vault.vault.FileUtil
import java.io.File
import com.edierun.vault.vault.Kind
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.nio.ByteBuffer
import kotlin.math.roundToInt

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun ViewerScreen(vault: Vault, item: VaultItem, onClose: () -> Unit, onNavigate: (VaultItem) -> Unit) {
    BackHandler { onClose() }
    val items by vault.items.collectAsState()
    val idx = items.indexOfFirst { it.id == item.id }
    val prev = if (idx > 0) items[idx - 1] else null
    val next = if (idx >= 0 && idx < items.size - 1) items[idx + 1] else null

    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        val window = activity?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val topBar: @Composable () -> Unit = {
        ViewerTopBar(
            title = item.name,
            onClose = onClose,
            onPrev = prev?.let { p -> { onNavigate(p) } },
            onNext = next?.let { n -> { onNavigate(n) } }
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(item.id) {
            when (item.kind) {
                Kind.VIDEO, Kind.AUDIO -> VlcPlayer(vault, item, topBar)
                Kind.IMAGE -> ImageViewer(vault, item, topBar)
                Kind.PAGE -> PageViewer(vault, item, topBar)
                Kind.OTHER -> OtherViewer(topBar)
            }
        }
    }
}

@Composable
private fun ViewerTopBar(title: String, onClose: () -> Unit, onPrev: (() -> Unit)?, onNext: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
        }
        Text(
            title,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onPrev?.invoke() }, enabled = onPrev != null) { Text("‹", fontSize = 26.sp) }
        TextButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text("›", fontSize = 26.sp) }
    }
}

// ---------------------------------------------------------------- vídeo e áudio (VLC)

private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun VlcPlayer(vault: Vault, item: VaultItem, topBar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = ctx.findActivity()
    val url = remember { StreamServer.register({ vault.openReader(item) }, item.mime) }
    val libVlc = remember { LibVLC(ctx.applicationContext, arrayListOf("--audio-time-stretch", "--network-caching=400")) }
    val player = remember { MediaPlayer(libVlc) }

    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var time by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedIdx by remember { mutableIntStateOf(2) }
    var fill by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; ended = false }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EndReached -> { playing = false; ended = true; controls = true }
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
        }
    }

    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(controls, playing, dragging) {
        if (controls && playing && dragging == null) {
            delay(3500)
            controls = false
        }
    }

    fun seekBy(deltaMs: Long) {
        if (length > 0) player.setTime((time + deltaMs).coerceIn(0L, length))
    }

    Box(Modifier.fillMaxSize()) {
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
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("♪", color = Color.White, fontSize = 72.sp)
                Text(item.name, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }

        // camada de toque: mostra/esconde os controlos
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    controls = !controls
                }
        )

        error?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                textAlign = TextAlign.Center
            )
        }

        if (controls) {
            Box(Modifier.align(Alignment.TopCenter)) { topBar() }

            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton("−10", 56) { seekBy(-10_000) }
                RoundButton(if (playing) "❚❚" else "▶", 76) {
                    when {
                        ended -> { player.stop(); player.play() }
                        playing -> player.pause()
                        else -> player.play()
                    }
                }
                RoundButton("+10", 56) { seekBy(10_000) }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                val pos = dragging ?: if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f
                Slider(
                    value = pos,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { if (length > 0) player.setTime((it * length).toLong()) }
                        dragging = null
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = dragging?.let { (it * length).toLong() } ?: time
                    Text("${formatTime(shown)} / ${formatTime(length)}", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        speedIdx = (speedIdx + 1) % SPEEDS.size
                        player.setRate(SPEEDS[speedIdx])
                    }) { Text("${SPEEDS[speedIdx]}x".replace(".0x", "x"), color = Color.White) }
                    if (item.kind == Kind.VIDEO) {
                        TextButton(onClick = {
                            fill = !fill
                            player.setVideoScale(if (fill) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                        }) { Text(if (fill) "Ajustar" else "Preencher", color = Color.White) }
                        TextButton(onClick = {
                            landscape = !landscape
                            activity?.requestedOrientation =
                                if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }) { Text("Rodar", color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(label: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color(0x88000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = if (sizeDp > 60) 26.sp else 15.sp)
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

@Composable
private fun ImageViewer(vault: Vault, item: VaultItem, topBar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val result by produceState<Result<Drawable>?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { loadDrawable(ctx, vault, item) } }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var chrome by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { chrome = !chrome },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 8f)
                    scale = s
                    offset = if (s > 1f) offset + pan else Offset.Zero
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
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (chrome) Box(Modifier.align(Alignment.TopCenter)) { topBar() }
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

// ---------------------------------------------------------------- páginas guardadas (offline)

/**
 * Lê uma página guardada sem qualquer acesso à internet: a rede e o JavaScript ficam desligados.
 * O ficheiro é decifrado para a área privada da app só enquanto está aberto e é apagado ao fechar.
 */
@Composable
private fun PageViewer(vault: Vault, item: VaultItem, topBar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val tempDir = remember { File(ctx.noBackupFilesDir, "tmp/view") }
    val result by produceState<Result<File>?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { vault.decryptToTemp(item, tempDir) } }
    }
    val file = result?.getOrNull()
    DisposableEffect(file) {
        onDispose { file?.let { f -> Thread { FileUtil.wipe(f) }.start() } }
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.background(Color.Black)) { topBar() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                file != null -> AndroidView(
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
                                // links dentro da página guardada não abrem nada (estamos offline)
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
                result != null -> Text(
                    "Não foi possível abrir esta página.",
                    color = Color.Black,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

// ---------------------------------------------------------------- outros

@Composable
private fun OtherViewer(topBar: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Sem pré-visualização", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Este tipo de ficheiro não abre aqui. Pode exportá-lo: na galeria, toque longo no ficheiro e depois em partilhar.",
                color = Color(0xFFBBBBBB),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(1.dp))
        }
        Box(Modifier.align(Alignment.TopCenter)) { topBar() }
    }
}
