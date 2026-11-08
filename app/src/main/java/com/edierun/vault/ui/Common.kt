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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import java.util.Locale

/**
 * Campo de texto ao estilo iOS que pede ao teclado para NÃO aprender nem guardar o que
 * se escreve (modo incógnito do teclado). Usado para links, pesquisas e PINs.
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
    val ios = LocalIos.current
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnAction by rememberUpdatedState(onAction)
    val textColor = ios.label.toArgb()
    val hintColor = ios.secondaryLabel.toArgb()
    val cursor = ios.accent.toArgb()
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(ios.fill)
            .padding(horizontal = 2.dp)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                EditText(ctx).apply {
                    this.hint = hint
                    setSingleLine(true)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    background = null
                    val ph = (10 * resources.displayMetrics.density).toInt()
                    val pv = (8 * resources.displayMetrics.density).toInt()
                    setPadding(ph, pv, ph, pv)
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
                et.setTextColor(textColor)
                et.setHintTextColor(hintColor)
                et.highlightColor = (cursor and 0x00FFFFFF) or 0x55000000
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
