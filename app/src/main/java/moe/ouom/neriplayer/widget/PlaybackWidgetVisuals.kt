package moe.ouom.neriplayer.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import moe.ouom.neriplayer.common.graphics.artworkBoxBlurPass
import moe.ouom.neriplayer.common.graphics.scaledArtworkBlurRadius
import kotlin.math.roundToInt

internal data class PlaybackWidgetVisuals(
    val artwork: Bitmap?,
    val compactArtwork: Bitmap?,
    val primaryControlColor: Int,
    val backgroundColor: Int = 0xFFF4F7F4.toInt(),
    val textPrimary: Int = 0xFF1C2521.toInt(),
    val textSecondary: Int = 0xFF48534D.toInt(),
    val controlTint: Int = textPrimary,
    val primaryControlTint: Int = 0xFF142B20.toInt(),
) {
    private data class BackdropKey(val size: PlaybackWidgetSize, val radius: Float, val scale: Float)
    private val backdrops = object : LruCache<BackdropKey, Bitmap>(BACKDROP_CACHE_MAX_BYTES) {
        override fun sizeOf(key: BackdropKey, value: Bitmap) = value.byteCount
    }

    fun compactBackdrop(cover: Bitmap, size: PlaybackWidgetSize, radius: Float, scale: Float): Bitmap {
        val key = BackdropKey(size, radius, scale)
        return backdrops[key] ?: playbackWidgetBackdrop(cover, size, radius,
            applyScrim = true, renderScale = scale).also { backdrops.put(key, it) }
    }
}

internal fun buildPlaybackWidgetVisuals(
    artwork: Bitmap?,
    isDarkTheme: Boolean = false,
): PlaybackWidgetVisuals {
    val squareArtwork = artwork?.takeIf { it.width > 0 && it.height > 0 }?.toSquareBitmap()
    val palette = playbackWidgetPalette(
        seedColor = squareArtwork?.sampleThemeSeed() ?: Color.rgb(106, 163, 134),
        isDarkTheme = isDarkTheme,
    )
    if (squareArtwork == null) {
        return PlaybackWidgetVisuals(
            artwork = null,
            compactArtwork = null,
            primaryControlColor = palette.primaryControlColor,
            backgroundColor = palette.backgroundColor,
            textPrimary = palette.textPrimary,
            textSecondary = palette.textSecondary,
            controlTint = palette.textPrimary,
            primaryControlTint = palette.primaryControlTint,
        )
    }
    val roundedArtwork = squareArtwork.toRoundedArtworkBitmap()
    return PlaybackWidgetVisuals(
        artwork = roundedArtwork,
        compactArtwork = squareArtwork,
        primaryControlColor = palette.primaryControlColor,
        backgroundColor = palette.backgroundColor,
        textPrimary = palette.textPrimary,
        textSecondary = palette.textSecondary,
        controlTint = palette.textPrimary,
        primaryControlTint = palette.primaryControlTint,
    )
}

private data class PlaybackWidgetPalette(
    val backgroundColor: Int,
    val primaryControlColor: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val primaryControlTint: Int,
)

private fun playbackWidgetPalette(
    seedColor: Int,
    isDarkTheme: Boolean,
): PlaybackWidgetPalette {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seedColor, hsl)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    val saturatedSeed = ColorUtils.HSLToColor(hsl)
    val surface = if (isDarkTheme) Color.rgb(25, 30, 27) else Color.rgb(250, 252, 249)
    val textBase = if (isDarkTheme) Color.rgb(244, 249, 245) else Color.rgb(24, 32, 27)
    val secondaryBase = if (isDarkTheme) Color.rgb(214, 226, 218) else Color.rgb(59, 72, 63)
    return PlaybackWidgetPalette(
        backgroundColor = ColorUtils.blendARGB(saturatedSeed, surface, if (isDarkTheme) 0.70f else 0.74f),
        primaryControlColor = ColorUtils.blendARGB(saturatedSeed, Color.WHITE, 0.50f),
        textPrimary = ColorUtils.blendARGB(seedColor, textBase, 0.94f),
        textSecondary = ColorUtils.blendARGB(seedColor, secondaryBase, 0.92f),
        primaryControlTint = Color.rgb(12, 24, 18),
    )
}

