package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import kotlin.math.roundToInt
import moe.ouom.neriplayer.common.graphics.artworkBoxBlurPass

/** Only current/upcoming glyphs are cached. The transition finishes at the media timestamp. */
internal class VivoMusicLyricTransition(private val density: Float) {
    companion object {
        const val DURATION_MS = 180L
        const val SCALE_DELTA = 0.06f
        const val TRAVEL_DP = 3f
        const val BLUR_LEVELS = 3
    }
    data class Glyph(val levels: List<Bitmap>)
    data class Frame(val from: Glyph?, val to: Glyph, val boundaryMs: Float, val durationMs: Float) {
        val bitmaps get() = from?.levels.orEmpty() + to.levels
        fun progress(elapsedMs: Float): Float = if (from == null) 1f else
            ((elapsedMs - boundaryMs + durationMs) / durationMs).coerceIn(0f, 1f)
    }
    private var song: String? = null
    private val glyphs = mutableMapOf<String, Glyph>()

    fun prepare(songKey: String, current: String, next: String?, boundaryMs: Float,
        durationMs: Float, color: Int, render: (String) -> Bitmap): Frame {
        if (song != songKey) { glyphs.clear(); song = songKey }
        val upcoming = next?.takeUnless { it == current || durationMs <= 0f }
        glyphs.keys.retainAll(listOfNotNull(current, upcoming).toSet())
        fun glyph(text: String) = glyphs.getOrPut(text) { glyph(render(text), color) }
        val from = glyph(current)
        return if (upcoming == null) Frame(null, from, 0f, 1f) else
            Frame(from, glyph(upcoming), boundaryMs, durationMs)
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

}
