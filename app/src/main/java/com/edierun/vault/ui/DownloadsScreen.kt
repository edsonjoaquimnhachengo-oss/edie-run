package com.edierun.vault.ui

import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(vault: Vault, prefill: String, onPrefillUsed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allJobs by DownloadManager.jobs.collectAsState()
    val engine by DownloadManager.engine.collectAsState()
    val torState by TorManager.state.collectAsState()
    // Cada cofre só vê os seus próprios downloads
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
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Baixar vídeos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "YouTube, Facebook, Instagram, TikTok, X, Vimeo, Dailymotion e centenas de outros sites. " +
                    "Também pode, na app de origem, carregar em Partilhar e escolher \"Calculadora\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrivateTextField(
                    value = url,
                    onValueChange = { url = it },
                    hint = "Cole aqui o link do vídeo",
                    modifier = Modifier.weight(1f),
                    imeAction = EditorInfo.IME_ACTION_GO,
                    onAction = { start() }
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    val found = text?.let { Regex("https?://\\S+").find(it)?.value }
                    if (found != null) url = found else Toast.makeText(ctx, "Não há nenhum link copiado.", Toast.LENGTH_SHORT).show()
                }) { Text("Colar") }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Quality.entries.forEach { q ->
                    FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q.label) })
                }
            }
        }
        item {
            Button(onClick = { start() }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Baixar para o cofre")
            }
        }
        item {
            val engineText = when (engine) {
                EngineState.PREPARING -> "Motor de downloads: a preparar (só demora na primeira vez)…"
                EngineState.READY -> "Motor de downloads: pronto"
                EngineState.FAILED -> "Motor de downloads com erro: ${DownloadManager.engineError.take(120)}"
            }
            val torText = if (torState == TorManager.State.READY) " · os downloads passam pelo Tor" else ""
            Text(engineText + torText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(enabled = !updating && engine == EngineState.READY, onClick = {
                    updating = true
                    scope.launch {
                        val msg = DownloadManager.updateEngine()
                        updating = false
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text(if (updating) "A actualizar…" else "Actualizar motor") }
                if (jobs.any { !it.isActive }) {
                    TextButton(onClick = { DownloadManager.clearFinished(vault.slot) }) { Text("Limpar lista") }
                }
            }
        }
        if (jobs.isEmpty()) {
            item {
                Text(
                    "Sem downloads. Se um site deixar de funcionar, carregue em \"Actualizar motor\": " +
                        "os sites mudam com frequência e o motor acompanha essas mudanças.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        items(jobs, key = { it.id }) { job -> JobCard(job) }
    }
}

@Composable
private fun JobCard(job: DownloadJob) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            if (job.isActive) {
                if (job.progress in 0f..1f) {
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
            }
            val (statusText, color) = when (job.status) {
                JobStatus.QUEUED -> "Em espera" to MaterialTheme.colorScheme.onSurfaceVariant
                JobStatus.RUNNING -> "A baixar" + (if (job.progress >= 0f) " · ${(job.progress * 100).toInt()}%" else "") to MaterialTheme.colorScheme.primary
                JobStatus.SAVING -> "A cifrar para o cofre" + (if (job.progress >= 0f) " · ${(job.progress * 100).toInt()}%" else "") to MaterialTheme.colorScheme.primary
                JobStatus.DONE -> "Guardado no cofre" to Color(0xFF6FCF97)
                JobStatus.FAILED -> "Falhou" to MaterialTheme.colorScheme.error
                JobStatus.CANCELLED -> "Cancelado" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (job.isActive) {
                    TextButton(onClick = { DownloadManager.cancel(job.id) }) { Text("Cancelar") }
                }
            }
            if (job.detail.isNotBlank() && job.status != JobStatus.DONE) {
                Text(
                    job.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
