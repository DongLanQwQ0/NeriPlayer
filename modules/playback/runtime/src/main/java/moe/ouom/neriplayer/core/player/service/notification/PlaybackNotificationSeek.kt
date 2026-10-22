package moe.ouom.neriplayer.core.player.service.notification

internal const val NOTIFICATION_SEEK_STEPS = 40

internal fun notificationSeekPosition(durationMs: Long, step: Int): Long? {
    if (durationMs <= 0L || step !in 0..NOTIFICATION_SEEK_STEPS) return null
    return durationMs / NOTIFICATION_SEEK_STEPS * step +
        durationMs % NOTIFICATION_SEEK_STEPS * step / NOTIFICATION_SEEK_STEPS
}

internal fun resolveNotificationSeekPosition(
    currentSongKey: String?,
    durationMs: Long,
    requestedSongKey: String?,
    positionMs: Long,
): Long? {
    if (currentSongKey.isNullOrBlank() || requestedSongKey != currentSongKey || durationMs <= 0L || positionMs < 0L) return null
    return positionMs.coerceAtMost(durationMs)
}
