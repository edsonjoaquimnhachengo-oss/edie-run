package com.edierun.vault.ui

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * Ecrã de entrada: uma calculadora ao estilo do iPhone que funciona de verdade.
 * Escrever o PIN (só algarismos) e carregar em "=" abre o cofre correspondente.
 */
class CalculatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            CalculatorRoot(onUnlocked = {
                startActivity(Intent(this, VaultActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            })
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
    val style = remember { CalcPrefs.get(ctx) }
    val calcDark = when (style) {
        CalcStyle.DARK -> true
        CalcStyle.LIGHT -> false
        CalcStyle.AUTO -> isSystemInDarkTheme()
    }
    if (!setUp) {
        EdieTheme(forceDark = true) {
            SetupScreen(onDone = {
                setUp = true
                showHint = true
            })
        }
    } else {
        EdieTheme(forceDark = calcDark) {
            Calculator(calcDark, onUnlocked)
            if (showHint) {
                IosAlert(
                    title = "Cofre criado",
                    message = "A partir de agora a app aparece como Calculadora e funciona como uma.\n\n" +
                        "Para entrar: escreva o PIN e carregue em =.\n\n" +
                        "Ao sair da app, ou ao desligar o ecrã, o cofre fecha-se sozinho.",
                    onDismiss = { showHint = false },
                    actions = listOf(AlertAction("Entendi", bold = true) { showHint = false })
                )
            }
        }
    }
}

// ---------------------------------------------------------------- calculadora

private const val OPS = "+−×÷"

private class CalcPalette(
    val bg: Color,
    val digit: Color, val digitPressed: Color, val digitText: Color,
    val func: Color, val funcPressed: Color, val funcText: Color,
    val op: Color, val opPressed: Color,
    val display: Color, val history: Color
)

private val darkPalette = CalcPalette(
    bg = Color.Black,
    digit = Color(0xFF333333), digitPressed = Color(0xFF737373), digitText = Color.White,
    func = Color(0xFFA5A5A5), funcPressed = Color(0xFFD9D9D9), funcText = Color.Black,
    op = Color(0xFFFF9F0A), opPressed = Color(0xFFFCC78D),
    display = Color.White, history = Color(0xFF8E8E93)
)

private val lightPalette = CalcPalette(
    bg = Color(0xFFF2F2F7),
    digit = Color.White, digitPressed = Color(0xFFD9D9D9), digitText = Color.Black,
    func = Color(0xFFD4D4D2), funcPressed = Color(0xFFB0B0AE), funcText = Color.Black,
    op = Color(0xFFFF9500), opPressed = Color(0xFFFFC27A),
    display = Color.Black, history = Color(0xFF8E8E93)
)

@Composable
private fun Calculator(dark: Boolean, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pal = if (dark) darkPalette else lightPalette
    var expr by rememberSaveable { mutableStateOf("") }
    var history by rememberSaveable { mutableStateOf("") }
    var justEvaluated by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    fun press(k: String) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        error = false
        when (k) {
            "AC" -> { expr = ""; history = ""; justEvaluated = false }
            "C" -> { expr = ""; justEvaluated = false }
            "⌫" -> { if (!justEvaluated) expr = expr.dropLast(1) }
            "=" -> {
                val raw = expr
                if (VaultStore.isValidPin(raw)) {
                    scope.launch {
                        val v = withContext(Dispatchers.Default) {
                            val first = VaultStore.tryUnlock(ctx, raw)
                            if (first != null && first.isClosed) VaultStore.tryUnlock(ctx, raw) else first
                        }
                        if (v != null) {
                            if (Session.vault != null) Session.lock(ctx)
                            Session.vault = v
                            expr = ""
                            history = ""
                            justEvaluated = false
                            onUnlocked()
                        }
                    }
                }
                if (raw.isNotEmpty() && !justEvaluated) {
                    val r = CalcEngine.evaluate(raw)
                    if (r != null) {
                        history = raw
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
                justEvaluated = false
                if (expr.isNotEmpty() && expr.last().isDigit()) expr += "%"
            }
            "±" -> {
                justEvaluated = false
                expr = CalcEngine.toggleSign(expr)
            }
            "," -> {
                if (justEvaluated) { expr = ""; justEvaluated = false }
                val segment = expr.takeLastWhile { it.isDigit() || it == '.' }
                if (!segment.contains('.')) expr += if (segment.isEmpty()) "0." else "."
            }
            else -> {
                if (justEvaluated) { expr = ""; justEvaluated = false }
                val segment = expr.takeLastWhile { it.isDigit() || it == '.' }
                when {
                    expr.length >= 40 -> {}
                    expr.isNotEmpty() && expr.last() == '%' -> {}
                    segment == "0" -> expr = expr.dropLast(1) + k
                    else -> expr += k
                }
            }
        }
    }

    val activeOp = if (!justEvaluated && expr.isNotEmpty() && expr.last() in OPS) expr.last().toString() else null
    val clearLabel = if (expr.isEmpty()) "AC" else "C"

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // visor: deslizar o dedo para a esquerda ou direita apaga o último algarismo (como no iPhone)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (abs(total) > 40f) press("⌫") },
                        onHorizontalDrag = { _, d -> total += d }
                    )
                }
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            BasicText(
                text = CalcEngine.display(history),
                style = TextStyle(color = pal.history, fontSize = 30.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.End),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 30.sp, stepSize = 1.sp),
                modifier = Modifier.fillMaxWidth()
            )
            BasicText(
                text = if (error) "Erro" else if (expr.isEmpty()) "0" else CalcEngine.display(expr),
                style = TextStyle(color = pal.display, fontSize = 88.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.End),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 88.sp, stepSize = 2.sp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 14.dp
            val key = (maxWidth - gap * 3) / 4
            val rows = listOf(
                listOf(clearLabel, "±", "%", "÷"),
                listOf("7", "8", "9", "×"),
                listOf("4", "5", "6", "−"),
                listOf("1", "2", "3", "+")
            )
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { k -> CalcKey(k, key, key, pal, active = k == activeOp) { press(k) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CalcKey("0", key * 2 + gap, key, pal, wide = true) { press("0") }
                    CalcKey(",", key, key, pal) { press(",") }
                    CalcKey("=", key, key, pal) { press("=") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CalcKey(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    pal: CalcPalette,
    wide: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val isOp = label in listOf("+", "−", "×", "÷", "=")
    val isFunc = label in listOf("AC", "C", "±", "%")
    val base = when {
        isOp && active -> Color.White
        isOp -> pal.op
        isFunc -> pal.func
        else -> pal.digit
    }
    val pressedColor = when {
        isOp -> pal.opPressed
        isFunc -> pal.funcPressed
        else -> pal.digitPressed
    }
    // como no iOS: acende logo ao tocar e apaga devagar
    val bg by animateColorAsState(if (pressed) pressedColor else base, tween(if (pressed) 0 else 420), label = "key")
    val fg = when {
        isOp && active -> pal.op
        isOp -> Color.White
        isFunc -> pal.funcText
        else -> pal.digitText
    }
    val text = if (label == "±") "+/−" else label
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            text,
            color = fg,
            fontSize = when {
                isOp -> 40.sp
                isFunc -> if (label == "±") 26.sp else 30.sp
                else -> 36.sp
            },
            fontWeight = if (isFunc) FontWeight.Medium else FontWeight.Normal,
            modifier = if (wide) Modifier.padding(start = height * 0.36f) else Modifier
        )
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

    fun plain(v: BigDecimal): String = normalized(v).toPlainString().replace('-', '−')

    /** Troca o sinal do número que está a ser escrito. */
    fun toggleSign(expr: String): String {
        if (expr.isEmpty()) return "−"
        val numLen = expr.takeLastWhile { it.isDigit() || it == '.' || it == '%' }.length
        val start = expr.length - numLen
        val before = expr.substring(0, start)
        val num = expr.substring(start)
        // um "−" é sinal (e não subtracção) se estiver no início ou logo a seguir a outro operador
        if (before.endsWith("−") && (before.length == 1 || before[before.length - 2] in OPS)) {
            return before.dropLast(1) + num
        }
        return before + "−" + num
    }

    /** Formato do iPhone em português: espaço nos milhares, vírgula nas casas decimais. */
    fun display(expr: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                val num = expr.substring(start, i)
                val intPart = num.substringBefore('.')
                val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
                sb.append(grouped)
                if (num.contains('.')) sb.append(',').append(num.substringAfter('.'))
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
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

// ---------------------------------------------------------------- configuração inicial (estilo ecrã de bloqueio)

private val lockGradient = Brush.verticalGradient(listOf(Color(0xFF1B2A4A), Color(0xFF2B1F45), Color(0xFF0B0B12)))

@Composable
private fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var mainPin by remember { mutableStateOf("") }
    var emergencyPin by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }

    fun fail(msg: String) {
        error = msg
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        scope.launch {
            for (x in listOf(-18f, 16f, -12f, 9f, -5f, 0f)) shake.animateTo(x, tween(45))
        }
    }

    fun ok() {
        error = ""
        when (step) {
            1 -> if (!VaultStore.isValidPin(entry)) {
                fail("O código deve ter entre 4 e 12 algarismos.")
            } else {
                mainPin = entry; entry = ""; step = 2
            }
            2 -> if (entry != mainPin) {
                fail("Os códigos não coincidem. Tente outra vez.")
                entry = ""; mainPin = ""; step = 1
            } else {
                entry = ""; step = 3
            }
            3 -> when {
                !VaultStore.isValidPin(entry) -> fail("O código deve ter entre 4 e 12 algarismos.")
                entry == mainPin -> { fail("Tem de ser diferente do código principal."); entry = "" }
                else -> { emergencyPin = entry; entry = ""; step = 4 }
            }
            4 -> if (entry != emergencyPin) {
                fail("Os códigos não coincidem. Tente outra vez.")
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

    Box(
        Modifier
            .fillMaxSize()
            .background(lockGradient)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        when (step) {
            0 -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(40.dp))
                    Box(
                        Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF5E5CE6)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(EdieIcons.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Edie Run", style = IosType.largeTitle, color = Color.White)
                    Text("Cofre privado para vídeos e imagens", style = IosType.subhead, color = Color(0xB3FFFFFF))
                    Spacer(Modifier.height(28.dp))
                    SetupPoint("Código principal", "Abre o seu cofre.")
                    SetupPoint(
                        "Código de emergência",
                        "Abre um segundo cofre, separado e com o mesmo aspecto. Use-o se alguém o obrigar a abrir a app."
                    )
                    SetupPoint("Disfarce", "A app passa a aparecer como uma calculadora. Para entrar, escreve o código e carrega em =.")
                    SetupPoint("Sem recuperação", "Se esquecer o código, o conteúdo fica cifrado para sempre. Ninguém o consegue abrir.")
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.White)
                        .iosClick(pressedAlpha = 0.7f, pressedScale = 0.98f) { step = 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Começar", style = IosType.headline, color = Color.Black)
                }
            }
            5 -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text("A criar os cofres cifrados…", color = Color.White, style = IosType.body)
            }
            else -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(54.dp))
                Icon(EdieIcons.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    when (step) {
                        1 -> "Crie o código principal"
                        2 -> "Repita o código principal"
                        3 -> "Crie o código de emergência"
                        else -> "Repita o código de emergência"
                    },
                    style = IosType.title3,
                    color = Color.White
                )
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.offset(x = shake.value.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val n = maxOf(4, entry.length)
                    for (i in 0 until n) {
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .border(1.2.dp, Color.White, CircleShape)
                                .background(if (i < entry.length) Color.White else Color.Transparent)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    error.ifEmpty { "4 a 12 algarismos" },
                    color = if (error.isNotEmpty()) Color(0xFFFF6961) else Color(0x99FFFFFF),
                    style = IosType.footnote,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp)
                )
                Spacer(Modifier.weight(1f))
                PinPad(
                    onDigit = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (entry.length < 12) entry += it
                    },
                    onDelete = { entry = entry.dropLast(1) },
                    onOk = { ok() },
                    okEnabled = entry.length >= 4
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SetupPoint(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = IosType.headline, color = Color.White)
        Text(text, style = IosType.subhead, color = Color(0xB3FFFFFF))
    }
}

private val keyLetters = mapOf(
    "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
    "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ"
)

/** Teclado numérico do ecrã de bloqueio do iPhone. */
@Composable
fun PinPad(onDigit: (String) -> Unit, onDelete: () -> Unit, onOk: () -> Unit, okEnabled: Boolean = true) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                row.forEach { d -> PinKey(d, keyLetters[d]) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(78.dp).iosClick(onClick = onDelete), contentAlignment = Alignment.Center) {
                Text("Apagar", color = Color.White, style = IosType.body)
            }
            PinKey("0", null) { onDigit("0") }
            Box(Modifier.size(78.dp).iosClick(enabled = okEnabled, onClick = onOk), contentAlignment = Alignment.Center) {
                Text("OK", color = if (okEnabled) Color.White else Color(0x55FFFFFF), style = IosType.headline)
            }
        }
    }
}

@Composable
private fun PinKey(digit: String, letters: String?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed) Color(0x99FFFFFF) else Color(0x2EFFFFFF),
        tween(if (pressed) 0 else 380),
        label = "pin"
    )
    Box(
        Modifier
            .size(78.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Normal)
            if (letters != null) {
                Text(letters, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            }
        }
    }
}
