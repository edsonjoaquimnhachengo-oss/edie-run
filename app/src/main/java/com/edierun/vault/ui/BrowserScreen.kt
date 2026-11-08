package com.edierun.vault.ui

import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.edierun.vault.download.DownloadManager
import com.edierun.vault.download.Quality
import com.edierun.vault.net.TorManager
import com.edierun.vault.vault.Vault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(controller: BrowserController, vault: Vault, onSentToDownloads: () -> Unit) {
    val ctx = LocalContext.current
    val torState by TorManager.state.collectAsState()
    val torMessage by TorManager.message.collectAsState()
    var input by remember { mutableStateOf(controller.url) }
    var imageToSave by remember { mutableStateOf<String?>(null) }

    // Primeira abertura nesta sessão: aplica as preferências do cofre
    LaunchedEffect(Unit) {
        controller.searchBase = vault.getPref("search", "https://duckduckgo.com/?q=")
        if (controller.url.isEmpty() && controller.progress == 100) {
            if (vault.getBoolPref("torDefault", false) && !controller.torWanted) {
                controller.torWanted = true
                TorManager.start(ctx)
            }
            controller.home()
        }
    }

    LaunchedEffect(torState, controller.torWanted) {
        controller.syncTor(torState)
    }

    LaunchedEffect(controller.url) {
        input = controller.url
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

    BackHandler(enabled = controller.canBack && controller.customView == null) {
        controller.webView.goBack()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (label, color) = when {
                !controller.torWanted -> "Tor: não" to MaterialTheme.colorScheme.surfaceVariant
                torState == TorManager.State.READY && controller.proxyApplied -> "Tor: sim" to Color(0xFF2E7D4F)
                torState == TorManager.State.ERROR -> "Tor: erro" to Color(0xFF8E2B2B)
                else -> "Tor: …" to Color(0xFF7A5A12)
            }
            AssistChip(
                onClick = {
                    if (controller.torWanted) {
                        controller.torWanted = false
                        if (!DownloadManager.hasActive()) TorManager.stop(ctx)
                    } else {
                        controller.torWanted = true
                        if (torState == TorManager.State.ERROR) TorManager.stop(ctx)
                        TorManager.start(ctx)
                    }
                },
                label = { Text(label) },
                colors = AssistChipDefaults.assistChipColors(containerColor = color)
            )
            Spacer(Modifier.width(6.dp))
            PrivateTextField(
                value = input,
                onValueChange = { input = it },
                hint = "Pesquisar ou endereço",
                modifier = Modifier.weight(1f),
                imeAction = EditorInfo.IME_ACTION_GO,
                onAction = { controller.load(input) }
            )
        }

        if (controller.progress in 1..99) {
            LinearProgressIndicator(
                progress = { controller.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { controller.attach() }
            )
            if (controller.torWanted && !controller.proxyApplied) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        controller.torUnsupported -> Text(
                            "O WebView deste telemóvel é antigo e não permite usar o Tor. " +
                                "Actualize o \"Android System WebView\" na Play Store, ou desligue o Tor.",
                            textAlign = TextAlign.Center
                        )
                        torState == TorManager.State.ERROR -> {
                            Text(torMessage, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            FilledTonalButton(onClick = {
                                TorManager.stop(ctx)
                                TorManager.start(ctx)
                            }) { Text("Tentar outra vez") }
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
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.webView.goBack() }, enabled = controller.canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Recuar")
            }
            IconButton(onClick = { controller.webView.goForward() }, enabled = controller.canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Avançar")
            }
            IconButton(onClick = { controller.webView.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Recarregar")
            }
            IconButton(onClick = { controller.home() }) {
                Icon(Icons.Filled.Home, contentDescription = "Início")
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                enabled = controller.url.startsWith("http"),
                onClick = {
                    val q = Quality.from(vault.getPref("quality", Quality.HIGH.name))
                    DownloadManager.enqueueMedia(vault, controller.url, q)
                    Toast.makeText(ctx, "Enviado para Downloads", Toast.LENGTH_SHORT).show()
                    onSentToDownloads()
                }
            ) { Text("⬇ Baixar vídeo") }
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
