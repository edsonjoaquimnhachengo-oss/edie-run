package com.edierun.vault.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.edierun.vault.vault.Vault

enum class ThemeMode(val label: String) { SYSTEM("Automático"), LIGHT("Claro"), DARK("Escuro") }

enum class Accent(val label: String, val light: Long, val dark: Long) {
    BLUE("Azul", 0xFF007AFF, 0xFF0A84FF),
    INDIGO("Índigo", 0xFF5856D6, 0xFF5E5CE6),
    PURPLE("Roxo", 0xFFAF52DE, 0xFFBF5AF2),
    PINK("Rosa", 0xFFFF2D55, 0xFFFF375F),
    RED("Vermelho", 0xFFFF3B30, 0xFFFF453A),
    ORANGE("Laranja", 0xFFFF9500, 0xFFFF9F0A),
    GREEN("Verde", 0xFF34C759, 0xFF30D158),
    TEAL("Turquesa", 0xFF30B0C7, 0xFF40C8E0),
    GRAPHITE("Grafite", 0xFF8E8E93, 0xFF98989D);

    fun color(dark: Boolean) = Color(if (dark) this.dark else light)
}

/** Paleta do sistema iOS (cores semânticas de "UIColor"). */
@Immutable
data class IosColors(
    val isDark: Boolean,
    val accent: Color,
    val background: Color,
    val groupedBackground: Color,
    val cell: Color,
    val cellElevated: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val glassTint: Color,
    val glassBorder: Color,
    val red: Color,
    val green: Color,
    val orange: Color,
    val yellow: Color
)

fun iosColors(dark: Boolean, accent: Accent): IosColors = if (dark) {
    IosColors(
        isDark = true,
        accent = accent.color(true),
        background = Color(0xFF000000),
        groupedBackground = Color(0xFF000000),
        cell = Color(0xFF1C1C1E),
        cellElevated = Color(0xFF2C2C2E),
        label = Color(0xFFFFFFFF),
        secondaryLabel = Color(0x99EBEBF5),
        tertiaryLabel = Color(0x4DEBEBF5),
        separator = Color(0x99545458),
        fill = Color(0x3D767680),
        glassTint = Color(0xB31C1C1E),
        glassBorder = Color(0x26FFFFFF),
        red = Color(0xFFFF453A),
        green = Color(0xFF30D158),
        orange = Color(0xFFFF9F0A),
        yellow = Color(0xFFFFD60A)
    )
} else {
    IosColors(
        isDark = false,
        accent = accent.color(false),
        background = Color(0xFFFFFFFF),
        groupedBackground = Color(0xFFF2F2F7),
        cell = Color(0xFFFFFFFF),
        cellElevated = Color(0xFFFFFFFF),
        label = Color(0xFF000000),
        secondaryLabel = Color(0x993C3C43),
        tertiaryLabel = Color(0x4D3C3C43),
        separator = Color(0x4A3C3C43),
        fill = Color(0x1F767680),
        glassTint = Color(0xBFF9F9F9),
        glassBorder = Color(0x1A000000),
        red = Color(0xFFFF3B30),
        green = Color(0xFF34C759),
        orange = Color(0xFFFF9500),
        yellow = Color(0xFFFFCC00)
    )
}

val LocalIos = staticCompositionLocalOf { iosColors(true, Accent.BLUE) }

/** Tamanhos de letra do iOS (Dynamic Type, tamanho padrão). */
object IosType {
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, lineHeight = 41.sp)
    val title1 = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
    val title2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val title3 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
}

private val iosTypography = Typography(
    headlineLarge = IosType.largeTitle,
    headlineMedium = IosType.title1,
    headlineSmall = IosType.title1,
    titleLarge = IosType.title2,
    titleMedium = IosType.headline,
    titleSmall = IosType.subhead.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = IosType.body,
    bodyMedium = IosType.subhead,
    bodySmall = IosType.footnote,
    labelLarge = IosType.subhead.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = IosType.caption,
    labelSmall = IosType.caption2
)

/** Tema escolhido pelo utilizador. Guardado (cifrado) nas definições de cada cofre. */
object AppTheme {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
    var accent by mutableStateOf(Accent.BLUE)

    fun load(vault: Vault) {
        mode = ThemeMode.entries.firstOrNull { it.name == vault.getPref("themeMode", "") } ?: ThemeMode.SYSTEM
        accent = Accent.entries.firstOrNull { it.name == vault.getPref("accent", "") } ?: Accent.BLUE
    }

    fun save(vault: Vault) {
        vault.setPref("themeMode", mode.name)
        vault.setPref("accent", accent.name)
    }

    fun reset() {
        mode = ThemeMode.SYSTEM
        accent = Accent.BLUE
    }
}

/** Estilo da calculadora (fora do cofre, por isso guardado à parte; não revela nada). */
enum class CalcStyle(val label: String) { DARK("Escura"), LIGHT("Clara"), AUTO("Automática") }

object CalcPrefs {
    private const val FILE = "look"

    fun get(ctx: Context): CalcStyle {
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("calc", null)
        return CalcStyle.entries.firstOrNull { it.name == v } ?: CalcStyle.DARK
    }

    fun set(ctx: Context, style: CalcStyle) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("calc", style.name).apply()
    }
}

@Composable
fun isAppDark(): Boolean = when (AppTheme.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdieTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isAppDark()
    val ios = iosColors(dark, AppTheme.accent)
    val scheme = if (dark) {
        darkColorScheme(
            primary = ios.accent,
            onPrimary = Color.White,
            secondary = ios.accent,
            background = ios.groupedBackground,
            onBackground = ios.label,
            surface = ios.background,
            onSurface = ios.label,
            surfaceVariant = ios.fill,
            onSurfaceVariant = ios.secondaryLabel,
            surfaceContainer = ios.cell,
            surfaceContainerHigh = ios.cellElevated,
            surfaceContainerHighest = ios.cellElevated,
            outline = ios.separator,
            outlineVariant = ios.separator,
            error = ios.red
        )
    } else {
        lightColorScheme(
            primary = ios.accent,
            onPrimary = Color.White,
            secondary = ios.accent,
            background = ios.groupedBackground,
            onBackground = ios.label,
            surface = ios.background,
            onSurface = ios.label,
            surfaceVariant = ios.fill,
            onSurfaceVariant = ios.secondaryLabel,
            surfaceContainer = ios.cell,
            surfaceContainerHigh = ios.cellElevated,
            surfaceContainerHighest = ios.cellElevated,
            outline = ios.separator,
            outlineVariant = ios.separator,
            error = ios.red
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = iosTypography) {
        CompositionLocalProvider(
            LocalIos provides ios,
            LocalRippleConfiguration provides null,
            content = content
        )
    }
}
