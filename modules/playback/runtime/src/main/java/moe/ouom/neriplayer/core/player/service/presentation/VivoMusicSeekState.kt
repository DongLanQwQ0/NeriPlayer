package moe.ouom.neriplayer.core.player.service.presentation

import kotlin.math.abs

/** The latest seek request owns the optimistic position until its callback fence expires. */
internal class VivoMusicSeekState {
    companion object { const val CALLBACK_FENCE_MS = 3_000L }
    private data class Request(
        val songKey: String,
        val durationMs: Long,
        val issuedAt: Long,
        val expiresAt: Long,
        val positionMs: Long,
        val anchorAt: Long,
        val playing: Boolean,
        val acknowledged: Boolean = false,
    ) {
        fun position(now: Long): Long = (positionMs + if (playing) (now - anchorAt).coerceAtLeast(0) else 0)
            .coerceIn(0, durationMs.coerceAtLeast(0))
    }
    private var request: Request? = null
    val pending: Boolean get() = request != null

    fun begin(songKey: String, durationMs: Long, targetMs: Long, playing: Boolean, now: Long) {
        // Replacing this immutable request cancels all ownership held by its predecessor.
        request = Request(songKey, durationMs, now, now + CALLBACK_FENCE_MS,
            targetMs.coerceIn(0, durationMs.coerceAtLeast(0)), now, playing)
    }

    fun resolve(songKey: String?, durationMs: Long, actualMs: Long, playing: Boolean, now: Long): Long {
        val current = request ?: return actualMs
        if (current.songKey != songKey || current.durationMs != durationMs || now >= current.expiresAt) {
            request = null
            return actualMs
        }
        val expected = current.position(now)
        request = current.copy(positionMs = expected, anchorAt = now, playing = playing,
            acknowledged = current.acknowledged || abs(actualMs - expected) < 1_000L)
        // A matching acknowledgement does not retire this fence: an older seek may still reply.
        return expected
    }

    fun predatesLatestClick(updatedAt: Long): Boolean = request?.let { updatedAt < it.issuedAt } == true

    /** A failed seek rolls back; a confirmed seek must not roll back to a retired raw callback. */
    fun finish(now: Long): Long? = request?.let {
        request = null
        if (it.acknowledged) it.position(now) else null
    }

    fun clear() { request = null }
}
