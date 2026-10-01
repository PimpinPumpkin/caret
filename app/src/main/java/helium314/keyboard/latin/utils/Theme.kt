// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import helium314.keyboard.latin.R

// Fallback palette for Android 7 to 11, where there is no wallpaper-derived scheme.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),
    background = Color(0xFFF8FAFD),
    surface = Color(0xFFF8FAFD),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F6FC),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFC2E7FF),
    background = Color(0xFF131314),
    surface = Color(0xFF131314),
    surfaceBright = Color(0xFF37393B),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
    onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
)

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int, round: Float): FontFamily = FontFamily(
    Font(
        R.font.google_sans_flex,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            // roundness axis: 100 is the soft cut Material 3 Expressive uses for big type
            FontVariation.Setting("ROND", round),
        ),
    ),
)

private val DisplayRegular = flex(400, 100f)
private val DisplayMedium = flex(500, 100f)
private val TextRegular = flex(400, 0f)
private val TextMedium = flex(500, 0f)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.display(medium: Boolean = false) =
        copy(fontFamily = if (medium) DisplayMedium else DisplayRegular, fontWeight = FontWeight(if (medium) 500 else 400))
    fun TextStyle.text(medium: Boolean = false) =
        copy(fontFamily = if (medium) TextMedium else TextRegular, fontWeight = FontWeight(if (medium) 500 else 400))
    return Typography(
        displayLarge = base.displayLarge.display(),
        displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(),
        headlineMedium = base.headlineMedium.display(),
        headlineSmall = base.headlineSmall.display(),
        titleLarge = base.titleLarge.display(),
        titleMedium = base.titleMedium.display(medium = true),
        titleSmall = base.titleSmall.display(medium = true),
        bodyLarge = base.bodyLarge.text(),
        bodyMedium = base.bodyMedium.text(),
        bodySmall = base.bodySmall.text(),
        labelLarge = base.labelLarge.text(medium = true),
        labelMedium = base.labelMedium.text(medium = true),
        labelSmall = base.labelSmall.text(medium = true),
    )
}

/**
 * Material 3 Expressive. The page is one tone darker than a plain surface so that setting rows,
 * drawn as bright cards, stand out from it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialExpressiveTheme(
        colorScheme = scheme.copy(background = scheme.surfaceContainer, surface = scheme.surfaceContainer),
        typography = typography(),
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

const val previewDark = true
