package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

/** Media positions include the user's lyric offset, not an animation-specific timing offset. */
internal data class ExternalLyricWindow(
    val current: String?, val next: String?, val nextPositionMs: Long?, val intervalMs: Long,
    val positionMs: Long = 0L, val sampledAtMs: Long = 0L,
)

internal fun externalLyricWindow(lyrics: List<LyricEntry>, positionMs: Long, offsetMs: Long): ExternalLyricWindow {
    val target = (positionMs + offsetMs).coerceAtLeast(0L)
    var low = 0
    var high = lyrics.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (lyrics[mid].startTimeMs <= target) low = mid + 1 else high = mid
    }
    var previous = lyrics.getOrNull(low - 1)
    val current = findExternalBluetoothLyricLine(lyrics, positionMs, offsetMs)
    // Equal consecutive text does not emit a new current-line flow value. Look past it now
    // so the following distinct line is still delivered before its deadline.
    while (low < lyrics.size && current != null) {
        var end = low
        while (end + 1 < lyrics.size && lyrics[end + 1].startTimeMs == lyrics[low].startTimeMs) end++
        val candidate = lyrics[end]
        val active = previous ?: break
        if (candidate.text.trim() != current || candidate.startTimeMs > maxOf(active.startTimeMs, active.endTimeMs) + EXTERNAL_BLUETOOTH_LYRIC_STALE_GRACE_MS) break
        previous = candidate
        low = end + 1
    }
    val expires = previous?.let { maxOf(it.startTimeMs, it.endTimeMs) + EXTERNAL_BLUETOOTH_LYRIC_STALE_GRACE_MS + 1 }
    val upcoming = lyrics.getOrNull(low)
    if (current != null && expires != null && (upcoming == null || expires < upcoming.startTimeMs)) {
        return ExternalLyricWindow(current, null, expires - offsetMs, expires - checkNotNull(previous).startTimeMs, positionMs)
    }
    val next = upcoming?.let { first ->
        var index = low
        while (index + 1 < lyrics.size && lyrics[index + 1].startTimeMs == first.startTimeMs) index++
        lyrics[index]
    }
    return ExternalLyricWindow(current, next?.text?.trim()?.takeIf(String::isNotEmpty),
        next?.startTimeMs?.minus(offsetMs), next?.let { it.startTimeMs - (previous?.startTimeMs ?: 0L) } ?: 0L, positionMs)
}
