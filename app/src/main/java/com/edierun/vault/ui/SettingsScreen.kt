package com.edierun.vault.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.edierun.vault.download.Quality
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ENGINES = listOf(
    "DuckDuckGo" to "https://duckduckgo.com/?q=",
    "Startpage" to "https://www.startpage.com/do/search?q=",
    "Brave" to "https://search.brave.com/search?q=",
    "Google" to "https://www.google.com/search?q="
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vault: Vault, onLock: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var torDefault by remember { mutableStateOf(vault.getBoolPref("torDefault", false)) }
    var search by remember { mutableStateOf(vault.getPref("search", ENGINES[0].second)) }
    var quality by remember { mutableStateOf(Quality.from(vault.getPref("quality", Quality.HIGH.name))) }
    var showPin by remember { mutableStateOf(false) }
    var showWipe by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Definições", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Section("Segurança") {
            Button(onClick = onLock, modifier = Modifier.fillMaxWidth()) { Text("Fechar o cofre agora") }
            OutlinedButton(onClick = { showPin = true }, modifier = Modifier.fillMaxWidth()) { Text("Mudar o PIN deste cofre") }
            Text(
                "O cofre fecha-se sozinho quando sai da app, muda de app ou desliga o ecrã.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Navegador Edie Run") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Abrir sempre com Tor")
                    Text(
                        "Esconde o seu IP dos sites. A navegação fica mais lenta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = torDefault, onCheckedChange = {
                    torDefault = it
                    vault.setPref("torDefault", it)
                })
            }
            Text("Motor de pesquisa", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ENGINES.forEach { (name, base) ->
                    FilterChip(selected = search == base, onClick = {
                        search = base
                        vault.setPref("search", base)
                    }, label = { Text(name) })
                }
            }
        }

        Section("Downloads") {
            Text("Qualidade por defeito", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.entries.forEach { q ->
                    FilterChip(selected = quality == q, onClick = {
                        quality = q
                        vault.setPref("quality", q.name)
                    }, label = { Text(q.label) })
                }
            }
        }

        Section("O que fica no telemóvel") {
            Text(
                "Fotos, vídeos, nomes, miniaturas e definições ficam cifrados (AES-256) dentro da área privada " +
                    "da app. Não aparecem na galeria, não vão para a nuvem nem para cópias de segurança, e o " +
                    "ecrã do cofre não pode ser fotografado nem gravado.\n\n" +
                    "O que o Android ainda sabe: que existe uma app chamada Calculadora, quanto tempo foi usada " +
                    "e quanto espaço ocupa. Se desinstalar a app, o cofre é apagado com ela.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Zona de perigo") {
            Button(
                onClick = { showWipe = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apagar todo o conteúdo deste cofre") }
        }

        Text(
            "Edie Run 1.1",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
    }

    if (showPin) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showPin = false },
            title = { Text("Novo PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrivateTextField(p1, { p1 = it }, "Novo PIN (4 a 12 algarismos)", numericPassword = true)
                    PrivateTextField(p2, { p2 = it }, "Repita o novo PIN", numericPassword = true)
                    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    when {
                        !VaultStore.isValidPin(p1) -> err = "O PIN deve ter entre 4 e 12 algarismos."
                        p1 != p2 -> err = "Os PINs não coincidem."
                        else -> {
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.Default) { VaultStore.changePin(ctx, vault, p1) }
                                busy = false
                                if (ok) {
                                    showPin = false
                                    Toast.makeText(ctx, "PIN alterado.", Toast.LENGTH_SHORT).show()
                                } else {
                                    err = "Esse PIN não pode ser usado. Escolha outro."
                                }
                            }
                        }
                    }
                }) { Text(if (busy) "A guardar…" else "Guardar") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showPin = false }) { Text("Cancelar") } }
        )
    }

    if (showWipe) {
        var confirm by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showWipe = false },
            title = { Text("Apagar tudo?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Todos os ficheiros deste cofre serão apagados para sempre. Escreva APAGAR para confirmar.")
                    PrivateTextField(confirm, { confirm = it }, "APAGAR")
                }
            },
            confirmButton = {
                TextButton(enabled = confirm.trim().uppercase() == "APAGAR", onClick = {
                    showWipe = false
                    scope.launch {
                        withContext(Dispatchers.IO) { vault.wipeAll() }
                        Toast.makeText(ctx, "Cofre esvaziado.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Apagar tudo") }
            },
            dismissButton = { TextButton(onClick = { showWipe = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
        Spacer(Modifier.height(2.dp))
    }
}
