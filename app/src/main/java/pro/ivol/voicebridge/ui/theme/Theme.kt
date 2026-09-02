package pro.ivol.voicebridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF116466),
    onPrimary = Color.White,
    secondary = Color(0xFF536878),
    tertiary = Color(0xFF8A5B13),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6ED3D0),
    onPrimary = Color(0xFF003738),
    secondary = Color(0xFFB8CBD8),
    tertiary = Color(0xFFFFB95C),
    error = Color(0xFFFFB4AB),
)

@Composable
fun VoiceBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
