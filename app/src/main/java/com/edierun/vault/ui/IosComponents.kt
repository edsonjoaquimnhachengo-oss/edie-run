package com.edierun.vault.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Estado de desfocagem do ecrã actual (vidro tipo iOS). */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** Espaço ocupado pela barra de separadores flutuante, para o conteúdo não ficar por baixo. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/** Vidro desfocado (estilo "Liquid Glass"): desfoca o que está por trás, com tom e contorno subtis. */
@Composable
fun Modifier.glass(shape: Shape, haze: HazeState? = LocalHaze.current, tint: Color? = null): Modifier {
    val ios = LocalIos.current
    val t = tint ?: ios.glassTint
    val base = this
        .shadow(if (ios.isDark) 0.dp else 12.dp, shape, ambientColor = Color(0x1A000000), spotColor = Color(0x26000000))
        .clip(shape)
    val glassy = if (haze != null) {
        base.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = ios.background,
                tints = listOf(HazeTint(t)),
                blurRadius = 26.dp,
                noiseFactor = 0f
            )
        )
    } else {
        base.background(t)
    }
    return glassy.border(0.6.dp, ios.glassBorder, shape)
}

/** Toque ao estilo iOS: sem ondulação; o elemento fica mais transparente (e opcionalmente encolhe) enquanto é premido. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.iosClick(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.4f,
    pressedScale: Float = 1f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val a by animateFloatAsState(if (pressed) pressedAlpha else 1f, tween(if (pressed) 50 else 260), label = "a")
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "s")
    this
        .graphicsLayer {
            alpha = a
            scaleX = s
            scaleY = s
        }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
}

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val ios = LocalIos.current
    val off = if (ios.isDark) Color(0x52787880) else Color(0x29787880)
    val track by animateColorAsState(if (checked) ios.green else off, tween(200), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.72f, stiffness = 520f), label = "knob")
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(LocalIos.current.separator))
}

/** Grupo de linhas arredondado, como nas Definições do iPhone. */
@Composable
fun IosGroup(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val ios = LocalIos.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = IosType.footnote,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ios.cell),
            content = content
        )
        if (footer != null) {
            Text(
                footer,
                style = IosType.footnote,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)
            )
        }
    }
}

@Composable
fun IosRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBg: Color? = null,
    value: String? = null,
    titleColor: Color? = null,
    chevron: Boolean = false,
    checked: Boolean = false,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val ios = LocalIos.current
    val inset = if (icon != null) 58.dp else 16.dp
    val sep = ios.separator
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .then(if (onClick != null) Modifier.iosClick(pressedAlpha = 0.55f, onClick = onClick) else Modifier)
            .drawBehind {
                if (!last) {
                    val y = size.height - 0.5.dp.toPx() / 2
                    drawLine(sep, Offset(inset.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
                }
            }
            .padding(start = 16.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(29.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconBg ?: ios.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = IosType.body, color = titleColor ?: ios.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = IosType.footnote, color = ios.secondaryLabel)
            }
        }
        if (value != null) {
            Text(value, style = IosType.body, color = ios.secondaryLabel, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        if (trailing != null) trailing()
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = ios.accent, modifier = Modifier.size(20.dp))
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ios.tertiaryLabel,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Controlo segmentado do iOS. */
@Composable
fun <T> IosSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val ios = LocalIos.current
    BoxWithConstraints(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(ios.fill)
            .padding(2.dp)
    ) {
        val segW = maxWidth / options.size
        val idx = options.indexOf(selected).coerceAtLeast(0)
        val x by animateDpAsState(segW * idx, spring(dampingRatio = 0.82f, stiffness = 600f), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .shadow(if (ios.isDark) 0.dp else 3.dp, RoundedCornerShape(7.dp))
                .clip(RoundedCornerShape(7.dp))
                .background(if (ios.isDark) Color(0xFF636366) else Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { o ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(o) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(o),
                        fontSize = 13.sp,
                        fontWeight = if (o == selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = ios.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

data class AlertAction(
    val label: String,
    val destructive: Boolean = false,
    val bold: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/** Alerta centrado ao estilo iOS. */
@Composable
fun IosAlert(
    title: String,
    message: String? = null,
    onDismiss: () -> Unit,
    actions: List<AlertAction>,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val ios = LocalIos.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(286.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (ios.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = IosType.headline, color = ios.label, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = IosType.footnote, color = ios.label, textAlign = TextAlign.Center)
                }
                if (content != null) {
                    Spacer(Modifier.height(14.dp))
                    content()
                }
            }
            Hairline()
            if (actions.size == 2) {
                Row(Modifier.fillMaxWidth().height(46.dp)) {
                    AlertButton(actions[0], Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(ios.separator))
                    AlertButton(actions[1], Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                actions.forEachIndexed { i, a ->
                    if (i > 0) Hairline()
                    AlertButton(a, Modifier.fillMaxWidth().height(46.dp))
                }
            }
        }
    }
}

@Composable
private fun AlertButton(a: AlertAction, modifier: Modifier) {
    val ios = LocalIos.current
    Box(
        modifier.iosClick(enabled = a.enabled, pressedAlpha = 0.5f, onClick = a.onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            a.label,
            fontSize = 17.sp,
            fontWeight = if (a.bold) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !a.enabled -> ios.tertiaryLabel
                a.destructive -> ios.red
                else -> ios.accent
            }
        )
    }
}

/** Cabeçalho de título grande do iOS. */
@Composable
fun IosLargeHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val ios = LocalIos.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IosType.largeTitle, color = ios.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = IosType.subhead, color = ios.secondaryLabel, maxLines = 1)
            }
        }
        if (trailing != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** Botão em cápsula de vidro (ex.: "Seleccionar"). */
@Composable
fun GlassPill(text: String, modifier: Modifier = Modifier, color: Color? = null, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(
        modifier
            .height(36.dp)
            .glass(CircleShape)
            .iosClick(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color ?: ios.label)
    }
}

/** Botão redondo de vidro com ícone. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val ios = LocalIos.current
    Box(
        modifier
            .size(size)
            .glass(CircleShape)
            .iosClick(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) (tint ?: ios.label) else ios.tertiaryLabel,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/** Botão principal em cápsula, cheio com a cor de destaque. */
@Composable
fun IosPrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(if (enabled) (color ?: ios.accent) else ios.fill)
            .iosClick(enabled = enabled, pressedAlpha = 0.7f, pressedScale = 0.98f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = IosType.headline, color = if (enabled) Color.White else ios.tertiaryLabel)
    }
}

/** Botão de texto simples (cor de destaque), como "Cancelar" ou "Concluído". */
@Composable
fun IosTextButton(text: String, modifier: Modifier = Modifier, bold: Boolean = false, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val ios = LocalIos.current
    Text(
        text,
        fontSize = 17.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (enabled) (color ?: ios.accent) else ios.tertiaryLabel,
        modifier = modifier
            .iosClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}
