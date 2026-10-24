package moe.ouom.neriplayer.ui.screen.tab.settings.page

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Lucide Image, ISC license and copyright notice: assets/licenses/lucide-image.txt.
internal val VivoIslandGlyph = ImageVector.Builder("LucideImage", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 3f); horizontalLineTo(19f); curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
        verticalLineTo(19f); curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
        horizontalLineTo(5f); curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
        verticalLineTo(5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); close()
        moveTo(10f, 8.5f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
        moveTo(21f, 15f); lineTo(16f, 10f); lineTo(5f, 21f)
    }
}.build()
