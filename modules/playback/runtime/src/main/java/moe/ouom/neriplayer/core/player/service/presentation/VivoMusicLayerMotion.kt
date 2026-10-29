package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Bounded two-layer motion; interrupted updates start from the visible composite. */
internal class VivoMusicLayerMotion(val durationMs: Long, val scaleDelta: Float, val travel: Float) {
    data class Frame(val from: Bitmap?, val to: Bitmap, val progress: Float) {
        val bitmaps get() = listOfNotNull(from, to)
    }
    private var key: Any? = null
    private var from: Bitmap? = null
    private var to: Bitmap? = null
    private var startedAt = 0L

    fun update(key: Any, now: Long, animate: Boolean = true, render: () -> Bitmap): Frame {
        if (this.key != key) {
            from = if (animate) frame(now)?.let(::snapshot) else null
            to = render()
            this.key = key
            startedAt = now
        }
        return checkNotNull(frame(now))
    }

    private fun frame(now: Long): Frame? {
        val target = to ?: return null
        val progress = ((now - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
        if (progress >= 1f) from = null
        return Frame(from, target, if (from == null) 1f else progress)
    }

    private fun snapshot(frame: Frame): Bitmap {
        if (frame.from == null) return frame.to
        val output = Bitmap.createBitmap(frame.to.width, frame.to.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val t = frame.progress * frame.progress * (3f - 2f * frame.progress)
        fun draw(bitmap: Bitmap, incoming: Boolean) {
            val visibility = if (incoming) t else 1f - t
            val scale = 1f - scaleDelta * (1f - visibility)
            val w = output.width * scale
            val h = output.height * scale
            val y = (output.height - h) / 2f + travel * (1f - visibility) * if (incoming) 1 else -1
            val left = if (travel == 0f) (output.width - w) / 2f else 0f
            canvas.drawBitmap(bitmap, null, RectF(left, y, left + w, y + h),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (visibility * 255).toInt() })
        }
        draw(frame.from, false); draw(frame.to, true)
        return output
    }
}

internal class VivoMusicToggleMotion {
    companion object { const val DURATION_MS = 140L }
    data class Frame(val from: Float, val to: Float, val progress: Float)
    private var from = 0f
    private var target: Float? = null
    private var startedAt = 0L
    fun update(checked: Boolean, now: Long): Frame {
        val next = if (checked) 1f else 0f
        val old = target
        if (old == null) { target = next; from = next }
        else if (old != next) {
            val t = ((now - startedAt).toFloat() / DURATION_MS).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            from += (old - from) * eased
            target = next
            startedAt = now
        }
        return Frame(from, next, if (from == next) 1f else
            ((now - startedAt).toFloat() / DURATION_MS).coerceIn(0f, 1f))
    }
}
