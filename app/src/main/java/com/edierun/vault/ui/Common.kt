package com.edierun.vault.ui

import android.text.InputType
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import java.util.Locale

private val EdieColors = darkColorScheme(
    primary = Color(0xFF7FB8FF),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF1D3A5C),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFFFB86B),
    onSecondary = Color(0xFF3B2200),
    secondaryContainer = Color(0xFF4A3216),
    onSecondaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF0D1015),
    onBackground = Color(0xFFE4E7EC),
    surface = Color(0xFF12161C),
    onSurface = Color(0xFFE4E7EC),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFFBCC4CF),
    surfaceContainer = Color(0xFF181D24),
    surfaceContainerHigh = Color(0xFF1F252D),
    error = Color(0xFFFF8A80)
)

@Composable
fun EdieTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EdieColors, content = content)
}

/**
 * Campo de texto que pede ao teclado para NÃO aprender nem guardar o que se escreve
 * (modo incógnito do teclado). Usado para links, pesquisas e PINs.
 */
@Composable
fun PrivateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    numericPassword: Boolean = false,
    imeAction: Int = EditorInfo.IME_ACTION_DONE,
    onAction: () -> Unit = {}
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnAction by rememberUpdatedState(onAction)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f).toArgb()
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                EditText(ctx).apply {
                    this.hint = hint
                    setSingleLine(true)
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    background = null
                    val p = (10 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                    imeOptions = imeAction or
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    inputType = if (numericPassword) {
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                    setText(value)
                    doAfterTextChanged { currentOnChange(it?.toString() ?: "") }
                    setOnEditorActionListener { _, _, _ ->
                        currentOnAction()
                        true
                    }
                }
            },
            update = { et ->
                if (et.text.toString() != value) {
                    et.setText(value)
                    et.setSelection(value.length)
                }
            }
        )
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}
