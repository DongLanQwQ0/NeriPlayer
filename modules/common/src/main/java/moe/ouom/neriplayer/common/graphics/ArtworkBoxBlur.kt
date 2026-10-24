package moe.ouom.neriplayer.common.graphics

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Keep the box kernel's variance when changing the artwork sampling resolution. */
fun scaledArtworkBlurRadius(radius: Int, scale: Float): Int =
    ((sqrt(1f + 4f * radius * (radius + 1) * scale * scale) - 1f) / 2f).roundToInt()

/** A clamped, separable box pass with a sliding sum: work per pixel is independent of radius. */
fun artworkBoxBlurPass(
    source: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
    output: IntArray = IntArray(source.size),
): IntArray {
    require(width > 0 && height > 0 && source.size == width * height)
    require(radius >= 0 && output.size == source.size && output !== source)
    val lineCount = if (horizontal) height else width
    val lineLength = if (horizontal) width else height
    val stride = if (horizontal) 1 else width
    val taps = radius * 2 + 1
    for (line in 0 until lineCount) {
        val base = if (horizontal) line * width else line
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = source[base + offset.coerceIn(0, lineLength - 1) * stride]
            alpha += color ushr 24
            red += color ushr 16 and 255
            green += color ushr 8 and 255
            blue += color and 255
        }
        for (position in 0 until lineLength) {
            output[base + position * stride] = (alpha / taps shl 24) or
                (red / taps shl 16) or (green / taps shl 8) or (blue / taps)
            if (position == lineLength - 1) break
            val leaving = source[base + (position - radius).coerceAtLeast(0) * stride]
            val entering = source[base + (position + radius + 1).coerceAtMost(lineLength - 1) * stride]
            alpha += (entering ushr 24) - (leaving ushr 24)
            red += (entering ushr 16 and 255) - (leaving ushr 16 and 255)
            green += (entering ushr 8 and 255) - (leaving ushr 8 and 255)
            blue += (entering and 255) - (leaving and 255)
        }
    }
    return output
}
