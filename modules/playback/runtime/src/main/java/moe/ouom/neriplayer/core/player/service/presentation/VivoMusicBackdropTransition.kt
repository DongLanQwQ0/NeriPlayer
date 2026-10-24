package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/** Retains the visible blend when artwork changes again before its fade has finished. */
internal class VivoMusicBackdropTransition {
    companion object { const val DURATION_MS = 300L }
    data class Frame(val from: Bitmap, val to: Bitmap, val alpha: Float)

    private var from: Bitmap? = null
    private var to: Bitmap? = null
    private var startedAt = 0L

    fun update(bitmap: Bitmap, now: Long): Frame {
        if (to !== bitmap) {
            from = frame(now)?.let { frame ->
                if (frame.alpha >= 1f) frame.to else {
                    Bitmap.createBitmap(frame.to.width, frame.to.height, Bitmap.Config.ARGB_8888).also { blend ->
                        blend.setHasAlpha(false)
                        Canvas(blend).apply {
                            drawBitmap(frame.from, 0f, 0f, null)
                            drawBitmap(frame.to, 0f, 0f, Paint().apply { alpha = (frame.alpha * 255).toInt() })
                        }
                    }
                }
            }
            to = bitmap
            startedAt = now
        }
        return checkNotNull(frame(now))
    }

    fun frame(now: Long): Frame? {
        val current = to ?: return null
        val alpha = ((now - startedAt).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        if (alpha >= 1f) from = null
        return Frame(from ?: current, current, if (from == null) 1f else alpha)
    }

    fun clear() { from = null; to = null }
}
