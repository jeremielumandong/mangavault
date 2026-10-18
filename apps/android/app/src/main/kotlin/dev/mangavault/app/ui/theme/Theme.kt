package dev.mangavault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mangavault.app.R

/** The prototype's THEMES tokens. */
@Immutable
data class MvColors(
    val bg: Color,
    val sf: Color,
    val sr: Color,
    val tp: Color,
    val ts: Color,
    val ac: Color,
    val ok: Color,
    val wn: Color,
    val ln: Color,
    val sk: Color,
    val sk2: Color,
    val tb: Color,
    val acs: Color,
    val isLight: Boolean,
)

val DarkTokens = MvColors(
    bg = Color(0xFF0E0E11), sf = Color(0xFF17171C), sr = Color(0xFF202027), tp = Color(0xFFF2F2F5),
    ts = Color(0xFFA0A0AB), ac = Color(0xFFFF5A5F), ok = Color(0xFF3DD68C), wn = Color(0xFFF5B841),
    ln = Color(0x14FFFFFF), sk = Color(0xFF1C1C22), sk2 = Color(0xFF2A2A32), tb = Color(0xD1141419),
    acs = Color(0x24FF5A5F), isLight = false,
)

val LightTokens = MvColors(
    bg = Color(0xFFFAFAFB), sf = Color(0xFFFFFFFF), sr = Color(0xFFF1F1F4), tp = Color(0xFF121216),
    ts = Color(0xFF5C5C66), ac = Color(0xFFE8484D), ok = Color(0xFF1FA463), wn = Color(0xFFC98A0B),
    ln = Color(0x17000000), sk = Color(0xFFECECF0), sk2 = Color(0xFFF7F7F9), tb = Color(0xDBFAFAFB),
    acs = Color(0x1FE8484D), isLight = true,
)

val OledTokens = MvColors(
    bg = Color(0xFF000000), sf = Color(0xFF0D0D10), sr = Color(0xFF18181D), tp = Color(0xFFF2F2F5),
    ts = Color(0xFFA0A0AB), ac = Color(0xFFFF5A5F), ok = Color(0xFF3DD68C), wn = Color(0xFFF5B841),
    ln = Color(0x17FFFFFF), sk = Color(0xFF121216), sk2 = Color(0xFF1E1E24), tb = Color(0xD9000000),
    acs = Color(0x29FF5A5F), isLight = false,
)

val ZenKaku = FontFamily(
    Font(R.font.zen_kaku_gothic_new_regular, FontWeight.Normal),
    Font(R.font.zen_kaku_gothic_new_medium, FontWeight.Medium),
    Font(R.font.zen_kaku_gothic_new_bold, FontWeight.Bold),
    Font(R.font.zen_kaku_gothic_new_black, FontWeight.Black),
)

val Dela = FontFamily(Font(R.font.dela_gothic_one, FontWeight.Normal))

/** Semibold in the prototype (600) resolves to the bundled Bold. */
val SemiBold = FontWeight.SemiBold

private val LocalMv = staticCompositionLocalOf { DarkTokens }

object Mv {
    val colors: MvColors
        @Composable @ReadOnlyComposable get() = LocalMv.current

    /** Screen titles: Dela Gothic One 28/35 on Android. */
    val h1 = TextStyle(fontFamily = Dela, fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Normal)
    val logo = TextStyle(fontFamily = Dela, fontSize = 24.sp, lineHeight = 32.sp)
    val section = TextStyle(fontFamily = ZenKaku, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = SemiBold)
    val body = TextStyle(fontFamily = ZenKaku, fontSize = 15.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = ZenKaku, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = ZenKaku, fontSize = 12.sp, lineHeight = 16.sp)
}

fun tokensFor(theme: String, systemDark: Boolean = true): MvColors = when (theme) {
    "light" -> LightTokens
    "oled" -> OledTokens
    "system" -> if (systemDark) DarkTokens else LightTokens
    else -> DarkTokens
}

@Composable
fun MvTheme(theme: String, content: @Composable () -> Unit) {
    val t = tokensFor(theme, isSystemInDarkTheme())
    val scheme = if (t.isLight) {
        lightColorScheme(
            primary = t.ac, onPrimary = Color.White, background = t.bg, onBackground = t.tp,
            surface = t.sf, onSurface = t.tp, surfaceVariant = t.sr, onSurfaceVariant = t.ts,
            surfaceContainer = t.sf, surfaceContainerHigh = t.sf, surfaceContainerLow = t.sf,
            outline = t.ln, outlineVariant = t.ln, secondaryContainer = t.acs, onSecondaryContainer = t.tp,
        )
    } else {
        darkColorScheme(
            primary = t.ac, onPrimary = Color.White, background = t.bg, onBackground = t.tp,
            surface = t.sf, onSurface = t.tp, surfaceVariant = t.sr, onSurfaceVariant = t.ts,
            surfaceContainer = t.sf, surfaceContainerHigh = t.sf, surfaceContainerLow = t.sf,
            outline = t.ln, outlineVariant = t.ln, secondaryContainer = t.acs, onSecondaryContainer = t.tp,
        )
    }
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Dela),
        displayMedium = base.displayMedium.copy(fontFamily = Dela),
        displaySmall = base.displaySmall.copy(fontFamily = Dela),
        headlineLarge = base.headlineLarge.copy(fontFamily = ZenKaku),
        headlineMedium = base.headlineMedium.copy(fontFamily = ZenKaku),
        headlineSmall = base.headlineSmall.copy(fontFamily = ZenKaku),
        titleLarge = base.titleLarge.copy(fontFamily = ZenKaku),
        titleMedium = base.titleMedium.copy(fontFamily = ZenKaku),
        titleSmall = base.titleSmall.copy(fontFamily = ZenKaku),
        bodyLarge = Mv.body,
        bodyMedium = Mv.body,
        bodySmall = Mv.small,
        labelLarge = base.labelLarge.copy(fontFamily = ZenKaku),
        labelMedium = base.labelMedium.copy(fontFamily = ZenKaku),
        labelSmall = base.labelSmall.copy(fontFamily = ZenKaku),
    )
    CompositionLocalProvider(LocalMv provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
