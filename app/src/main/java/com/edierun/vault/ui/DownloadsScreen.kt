package com.edierun.vault.ui

import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edierun.vault.download.DownloadJob
import com.edierun.vault.download.DownloadManager
import com.edierun.vault.download.EngineState
import com.edierun.vault.download.JobStatus
import com.edierun.vault.download.Quality
import com.edierun.vault.net.TorManager
import com.edierun.vault.vault.Vault
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(vault: Vault, prefill: String, onPrefillUsed: () -> Unit) {
    val ctx = LocalContext.current
    val ios = LocalIos.current
    val scope = rememberCoroutineScope()
    val allJobs by DownloadManager.jobs.collectAsState()
    val engine by DownloadManager.engine.collectAsState()
    val torState by TorManager.state.collectAsState()
    // cada cofre só vê os seus próprios downloads
    val jobs = remember(allJobs, vault.slot) { allJobs.filter { it.slot == vault.slot }.reversed() }
    var url by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(Quality.from(vault.getPref("quality", Quality.HIGH.name))) }
    var updating by remember { mutableStateOf(false) }

    LaunchedEffect(prefill) {
        if (prefill.isNotEmpty()) {
            url = prefill
            onPrefillUsed()
        }
    }

    fun start() {
        val u = url.trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            Toast.makeText(ctx, "Cole um link que comece por http:// ou https://", Toast.LENGTH_SHORT).show()
            return
        }
        DownloadManager.enqueueMedia(vault, u, quality)
        url = ""
    }

    LazyColumn(
        Modifier.fillMaxSize().background(ios.groupedBackground).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 20.dp)
    ) {
        item {
            IosLargeHeader(
                "Downloads",
                subtitle = when (engine) {
                    EngineState.PREPARING -> "A preparar o motor…"
                    EngineState.READY -> if (torState == TorManager.State.READY) "Pronto · através do Tor" else "Pronto"
                    EngineState.FAILED -> "Motor com erro"
                }
            ) {
                GlassIconButton(Icons.Filled.Refresh, "Actualizar motor", enabled = !updating && engine == EngineState.READY) {
                    updating = true
                    Toast.makeText(ctx, "A actualizar o motor de downloads…", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val msg = DownloadManager.updateEngine()
                        updating = false
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            IosGroup(
                footer = "YouTube, Facebook, Instagram, TikTok, X, Vimeo e centenas de outros sites. " +
                    "Também pode carregar em Partilhar na app de origem e escolher \"Calculadora\"."
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrivateTextField(
                            value = url,
                            onValueChange = { url = it },
                            hint = "Link do vídeo",
                            modifier = Modifier.weight(1f),
                            imeAction = EditorInfo.IME_ACTION_GO,
                            onAction = { start() }
                        )
                        Spacer(Modifier.width(6.dp))
                        IosTextButton("Colar") {
                            val cm = ctx.getSystemService(ClipboardManager::class.java)
                            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            val found = text?.let { Regex("https?://\\S+").find(it)?.value }
                            if (found != null) url = found else Toast.makeText(ctx, "Não há nenhum link copiado.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    IosSegmented(
                        options = Quality.entries.toList(),
                        selected = quality,
                        label = {
                            when (it) {
                                Quality.HIGH -> "1080p"
                                Quality.MEDIUM -> "720p"
                                Quality.LOW -> "480p"
                                Quality.AUDIO -> "Áudio"
                            }
                        },
                        onSelect = { quality = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    IosPrimaryButton("Baixar para o cofre", modifier = Modifier.fillMaxWidth(), enabled = url.isNotBlank()) { start() }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (jobs.isEmpty()) {
            item {
                Text(
                    "Sem downloads. Se um site deixar de funcionar, toque no botão de actualizar, no canto: " +
                        "os sites mudam com frequência e o motor acompanha essas mudanças.",
                    style = IosType.footnote,
                    color = ios.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENTES", style = IosType.footnote, color = ios.secondaryLabel, modifier = Modifier.weight(1f))
                    if (jobs.any { !it.isActive }) {
                        Text(
                            "Limpar",
                            style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = ios.accent,
                            modifier = Modifier.iosClick { DownloadManager.clearFinished(vault.slot) }
                        )
                    }
                }
            }
            items(jobs, key = { it.id }) { job ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { JobCard(job) }
            }
        }
    }
}

@Composable
private fun JobCard(job: DownloadJob) {
    val ios = LocalIos.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ios.cell)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (badgeColor, badgeIcon) = when (job.status) {
            JobStatus.DONE -> ios.green to Icons.Filled.Check
            JobStatus.FAILED -> ios.red to Icons.Filled.Warning
            JobStatus.CANCELLED -> ios.tertiaryLabel to Icons.Filled.Close
            else -> ios.accent to EdieIcons.Download
        }
        Box(Modifier.size(34.dp).clip(CircleShape).background(badgeColor), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(badgeIcon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, style = IosType.subhead.copy(fontWeight = FontWeight.Medium), color = ios.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            if (job.isActive) {
                if (job.progress in 0f..1f) {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        color = ios.accent,
                        trackColor = ios.fill,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                    )
                } else {
                    LinearProgressIndicator(color = ios.accent, trackColor = ios.fill, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                }
                Spacer(Modifier.height(4.dp))
            }
            val status = when (job.status) {
                JobStatus.QUEUED -> "Em espera"
                JobStatus.RUNNING -> "A baixar" + if (job.progress >= 0f) " · ${(job.progress * 100).toInt()}%" else ""
                JobStatus.SAVING -> "A cifrar para o cofre" + if (job.progress >= 0f) " · ${(job.progress * 100).toInt()}%" else ""
                JobStatus.DONE -> "Guardado no cofre"
                JobStatus.FAILED -> job.detail.ifBlank { "Falhou" }
                JobStatus.CANCELLED -> "Cancelado"
            }
            Text(
                status,
                style = IosType.footnote,
                color = if (job.status == JobStatus.FAILED) ios.red else ios.secondaryLabel,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (job.isActive) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(ios.fill).iosClick { DownloadManager.cancel(job.id) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = ios.secondaryLabel, modifier = Modifier.size(16.dp))
            }
        }
    }
}
