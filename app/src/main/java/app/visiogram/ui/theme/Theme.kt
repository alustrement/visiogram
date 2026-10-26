package app.visiogram.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Brand = Color(0xFF2AABEE)
val Danger = Color(0xFFE5484D)
val Success = Color(0xFF30A46C)
val Background = Color(0xFF10141A)
val SurfaceColor = Color(0xFF1B2129)

private val colors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Brand,
    background = Background,
    onBackground = Color(0xFFE8EDF2),
    surface = SurfaceColor,
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF262E38),
    onSurfaceVariant = Color(0xFFAAB4BF),
    error = Danger,
)

@Composable
fun VisiogramTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
