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
        background = backdrop(seedColor(artwork))
    }

    private fun seedColor(bitmap: Bitmap?): Int {
        if (bitmap == null) return Color.rgb(0, 112, 140)
        var red = 0L
        var green = 0L
        var blue = 0L
        // A fixed sampling grid avoids processing the full cover on the playback thread.
        for (y in 0 until 8) for (x in 0 until 8) {
            val color = bitmap.getPixel(x * bitmap.width / 8, y * bitmap.height / 8)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        val hsv = FloatArray(3)
        Color.RGBToHSV((red / 64).toInt(), (green / 64).toInt(), (blue / 64).toInt(), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = 0.48f
        return Color.HSVToColor(hsv)
    }

    private fun backdrop(color: Int): Bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 65f, 0f, 180f,
                intArrayOf(Color.BLACK, color), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 320f, 180f, paint)
    }

    private fun rounded(bitmap: Bitmap, radius: Float): Bitmap =
        Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            Canvas(it).drawRoundRect(RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()), radius, radius, paint)
        }
}
