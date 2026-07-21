package lt.sturmanas.bajeristas.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6CA8),
    onPrimary = Color.White,
    secondary = Color(0xFF5C6BC0),
    onSecondary = Color.White,
    background = Color(0xFFF8F9FA),
    surface = Color.White,
    error = Color(0xFFC62828),
)

@Composable
fun SturmanasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
