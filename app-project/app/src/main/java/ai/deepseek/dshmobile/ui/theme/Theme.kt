package ai.deepseek.dshmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DeepBlue = Color(0xFF4D6BFE)
private val DeepBlueDark = Color(0xFF3A54D6)
private val DeepBlueLight = Color(0xFF8AA0FF)

private val DarkScheme = darkColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF243063),
    onPrimaryContainer = DeepBlueLight,
    secondary = Color(0xFF7C8DB5),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1B2537),
    onSurfaceVariant = Color(0xFFA9B4C9),
    outline = Color(0xFF33405A),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = DeepBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF001158),
    secondary = Color(0xFF5A6480),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFECEFF6),
    onSurfaceVariant = Color(0xFF4A5468),
    outline = Color(0xFFC3CAD9),
    error = Color(0xFFB3261E),
)

val MonoFamily = FontFamily.Monospace

val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