private const val ARTWORK_MAX_DIMENSION_PX = 192
private const val SURFACE_MAX_DIMENSION_PX = 1024
private const val BACKDROP_BLUR_SOURCE_MAX_PX = 160
private const val BACKDROP_BLUR_REFERENCE_SIZE_PX = 40
private const val BACKDROP_BLUR_REFERENCE_RADIUS_PX = 3
private const val BACKDROP_CACHE_MAX_BYTES = 8 * 1024 * 1024
private const val MINI_SCRIM_START_COLOR = 0x99000000.toInt()
private const val MINI_SCRIM_END_COLOR = 0xB3000000.toInt()

internal fun playbackWidgetSurface(
    size: PlaybackWidgetSize,
    cornerRadiusDp: Float,
    color: Int,
    renderScale: Float = 1f,
): Bitmap {
    val scale = minOf(renderScale, SURFACE_MAX_DIMENSION_PX.toFloat() / maxOf(size.widthDp, size.heightDp))
    val width = (size.widthDp * scale).roundToInt().coerceAtLeast(1)
    val height = (size.heightDp * scale).roundToInt().coerceAtLeast(1)
    val path = playbackWidgetCornerPath(
        width = width.toFloat(),
        height = height.toFloat(),
        cornerRadius = cornerRadiusDp * scale,
    )
    return createBitmap(width, height).also { output ->
        Canvas(output).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}

internal fun playbackWidgetBackdrop(
    artwork: Bitmap,
    size: PlaybackWidgetSize,
    cornerRadiusDp: Float,
    applyScrim: Boolean = false,
    renderScale: Float = 1f,
): Bitmap {
    // bitmap clipping also works on launchers where XML cannot clip the root view
    val output = playbackWidgetSurface(size, cornerRadiusDp, Color.WHITE, renderScale)
    val width = output.width
    val height = output.height
    val blurSource = playbackWidgetBlurSource(artwork, width, height)
    val target = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // an opaque base keeps the scrim readable even when cover pixels are transparent
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    canvas.drawBitmap(blurSource, null, target, paint)
    blurSource.recycle()
    if (applyScrim) {
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            MINI_SCRIM_START_COLOR, MINI_SCRIM_END_COLOR,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(target, paint)
    }
    return output
}

/**
 * 保留足够采样像素并按分辨率换算模糊半径，避免整块小组件暴露低清插值网格。
 * 逐级缩小抑制混叠，滑动窗口模糊保持线性开销。
 */
internal fun playbackWidgetBlurSource(artwork: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    val cropWidth = minOf(artwork.width, (artwork.height * targetWidth.toFloat() / targetHeight).roundToInt())
        .coerceAtLeast(1)
    val cropHeight = minOf(artwork.height, (artwork.width * targetHeight.toFloat() / targetWidth).roundToInt())
        .coerceAtLeast(1)
    val left = (artwork.width - cropWidth) / 2
    val top = (artwork.height - cropHeight) / 2
    val scale = minOf(1f, BACKDROP_BLUR_SOURCE_MAX_PX.toFloat() / maxOf(cropWidth, cropHeight))
    val blurWidth = (cropWidth * scale).roundToInt().coerceAtLeast(1)
    val blurHeight = (cropHeight * scale).roundToInt().coerceAtLeast(1)
    var current = artwork
    var currentSource = Rect(left, top, left + cropWidth, top + cropHeight)
    // halving first keeps every bilinear step within 2x so cover details average instead of aliasing
    while (maxOf(currentSource.width(), currentSource.height()) / 2 >= maxOf(blurWidth, blurHeight)) {
        val halved = scaledBitmap(
            current,
            currentSource,
            (currentSource.width() / 2).coerceAtLeast(blurWidth),
            (currentSource.height() / 2).coerceAtLeast(blurHeight),
        )
        if (current !== artwork) current.recycle()
        current = halved
        currentSource = Rect(0, 0, halved.width, halved.height)
    }
    val blurSource = scaledBitmap(current, currentSource, blurWidth, blurHeight)
    if (current !== artwork) current.recycle()
    val pixels = IntArray(blurWidth * blurHeight)
    blurSource.getPixels(pixels, 0, blurWidth, 0, 0, blurWidth, blurHeight)
    val radius = scaledArtworkBlurRadius(BACKDROP_BLUR_REFERENCE_RADIUS_PX,
        (maxOf(blurWidth, blurHeight).toFloat() / BACKDROP_BLUR_REFERENCE_SIZE_PX).coerceAtLeast(1f))
    val horizontal = artworkBoxBlurPass(pixels, blurWidth, blurHeight, radius, horizontal = true)
    val blurred = artworkBoxBlurPass(horizontal, blurWidth, blurHeight, radius, horizontal = false)
    blurSource.setPixels(blurred, 0, blurWidth, 0, 0, blurWidth, blurHeight)
    return blurSource
}

private fun scaledBitmap(source: Bitmap, sourceRect: Rect, width: Int, height: Int): Bitmap {
    return createBitmap(width, height).also { output ->
        Canvas(output).drawBitmap(
            source,
            sourceRect,
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }
}

private fun Bitmap.toSquareBitmap(): Bitmap {
    val sourceSize = minOf(width, height)
    val left = (width - sourceSize) / 2
    val top = (height - sourceSize) / 2
    val targetSize = sourceSize.coerceAtMost(ARTWORK_MAX_DIMENSION_PX)
    return createBitmap(targetSize, targetSize).also { output ->
        Canvas(output).drawBitmap(
            this,
            Rect(left, top, left + sourceSize, top + sourceSize),
            Rect(0, 0, targetSize, targetSize),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }
}

private fun Bitmap.toRoundedArtworkBitmap(): Bitmap {
    val output = createBitmap(width, height)
    val canvas = Canvas(output)
    val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawPath(
        playbackWidgetCornerPath(width.toFloat(), height.toFloat(), width * 0.15f),
        maskPaint,
    )
    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, null, rect, maskPaint)
    maskPaint.xfermode = null
    return output
}

private fun playbackWidgetCornerPath(
    width: Float,
    height: Float,
    cornerRadius: Float,
): Path {
    val radius = cornerRadius.coerceIn(0f, minOf(width, height) / 2f)
    return Path().apply {
        addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW)
    }
}

private fun Bitmap.sampleThemeSeed(): Int {
    val stepX = (width / 48).coerceAtLeast(1)
    val stepY = (height / 48).coerceAtLeast(1)
    var red = 0f
    var green = 0f
    var blue = 0f
    var weightTotal = 0f
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val color = this[x, y]
            val alpha = color ushr 24 and 0xFF
            if (alpha > 24) {
                val sampleRed = color ushr 16 and 0xFF
                val sampleGreen = color ushr 8 and 0xFF
                val sampleBlue = color and 0xFF
                val maximum = maxOf(sampleRed, sampleGreen, sampleBlue)
                val minimum = minOf(sampleRed, sampleGreen, sampleBlue)
                val saturation = if (maximum == 0) 0f else {
                    (maximum - minimum).toFloat() / maximum
                }
                val brightness = maximum / 255f
                val weight = 0.25f + saturation * 1.4f +
                    if (brightness in 0.12f..0.95f) 0.25f else 0f
                red += sampleRed * weight
                green += sampleGreen * weight
                blue += sampleBlue * weight
                weightTotal += weight
            }
            x += stepX
        }
        y += stepY
    }
    if (weightTotal <= 0f) {
        return Color.rgb(112, 112, 112)
    }
    return Color.rgb(
        (red / weightTotal).toInt().coerceIn(0, 255),
        (green / weightTotal).toInt().coerceIn(0, 255),
        (blue / weightTotal).toInt().coerceIn(0, 255),
    )
}
