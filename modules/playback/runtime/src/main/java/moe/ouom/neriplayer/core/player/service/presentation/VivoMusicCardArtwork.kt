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
import moe.ouom.neriplayer.common.graphics.artworkBoxBlurPass
import moe.ouom.neriplayer.common.graphics.scaledArtworkBlurRadius
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance

/** Small, cached bitmaps keep progress-only RemoteViews updates within the Binder budget. */
internal class VivoMusicCardArtwork(context: Context) {
    private companion object {
        const val BACKDROP_WIDTH = 512
        const val BACKDROP_HEIGHT = 288
        const val BLUR_REFERENCE_WIDTH = 64
        const val LOW_BLUR_SAMPLE_WIDTH = 256
        const val STRONG_BLUR_SAMPLE_WIDTH = 128
        const val BLUR_PASSES = 3
    }

    private var source: Bitmap? = null
    private var backdropSource: Bitmap? = null
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

    fun update(
        artwork: Bitmap?,
        appearance: VivoMusicIslandAppearance = VivoMusicIslandAppearance(),
        backgroundArtwork: Bitmap? = artwork,
    ) {
        val next = appearance.normalized()
        val sourceChanged = !initialized || source !== artwork
        val backdropChanged = !initialized || backdropSource !== backgroundArtwork
        if (!sourceChanged && !backdropChanged && this.appearance == next) return
        if (sourceChanged) cover = artwork?.let { rounded(it, it.width * 0.14f) }
        if (backdropChanged || this.appearance.blurRadius != next.blurRadius) {
            blurredSample?.recycle()
            blurredSample = backgroundArtwork?.let { createBlurredSample(it, next.blurRadius) }
        }
        initialized = true
        source = artwork
        backdropSource = backgroundArtwork
        this.appearance = next
        background = backdrop(blurredSample, next)
    }

    private fun createBlurredSample(artwork: Bitmap, radius: Int): Bitmap {
        // Zero blur crops directly at output resolution. Weak blur retains fine detail;
        // stronger blur can use fewer pixels without exposing a coarse sampling grid.
        val width = when (radius) {
            0 -> BACKDROP_WIDTH
            in 1..3 -> LOW_BLUR_SAMPLE_WIDTH
            else -> STRONG_BLUR_SAMPLE_WIDTH
        }
        val height = width * BACKDROP_HEIGHT / BACKDROP_WIDTH
        val sample = croppedSample(artwork, width, height)
        if (radius == 0) return sample
        val scaledRadius = scaledArtworkBlurRadius(radius, width.toFloat() / BLUR_REFERENCE_WIDTH)
        val pixels = IntArray(width * height)
        val scratch = IntArray(pixels.size)
        sample.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(BLUR_PASSES) {
            artworkBoxBlurPass(pixels, width, height, scaledRadius, horizontal = true, output = scratch)
            artworkBoxBlurPass(scratch, width, height, scaledRadius, horizontal = false, output = pixels)
        }
        sample.setPixels(pixels, 0, width, 0, 0, width, height)
        return sample
    }

    private fun backdrop(sample: Bitmap?, appearance: VivoMusicIslandAppearance): Bitmap =
        Bitmap.createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT, Bitmap.Config.ARGB_8888).also { result ->
            result.setHasAlpha(false)
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

    private fun croppedSample(artwork: Bitmap, sampleWidth: Int, sampleHeight: Int): Bitmap =
        Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888).also { sample ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val scale = maxOf(sampleWidth.toFloat() / artwork.width, sampleHeight.toFloat() / artwork.height)
            val width = artwork.width * scale
            val height = artwork.height * scale
            val left = (sampleWidth - width) / 2f
            val top = (sampleHeight - height) / 2f
            Canvas(sample).apply {
                drawColor(Color.rgb(23, 26, 32))
                drawBitmap(artwork, null, RectF(left, top, left + width, top + height), paint)
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
