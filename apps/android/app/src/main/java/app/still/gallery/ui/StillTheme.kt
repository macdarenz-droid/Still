package app.still.gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
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

internal val DriftInk = Color(0xFF111315)
internal val DriftIvory = Color(0xFFF3EFE5)
internal val DriftCoral = Color(0xFFEF885F)

private val DriftColors = darkColorScheme(
    primary = DriftCoral,
    onPrimary = DriftInk,
    primaryContainer = Color(0xFF493027),
    onPrimaryContainer = DriftIvory,
    secondary = DriftCoral,
    onSecondary = DriftInk,
    secondaryContainer = Color(0xFF373A35),
    onSecondaryContainer = DriftIvory,
    background = DriftInk,
    onBackground = DriftIvory,
    surface = DriftInk,
    onSurface = DriftIvory,
    surfaceVariant = Color(0xFF272D2A),
    onSurfaceVariant = Color(0xFFB9BDB5),
    outline = Color(0xFF727970),
    surfaceContainer = Color(0xFF1A1E1C),
    surfaceContainerHigh = Color(0xFF272D2A),
)
private val DriftTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 46.sp, lineHeight = 49.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 34.sp),
)

@Composable
fun StillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DriftColors, typography = DriftTypography, content = content)
}

enum class Mark { PHOTOS, ALBUMS, HEART, CLEANUP, SETTINGS, BACK, CLOSE, SHARE, SELECT, INFO, PLUS, MINUS, REFRESH, NEXT, CALENDAR, GRID, RIBBON }

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
            Mark.CLEANUP -> { rounded(3f, 6f, 12f, 15f); rounded(9f, 3f, 12f, 15f); line(12f, 11f, 15f, 14f); line(15f, 14f, 19f, 8f) }
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
            Mark.NEXT -> { line(4f, 12f, 20f, 12f); line(20f, 12f, 13f, 5f); line(20f, 12f, 13f, 19f) }
            Mark.CALENDAR -> { rounded(3f, 5f, 18f, 16f); line(3f, 10f, 21f, 10f); line(8f, 2f, 8f, 7f); line(16f, 2f, 16f, 7f) }
            Mark.GRID -> { rounded(3f, 3f, 7f, 7f); rounded(14f, 3f, 7f, 7f); rounded(3f, 14f, 7f, 7f); rounded(14f, 14f, 7f, 7f) }
            Mark.RIBBON -> { rounded(6f, 3f, 12f, 18f); line(2f, 7f, 2f, 18f); line(22f, 6f, 22f, 17f) }
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
