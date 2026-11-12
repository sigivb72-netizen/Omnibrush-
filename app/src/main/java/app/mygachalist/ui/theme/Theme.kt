package app.mygachalist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.mygachalist.data.GameStatus
import app.mygachalist.data.ListStatus
import app.mygachalist.data.ThemeMode

val BrandBlue = Color(0xFF2E51A2)

private val Light = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E7F5),
    onPrimaryContainer = Color(0xFF1C2F66),
    secondary = Color(0xFF4F74C8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E7F5),
    onSecondaryContainer = Color(0xFF1C2F66),
    background = Color.White,
    onBackground = Color(0xFF1B1D22),
    surface = Color.White,
    onSurface = Color(0xFF1B1D22),
    surfaceVariant = Color(0xFFEDF1F9),
    onSurfaceVariant = Color(0xFF4A5568),
    outline = Color(0xFFBEC8DC),
    outlineVariant = Color(0xFFE2E6EE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFF4F6FB),
    surfaceContainerHigh = Color(0xFFEDF1F9),
    surfaceContainerHighest = Color(0xFFE1E7F5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FA8E8),
    onPrimary = Color(0xFF0E1E4A),
    primaryContainer = Color(0xFF2A3F78),
    onPrimaryContainer = Color(0xFFE1E7F5),
    secondary = Color(0xFF8FA8E8),
    onSecondary = Color(0xFF0E1E4A),
    secondaryContainer = Color(0xFF2A3F78),
    onSecondaryContainer = Color(0xFFE1E7F5),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E5EA),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E5EA),
    surfaceVariant = Color(0xFF23272F),
    onSurfaceVariant = Color(0xFFB4BCCB),
    outline = Color(0xFF4A5261),
    outlineVariant = Color(0xFF2B303A),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF15181E),
    surfaceContainer = Color(0xFF1A1D24),
    surfaceContainerHigh = Color(0xFF23272F),
    surfaceContainerHighest = Color(0xFF2B303A),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(mode)) Dark else Light, typography = Typography(), content = content)
}

fun ListStatus.color(): Color = when (this) {
    ListStatus.PLAYING -> Color(0xFF2DB039)
    ListStatus.COMPLETED -> Color(0xFF26448F)
    ListStatus.ON_HOLD -> Color(0xFFF1C83E)
    ListStatus.DROPPED -> Color(0xFFA12F31)
    ListStatus.PLAN_TO_PLAY -> Color(0xFFC3C3C3)
}

fun GameStatus.color(): Color = when (this) {
    GameStatus.ANNOUNCED -> Color(0xFF7E57C2)
    GameStatus.PRE_REGISTRATION -> Color(0xFFEF6C00)
    GameStatus.BETA -> Color(0xFF00897B)
    GameStatus.RELEASED -> Color(0xFF2E7D32)
    GameStatus.END_OF_SERVICE -> Color(0xFF8D2B2B)
}
