package moe.ouom.neriplayer.core.player.service.presentation

import kotlin.math.abs

/** The application and the remote host share the same seek curve across notification updates. */
internal class VivoMusicProgressMotion {
    companion object { const val SEEK_DURATION_MS = 400L }

    data class Frame(val from: Float, val target: Float, val remainingMs: Long, val playing: Boolean)

    private var identity: Pair<String, Long>? = null
    private var from = 0.0
    private var target = 0.0
    private var startedAt = 0L
    private var transitionMs = 0L
    private var playing = false

    private fun value(now: Long): Double {
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        val t = if (transitionMs == 0L) 1.0 else (elapsed.toDouble() / transitionMs).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        return from + (target - from) * eased + if (playing) elapsed else 0L
    }

    fun update(songKey: String, durationMs: Long, positionMs: Long, isPlaying: Boolean, now: Long, seek: Boolean = false) {
        val nextIdentity = songKey to durationMs
        val position = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toDouble()
        if (identity != nextIdentity) {
            identity = nextIdentity
            from = position
            target = position
            startedAt = now
            transitionMs = 0L
        } else {
            val visible = value(now)
            val expected = target + if (playing) (now - startedAt).coerceAtLeast(0L) else 0L
            // An acknowledgement is not another seek. Keep the unfinished curve intact.
            if (seek || abs(position - expected) > 1_000.0 || isPlaying != playing) {
                val remaining = (transitionMs - (now - startedAt).coerceAtLeast(0L)).coerceAtLeast(0L)
                from = visible
                target = position
                startedAt = now
                transitionMs = if (seek || abs(position - expected) > 1_000.0) SEEK_DURATION_MS else remaining
                if (transitionMs == 0L) from = target
            }
        }
        playing = isPlaying
    }

    fun frame(now: Long): Frame {
        val duration = identity?.second?.takeIf { it > 0L } ?: return Frame(0f, 0f, 0L, false)
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        val targetNow = target + if (playing) elapsed else 0L
        return Frame((value(now) / duration).toFloat().coerceIn(0f, 1f),
            (targetNow / duration).toFloat().coerceIn(0f, 1f),
            (transitionMs - elapsed).coerceAtLeast(0L), playing)
    }

    fun clear() { identity = null }
}
