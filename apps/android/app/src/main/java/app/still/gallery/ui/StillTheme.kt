package app.still.gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5871),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7EC),
    onPrimaryContainer = Color(0xFF233B50),
    background = Color(0xFFF7F6F2),
    onBackground = Color(0xFF272925),
    surface = Color(0xFFF7F6F2),
    onSurface = Color(0xFF272925),
    surfaceVariant = Color(0xFFEBEAE4),
    onSurfaceVariant = Color(0xFF62665F),
    outline = Color(0xFF777B73),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3CADB),
    primaryContainer = Color(0xFF334B60),
    onPrimaryContainer = Color(0xFFDDE8F0),
    background = Color(0xFF1D211F),
    surface = Color(0xFF1D211F),
    onSurface = Color(0xFFE7E9E2),
    onBackground = Color(0xFFE7E9E2),
    surfaceVariant = Color(0xFF343A35),
    onSurfaceVariant = Color(0xFFBDC6BB),
)

@Composable
fun StillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

enum class Mark { PHOTOS, ALBUMS, HEART, SETTINGS, BACK, CLOSE, SHARE, SELECT, INFO, PLUS, MINUS, REFRESH }

@Composable
fun MarkIcon(mark: Mark, description: String?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(24.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(1.7f * unit, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), stroke.width, StrokeCap.Round)
        fun rounded(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, point(x, y), Size(w * unit, h * unit), CornerRadius(2 * unit), style = stroke)
        when (mark) {
            Mark.PHOTOS -> {
                rounded(4f, 4f, 16f, 16f)
                drawCircle(color, 1.6f * unit, point(9f, 9f))
                line(5f, 17f, 11f, 12f); line(11f, 12f, 15f, 16f); line(15f, 16f, 18f, 13f)
            }
            Mark.ALBUMS -> { rounded(7f, 7f, 14f, 14f); line(3f, 16f, 3f, 3f); line(3f, 3f, 16f, 3f) }
            Mark.HEART -> {
                val path = Path().apply {
                    moveTo(12 * unit, 21 * unit)
                    cubicTo(-5 * unit, 9 * unit, 5 * unit, -1 * unit, 12 * unit, 7 * unit)
                    cubicTo(19 * unit, -1 * unit, 29 * unit, 9 * unit, 12 * unit, 21 * unit)
                }
                drawPath(path, color, style = stroke)
            }
            Mark.SETTINGS -> {
                drawCircle(color, 8 * unit, point(12f, 12f), style = stroke)
                drawCircle(color, 2.6f * unit, point(12f, 12f), style = stroke)
                line(12f, 1f, 12f, 4f); line(12f, 20f, 12f, 23f)
                line(1f, 12f, 4f, 12f); line(20f, 12f, 23f, 12f)
            }
            Mark.BACK -> { line(20f, 12f, 4f, 12f); line(4f, 12f, 11f, 5f); line(4f, 12f, 11f, 19f) }
            Mark.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Mark.SHARE -> {
                line(8f, 9f, 16f, 5f); line(8f, 15f, 16f, 19f)
                listOf(point(5f, 12f), point(19f, 4f), point(19f, 20f)).forEach { drawCircle(color, 2.5f * unit, it, style = stroke) }
            }
            Mark.SELECT -> { drawCircle(color, 8.5f * unit, point(12f, 12f), style = stroke); line(8f, 12f, 11f, 15f); line(11f, 15f, 16f, 9f) }
            Mark.INFO -> {
                drawCircle(color, 9 * unit, point(12f, 12f), style = stroke)
                line(12f, 11f, 12f, 17f); drawCircle(color, unit, point(12f, 7f))
            }
            Mark.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            Mark.MINUS -> line(5f, 12f, 19f, 12f)
            Mark.REFRESH -> {
                drawArc(color, 40f, 290f, false, point(4f, 4f), Size(16 * unit, 16 * unit), style = stroke)
                line(20f, 4f, 20f, 10f); line(20f, 10f, 14f, 10f)
            }
        }
    }
}
