package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.ouom.neriplayer.common.graphics.artworkBoxBlurPass

/** Small glyph-only layers; progress updates neither rasterize text nor blur the album again. */
internal class VivoMusicLyricTransition(private val density: Float) {
    companion object {
        const val DURATION_MS = 300L
        const val SCALE_DELTA = 0.06f
        const val TRAVEL_DP = 3f
        const val BLUR_LEVELS = 3
    }
    data class Glyph(val levels: List<Bitmap>)
    data class Frame(val from: Glyph?, val to: Glyph, val progress: Float) {
        val bitmaps get() = from?.levels.orEmpty() + to.levels
    }
    private var identity: Pair<String, String>? = null
    private var from: Glyph? = null
    private var to: Glyph? = null
    private var startedAt = 0L

    fun update(songKey: String, text: String, isLyric: Boolean, now: Long,
        color: Int, render: () -> Bitmap): Frame {
        val next = songKey to text
        if (identity != next) {
            val old = frame(now)
            from = if (identity?.first == songKey && isLyric && old != null) {
                if (old.from == null) old.to else glyph(snapshot(old), color)
            } else null
            to = glyph(render(), color)
            identity = next
            startedAt = now
        }
        return checkNotNull(frame(now))
    }

    fun frame(now: Long): Frame? {
        val current = to ?: return null
        val progress = ((now - startedAt).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        if (progress >= 1f) from = null
        return Frame(from, current, if (from == null) 1f else progress)
    }

    private fun glyph(sharp: Bitmap, color: Int): Glyph = Glyph(buildList {
        add(sharp)
        for (level in 1 until BLUR_LEVELS) {
            val pixels = IntArray(sharp.width * sharp.height)
            sharp.getPixels(pixels, 0, sharp.width, 0, 0, sharp.width, sharp.height)
            // Text has one color. Blur coverage, preserving its unpremultiplied RGB at the edge.
            for (i in pixels.indices) pixels[i] = (pixels[i] and -0x1000000) or (color and 0xffffff)
            val scratch = IntArray(pixels.size)
            val radius = (density * level).roundToInt().coerceAtLeast(1)
            repeat(3) {
                artworkBoxBlurPass(pixels, sharp.width, sharp.height, radius, true, scratch)
                artworkBoxBlurPass(scratch, sharp.width, sharp.height, radius, false, pixels)
            }
            add(Bitmap.createBitmap(pixels, sharp.width, sharp.height, Bitmap.Config.ARGB_8888))
        }
    })

    /** Retarget from the current composite instead of flashing an obsolete line back to full size. */
    private fun snapshot(frame: Frame): Bitmap {
        val bitmap = frame.to.levels.first()
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { result ->
            val canvas = Canvas(result)
            val t = frame.progress * frame.progress * (3f - 2f * frame.progress)
            fun draw(glyph: Glyph, entering: Boolean) {
                val visibility = if (entering) t else 1f - t
                val phase = 1f - visibility
                val scale = 1f - SCALE_DELTA * phase
                val offset = TRAVEL_DP * density * if (entering) phase else -phase
                val top = (bitmap.height - bitmap.height * scale) / 2f + offset
                for ((level, layer) in glyph.levels.withIndex()) {
                    val weight = (1f - abs(phase * (BLUR_LEVELS - 1) - level)).coerceAtLeast(0f)
                    if (weight == 0f || visibility == 0f) continue
                    canvas.drawBitmap(layer, null, RectF(0f, top, bitmap.width * scale, top + bitmap.height * scale),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            alpha = (visibility * weight * 255).roundToInt()
                        })
                }
            }
            frame.from?.let { draw(it, false) }
            draw(frame.to, true)
        }
    }
}
