package com.edierun.vault.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext

/**
 * Ecrã de entrada. Funciona como uma calculadora normal.
 * Escrever o PIN (só algarismos) e carregar em "=" abre o cofre correspondente.
 */
class CalculatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            EdieTheme {
                CalculatorRoot(onUnlocked = {
                    startActivity(Intent(this, VaultActivity::class.java))
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.value?.let { url ->
            Session.pendingUrl = url.trimEnd(')', ']', '"', '\'', '.', ',')
        }
    }
}

@Composable
private fun CalculatorRoot(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var setUp by remember { mutableStateOf(VaultStore.isSetUp(ctx)) }
    var showHint by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!setUp) {
            SetupScreen(onDone = {
                setUp = true
                showHint = true
            })
        } else {
            Calculator(onUnlocked)
        }
    }
    if (showHint) {
        AlertDialog(
            onDismissRequest = { showHint = false },
            confirmButton = { TextButton(onClick = { showHint = false }) { Text("Entendi") } },
            title = { Text("Cofre criado") },
            text = {
                Text(
                    "A partir de agora a app aparece como Calculadora e funciona como uma.\n\n" +
                        "Para entrar: escreva o PIN e carregue em =.\n\n" +
                        "Ao sair da app, ou ao desligar o ecrã, o cofre fecha-se sozinho."
                )
            }
        )
    }
}

// ---------------------------------------------------------------- calculadora

private const val OPS = "+−×÷"

