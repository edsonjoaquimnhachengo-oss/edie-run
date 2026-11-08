package com.edierun.vault.ui

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
    "Brave Search" to "https://search.brave.com/search?q=",
    "Google" to "https://www.google.com/search?q="
)

@Composable
fun SettingsScreen(vault: Vault, onLock: () -> Unit) {
    val ctx = LocalContext.current
    val ios = LocalIos.current
    val scope = rememberCoroutineScope()
    var torDefault by remember { mutableStateOf(vault.getBoolPref("torDefault", false)) }
    var search by remember { mutableStateOf(vault.getPref("search", ENGINES[0].second)) }
    var quality by remember { mutableStateOf(Quality.from(vault.getPref("quality", Quality.HIGH.name))) }
    var calcStyle by remember { mutableStateOf(CalcPrefs.get(ctx)) }
    var showPin by remember { mutableStateOf(false) }
    var showWipe by remember { mutableStateOf(false) }

    fun saveTheme() {
        scope.launch(Dispatchers.IO) { AppTheme.save(vault) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        IosLargeHeader("Definições")
        Spacer(Modifier.height(10.dp))

        // ---------- Aparência
        IosGroup(header = "Aparência") {
            Column(Modifier.padding(16.dp)) {
                ThemePreviewRow(AppTheme.mode) { m ->
                    AppTheme.mode = m
                    saveTheme()
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        IosGroup(header = "Cor de destaque", footer = "Usada nos botões, ícones seleccionados e ligações.") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Accent.entries.forEach { a ->
                    val selected = AppTheme.accent == a
                    val ring by animateDpAsState(if (selected) 2.5.dp else 0.dp, label = "ring")
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .border(ring, ios.label.copy(alpha = 0.85f), CircleShape)
                            .padding(if (selected) 4.dp else 0.dp)
                            .clip(CircleShape)
                            .background(a.color(ios.isDark))
                            .iosClick(pressedScale = 0.85f) {
                                AppTheme.accent = a
                                saveTheme()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Filled.Check, contentDescription = a.label, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        IosGroup(header = "Calculadora", footer = "Aspecto da calculadora que esconde o cofre.") {
            Box(Modifier.padding(12.dp)) {
                IosSegmented(
                    options = CalcStyle.entries.toList(),
                    selected = calcStyle,
                    label = { it.label },
                    onSelect = {
                        calcStyle = it
                        CalcPrefs.set(ctx, it)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ---------- Segurança
        Spacer(Modifier.height(22.dp))
        IosGroup(header = "Segurança", footer = "O cofre fecha-se sozinho quando sai da app, muda de app ou desliga o ecrã.") {
            IosRow("Fechar o cofre agora", icon = Icons.Filled.Lock, iconBg = Color(0xFF8E8E93), onClick = onLock)
            IosRow("Mudar o código deste cofre", icon = Icons.Filled.Star, iconBg = ios.orange, chevron = true, last = true, onClick = { showPin = true })
        }

        // ---------- Navegador
        Spacer(Modifier.height(22.dp))
        IosGroup(header = "Navegador Edie Run") {
            IosRow(
                "Abrir sempre com Tor",
                subtitle = "Esconde o seu IP dos sites. Fica mais lento.",
                icon = EdieIcons.Shield,
                iconBg = Color(0xFF7D4698),
                last = true
            ) {
                IosSwitch(torDefault) {
                    torDefault = it
                    vault.setPref("torDefault", it)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        IosGroup(header = "Motor de pesquisa") {
            ENGINES.forEachIndexed { i, (name, base) ->
                IosRow(
                    name,
                    icon = if (i == 0) Icons.Filled.Search else null,
                    iconBg = if (i == 0) ios.accent else null,
                    checked = search == base,
                    last = i == ENGINES.lastIndex,
                    onClick = {
                        search = base
                        vault.setPref("search", base)
                    }
                )
            }
        }

        // ---------- Downloads
        Spacer(Modifier.height(22.dp))
        IosGroup(header = "Qualidade dos downloads") {
            Quality.entries.forEachIndexed { i, q ->
                IosRow(
                    q.label,
                    checked = quality == q,
                    last = i == Quality.entries.lastIndex,
                    onClick = {
                        quality = q
                        vault.setPref("quality", q.name)
                    }
                )
            }
        }

        // ---------- Privacidade
        Spacer(Modifier.height(22.dp))
        IosGroup(
            header = "O que fica no telemóvel",
            footer = "Fotos, vídeos, nomes, miniaturas e definições ficam cifrados (AES-256) na área privada da app. " +
                "Não aparecem na galeria, não vão para a nuvem nem para cópias de segurança, e o ecrã do cofre " +
                "não pode ser fotografado nem gravado.\n\nO Android sabe que existe uma app chamada Calculadora, " +
                "quanto tempo foi usada e quanto espaço ocupa. Desinstalar a app apaga o cofre."
        ) {
            IosRow("Cifra", value = "AES-256-GCM")
            IosRow("Cópias na nuvem", value = "Nunca", last = true)
        }

        // ---------- Perigo
        Spacer(Modifier.height(22.dp))
        IosGroup {
            IosRow(
                "Apagar todo o conteúdo deste cofre",
                icon = Icons.Filled.Delete,
                iconBg = ios.red,
                titleColor = ios.red,
                last = true,
                onClick = { showWipe = true }
            )
        }

        Text(
            "Edie Run 1.2",
            style = IosType.footnote,
            color = ios.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp)
        )
        Spacer(Modifier.height(LocalBottomInset.current + 20.dp))
    }

    if (showPin) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        IosAlert(
            title = "Novo código",
            message = err.ifEmpty { "4 a 12 algarismos" },
            onDismiss = { if (!busy) showPin = false },
            actions = listOf(
                AlertAction("Cancelar", enabled = !busy) { showPin = false },
                AlertAction(if (busy) "A guardar…" else "Guardar", bold = true, enabled = !busy) {
                    when {
                        !VaultStore.isValidPin(p1) -> err = "O código deve ter entre 4 e 12 algarismos."
                        p1 != p2 -> err = "Os códigos não coincidem."
                        else -> {
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.Default) { VaultStore.changePin(ctx, vault, p1) }
                                busy = false
                                if (ok) {
                                    showPin = false
                                    Toast.makeText(ctx, "Código alterado.", Toast.LENGTH_SHORT).show()
                                } else {
                                    err = "Esse código não pode ser usado. Escolha outro."
                                }
                            }
                        }
                    }
                }
            )
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrivateTextField(p1, { p1 = it }, "Novo código", modifier = Modifier.fillMaxWidth(), numericPassword = true)
                PrivateTextField(p2, { p2 = it }, "Repita o código", modifier = Modifier.fillMaxWidth(), numericPassword = true)
            }
        }
    }

    if (showWipe) {
        var confirm by remember { mutableStateOf("") }
        IosAlert(
            title = "Apagar tudo?",
            message = "Todos os ficheiros deste cofre serão apagados para sempre. Escreva APAGAR para confirmar.",
            onDismiss = { showWipe = false },
            actions = listOf(
                AlertAction("Cancelar", bold = true) { showWipe = false },
                AlertAction("Apagar tudo", destructive = true, enabled = confirm.trim().uppercase() == "APAGAR") {
                    showWipe = false
                    scope.launch {
                        withContext(Dispatchers.IO) { vault.wipeAll() }
                        Toast.makeText(ctx, "Cofre esvaziado.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        ) {
            PrivateTextField(confirm, { confirm = it }, "APAGAR", modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Escolha de tema com pré-visualizações, como em Definições › Ecrã e brilho do iPhone. */
@Composable
private fun ThemePreviewRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val ios = LocalIos.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ThemeMode.entries.forEach { m ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.iosClick(pressedScale = 0.95f) { onSelect(m) }
            ) {
                Box(
                    Modifier
                        .size(width = 70.dp, height = 120.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .border(
                            if (selected == m) 2.5.dp else 0.5.dp,
                            if (selected == m) ios.accent else ios.separator,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                ) {
                    when (m) {
                        ThemeMode.LIGHT -> MiniScreen(dark = false, Modifier.fillMaxSize())
                        ThemeMode.DARK -> MiniScreen(dark = true, Modifier.fillMaxSize())
                        ThemeMode.SYSTEM -> Row(Modifier.fillMaxSize()) {
                            MiniScreen(dark = false, Modifier.weight(1f).fillMaxSize())
                            MiniScreen(dark = true, Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(m.label, style = IosType.subhead, color = ios.label)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected == m) ios.accent else Color.Transparent)
                        .border(1.5.dp, if (selected == m) ios.accent else ios.tertiaryLabel, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected == m) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun MiniScreen(dark: Boolean, modifier: Modifier) {
    val bg = if (dark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val cell = if (dark) Color(0xFF2C2C2E) else Color.White
    val accent = AppTheme.accent.color(dark)
    Column(modifier.background(bg).padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth(0.6f).height(7.dp).clip(CircleShape).background(if (dark) Color.White else Color.Black))
        repeat(3) {
            Box(Modifier.fillMaxWidth().height(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(cell))
        }
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(CircleShape).background(accent))
    }
}
