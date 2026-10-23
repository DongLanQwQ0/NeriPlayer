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

/** Small, cached bitmaps keep progress-only RemoteViews updates within the Binder budget. */
internal class VivoMusicCardArtwork(context: Context) {
    private companion object {
        const val BACKDROP_WIDTH = 320
        const val BACKDROP_HEIGHT = 180
        const val SAMPLE_WIDTH = 64
        const val SAMPLE_HEIGHT = 36
        const val BLUR_RADIUS = 2
        const val BLUR_PASSES = 3
        const val TOP_SCRIM_ALPHA = 0xB8
        const val BOTTOM_SCRIM_ALPHA = 0xD6
    }

    private var source: Bitmap? = null
    private var initialized = false
    var cover: Bitmap? = null
        private set
    var background: Bitmap? = null
        private set
    val appIcon: Bitmap by lazy {
        rounded(context.applicationInfo.loadIcon(context.packageManager).toBitmap(80, 80), 40f)
    }

    fun update(artwork: Bitmap?) {
        if (initialized && source === artwork) return
        initialized = true
        source = artwork
        cover = artwork?.let { rounded(it, it.width * 0.14f) }
        background = backdrop(artwork)
    }

    private fun backdrop(artwork: Bitmap?): Bitmap =
        Bitmap.createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT, Bitmap.Config.ARGB_8888).also { result ->
            val canvas = Canvas(result)
            canvas.drawColor(Color.rgb(23, 26, 32))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (artwork != null) {
                val sample = croppedSample(artwork, paint)
                val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
                val scratch = IntArray(pixels.size)
                sample.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                // Three separable box passes approximate a Gaussian at a bounded, small size.
                repeat(BLUR_PASSES) {
                    blurAxis(pixels, scratch, horizontal = true)
                    blurAxis(scratch, pixels, horizontal = false)
                }
                sample.setPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                canvas.drawBitmap(sample, null,
                    RectF(0f, 0f, BACKDROP_WIDTH.toFloat(), BACKDROP_HEIGHT.toFloat()), paint)
                sample.recycle()
            }
            paint.shader = LinearGradient(0f, 0f, 0f, BACKDROP_HEIGHT.toFloat(),
                intArrayOf(Color.argb(TOP_SCRIM_ALPHA, 0, 0, 0),
                    Color.argb(BOTTOM_SCRIM_ALPHA, 0, 0, 0)),
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

    private fun blurAxis(input: IntArray, output: IntArray, horizontal: Boolean) {
        val count = BLUR_RADIUS * 2 + 1
        for (y in 0 until SAMPLE_HEIGHT) for (x in 0 until SAMPLE_WIDTH) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -BLUR_RADIUS..BLUR_RADIUS) {
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