@Composable
private fun Calculator(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expr by rememberSaveable { mutableStateOf("") }
    var justEvaluated by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val preview = remember(expr) {
        if (expr.any { it in OPS || it == '%' }) CalcEngine.evaluate(expr)?.let { CalcEngine.display(it) } ?: "" else ""
    }

    fun press(k: String) {
        error = false
        when (k) {
            "C" -> { expr = ""; justEvaluated = false }
            "⌫" -> { expr = expr.dropLast(1); justEvaluated = false }
            "=" -> {
                val raw = expr
                if (VaultStore.isValidPin(raw)) {
                    scope.launch {
                        val v = withContext(Dispatchers.Default) {
                            val first = VaultStore.tryUnlock(ctx, raw)
                            // um download pode ter acabado e fechado o cofre entretanto
                            if (first != null && first.isClosed) VaultStore.tryUnlock(ctx, raw) else first
                        }
                        if (v != null) {
                            if (Session.vault != null) Session.lock(ctx)
                            Session.vault = v
                            expr = ""
                            justEvaluated = false
                            onUnlocked()
                        }
                    }
                }
                if (raw.isNotEmpty()) {
                    val r = CalcEngine.evaluate(raw)
                    if (r != null) {
                        expr = CalcEngine.plain(r)
                        justEvaluated = true
                    } else {
                        error = true
                    }
                }
            }
            "+", "−", "×", "÷" -> {
                justEvaluated = false
                if (expr.isEmpty()) {
                    if (k == "−") expr = "−"
                } else if (expr.last() in OPS) {
                    if (expr.length > 1) expr = expr.dropLast(1) + k
                } else if (expr.last() != '.') {
                    expr += k
                }
            }
            "%" -> {
                if (expr.isNotEmpty() && (expr.last().isDigit())) expr += "%"
                justEvaluated = false
            }
            "," -> {
                if (justEvaluated) { expr = ""; justEvaluated = false }
                val segment = expr.takeLastWhile { it.isDigit() || it == '.' }
                if (!segment.contains('.')) expr += if (segment.isEmpty()) "0." else "."
            }
            else -> { // algarismos
                if (justEvaluated) { expr = ""; justEvaluated = false }
                if (expr.length < 40 && !(expr.isNotEmpty() && expr.last() == '%')) expr += k
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = if (expr.isEmpty()) "0" else expr.replace('.', ','),
                color = Color.White,
                fontSize = if (expr.length > 12) 40.sp else 60.sp,
                fontWeight = FontWeight.Light,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
            Text(
                text = if (error) "Erro" else preview,
                color = Color(0xFF9AA0A6),
                fontSize = 28.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(16.dp))
        }
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("00", "0", ",", "=")
        )
        Column(Modifier.fillMaxWidth().height(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        CalcKey(k) {
                            if (k == "00") { press("0"); press("0") } else press(k)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CalcKey(label: String, onClick: () -> Unit) {
    val bg = when (label) {
        "=" -> Color(0xFFF28C28)
        "+", "−", "×", "÷" -> Color(0xFF3B3F46)
        "C", "⌫", "%" -> Color(0xFF5A5F67)
        else -> Color(0xFF1F2227)
    }
    val fg = when (label) {
        "+", "−", "×", "÷" -> Color(0xFFF2A65A)
        else -> Color.White
    }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontSize = 28.sp)
    }
}

/** Avaliador simples de expressões: + − × ÷ e %. */
object CalcEngine {
    private val MC = MathContext.DECIMAL64

    fun evaluate(expr: String): BigDecimal? {
        if (expr.isEmpty()) return null
        return try {
            Parser(expr.trimEnd { it in OPS }).parse()
        } catch (_: Exception) {
            null
        }
    }

    private fun normalized(v: BigDecimal): BigDecimal {
        val r = v.round(MathContext(12)).stripTrailingZeros()
        return if (r.signum() == 0) BigDecimal.ZERO else r
    }

    fun plain(v: BigDecimal): String = normalized(v).toPlainString()

    fun display(v: BigDecimal): String {
        val r = normalized(v)
        val big = r.abs() >= BigDecimal("1E15")
        val tiny = r.signum() != 0 && r.abs() < BigDecimal("1E-9")
        return (if (big || tiny) r.toString() else r.toPlainString()).replace('.', ',')
    }

    private class Parser(val s: String) {
        var i = 0

        fun parse(): BigDecimal {
            if (s.isEmpty()) throw IllegalArgumentException()
            val v = expression()
            if (i != s.length) throw IllegalArgumentException()
            return v
        }

        fun expression(): BigDecimal {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '−')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v.add(r, MC) else v.subtract(r, MC)
            }
            return v
        }

        fun term(): BigDecimal {
            var v = factor()
            while (i < s.length && (s[i] == '×' || s[i] == '÷')) {
                val op = s[i++]
                val r = factor()
                v = if (op == '×') v.multiply(r, MC) else {
                    if (r.signum() == 0) throw ArithmeticException()
                    v.divide(r, MC)
                }
            }
            return v
        }

        fun factor(): BigDecimal {
            if (i < s.length && s[i] == '−') {
                i++
                return factor().negate()
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException()
            var v = BigDecimal(s.substring(start, i))
            while (i < s.length && s[i] == '%') {
                i++
                v = v.divide(BigDecimal(100), MC)
            }
            return v
        }
    }
}

// ---------------------------------------------------------------- configuração inicial

@Composable
private fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var mainPin by remember { mutableStateOf("") }
    var emergencyPin by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun ok() {
        error = ""
        when (step) {
            1 -> if (!VaultStore.isValidPin(entry)) {
                error = "O PIN deve ter entre 4 e 12 algarismos."
            } else {
                mainPin = entry; entry = ""; step = 2
            }
            2 -> if (entry != mainPin) {
                error = "Os PINs não coincidem. Comece de novo."
                entry = ""; mainPin = ""; step = 1
            } else {
                entry = ""; step = 3
            }
            3 -> when {
                !VaultStore.isValidPin(entry) -> error = "O PIN deve ter entre 4 e 12 algarismos."
                entry == mainPin -> { error = "O PIN de emergência tem de ser diferente do principal."; entry = "" }
                else -> { emergencyPin = entry; entry = ""; step = 4 }
            }
            4 -> if (entry != emergencyPin) {
                error = "Os PINs não coincidem. Comece de novo."
                entry = ""; emergencyPin = ""; step = 3
            } else {
                entry = ""
                step = 5
                scope.launch {
                    withContext(Dispatchers.Default) { VaultStore.setup(ctx, mainPin, emergencyPin) }
                    mainPin = ""; emergencyPin = ""
                    onDone()
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(22.dp)
    ) {
        when (step) {
            0 -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text("Edie Run", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Cofre privado para vídeos e imagens", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(22.dp))
                    Text(
                        "Vai criar dois PINs, só com algarismos (4 a 12):\n\n" +
                            "PIN principal: abre o seu cofre.\n\n" +
                            "PIN de emergência: abre um segundo cofre, separado e igual em aparência. " +
                            "Se alguém o obrigar a abrir a app, use este. Pode guardar lá umas fotos banais " +
                            "para parecer um cofre normal.\n\n" +
                            "Depois disto, a app passa a aparecer como uma calculadora que funciona. " +
                            "Para entrar, escreve o PIN e carrega em =.\n\n" +
                            "Importante: não existe recuperação. Se esquecer o PIN, o conteúdo fica cifrado " +
                            "para sempre e ninguém o consegue abrir, nem você.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Começar")
                }
            }
            5 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("A criar os cofres cifrados…")
                    }
                }
            }
            else -> {
                val title = when (step) {
                    1 -> "Crie o PIN principal"
                    2 -> "Repita o PIN principal"
                    3 -> "Crie o PIN de emergência"
                    else -> "Repita o PIN de emergência"
                }
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(30.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(24.dp))
                    PinDots(entry.length)
                    Spacer(Modifier.height(14.dp))
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                PinPad(
                    onDigit = { if (entry.length < 12) entry += it },
                    onDelete = { entry = entry.dropLast(1) },
                    onOk = { ok() }
                )
            }
        }
    }
}

@Composable
private fun PinDots(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val n = maxOf(4, count)
        for (i in 0 until n) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (i < count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@Composable
fun PinPad(onDigit: (String) -> Unit, onDelete: () -> Unit, onOk: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "OK")
    )
    Column(Modifier.fillMaxWidth().height(330.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (k == "OK") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                when (k) {
                                    "⌫" -> onDelete()
                                    "OK" -> onOk()
                                    else -> onDigit(k)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            k,
                            fontSize = 24.sp,
                            color = if (k == "OK") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
