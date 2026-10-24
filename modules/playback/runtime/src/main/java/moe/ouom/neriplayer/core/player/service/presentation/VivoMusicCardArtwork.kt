package moe.ouom.neriplayer.core.player.service.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.drawable.toBitmap
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance

/** Small, cached bitmaps keep progress-only RemoteViews updates within the Binder budget. */
internal class VivoMusicCardArtwork(context: Context) {
    private companion object {
        const val BACKDROP_WIDTH = 320
        const val BACKDROP_HEIGHT = 180
        const val SAMPLE_WIDTH = 64
        const val SAMPLE_HEIGHT = 36
        const val BLUR_PASSES = 3
    }

    private var source: Bitmap? = null
    private var initialized = false
    private var appearance = VivoMusicIslandAppearance()
    private var blurredSample: Bitmap? = null
    var cover: Bitmap? = null
        private set
    var background: Bitmap? = null
        private set
    val appIcon: Bitmap by lazy {
        rounded(context.applicationInfo.loadIcon(context.packageManager).toBitmap(80, 80), 40f)
    }

    fun update(artwork: Bitmap?, appearance: VivoMusicIslandAppearance = VivoMusicIslandAppearance()) {
        val next = appearance.normalized()
        val sourceChanged = !initialized || source !== artwork
        if (!sourceChanged && this.appearance == next) return
        if (sourceChanged) cover = artwork?.let { rounded(it, it.width * 0.14f) }
        if (sourceChanged || this.appearance.blurRadius != next.blurRadius) {
            blurredSample?.recycle()
            blurredSample = artwork?.let { createBlurredSample(it, next.blurRadius) }
        }
        initialized = true
        source = artwork
        this.appearance = next
        background = backdrop(blurredSample, next)
    }

    private fun createBlurredSample(artwork: Bitmap, radius: Int): Bitmap {
        val sample = croppedSample(artwork, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (radius == 0) return sample
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        val scratch = IntArray(pixels.size)
        sample.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        repeat(BLUR_PASSES) {
            blurAxis(pixels, scratch, horizontal = true, radius)
            blurAxis(scratch, pixels, horizontal = false, radius)
        }
        sample.setPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        return sample
    }

    private fun backdrop(sample: Bitmap?, appearance: VivoMusicIslandAppearance): Bitmap =
        Bitmap.createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT, Bitmap.Config.ARGB_8888).also { result ->
            val canvas = Canvas(result)
            canvas.drawColor(Color.rgb(23, 26, 32))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (sample != null) {
                canvas.drawBitmap(sample, null,
                    RectF(0f, 0f, BACKDROP_WIDTH.toFloat(), BACKDROP_HEIGHT.toFloat()), paint)
            }
            paint.shader = LinearGradient(0f, 0f, 0f, BACKDROP_HEIGHT.toFloat(),
                intArrayOf(Color.argb(appearance.topScrimAlpha, 0, 0, 0),
                    Color.argb(appearance.bottomScrimAlpha, 0, 0, 0)),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, BACKDROP_WIDTH.toFloat(), BACKDROP_HEIGHT.toFloat(), paint)
        }

    private fun croppedSample(artwork: Bitmap, paint: Paint): Bitmap =
        Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888).also { sample ->
            val scale = maxOf(SAMPLE_WIDTH.toFloat() / artwork.width, SAMPLE_HEIGHT.toFloat() / artwork.height)
            val width = artwork.width * scale
            val height = artwork.height * scale
            val left = (SAMPLE_WIDTH - width) / 2f
            val top = (SAMPLE_HEIGHT - height) / 2f
            Canvas(sample).apply {
                drawColor(Color.rgb(23, 26, 32))
                drawBitmap(artwork, null, RectF(left, top, left + width, top + height), paint)
            }
        }

    private fun blurAxis(input: IntArray, output: IntArray, horizontal: Boolean, radius: Int) {
        val count = radius * 2 + 1
        for (y in 0 until SAMPLE_HEIGHT) for (x in 0 until SAMPLE_WIDTH) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val sx = if (horizontal) (x + offset).coerceIn(0, SAMPLE_WIDTH - 1) else x
                val sy = if (horizontal) y else (y + offset).coerceIn(0, SAMPLE_HEIGHT - 1)
                val color = input[sy * SAMPLE_WIDTH + sx]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
            }
            output[y * SAMPLE_WIDTH + x] = Color.rgb(red / count, green / count, blue / count)
        }
    }

    private fun rounded(bitmap: Bitmap, radius: Float): Bitmap =
        Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            Canvas(it).drawRoundRect(RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()), radius, radius, paint)
        }
}
