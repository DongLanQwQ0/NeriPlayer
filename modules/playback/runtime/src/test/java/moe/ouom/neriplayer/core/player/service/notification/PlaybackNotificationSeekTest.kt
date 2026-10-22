package moe.ouom.neriplayer.core.player.service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackNotificationSeekTest {
    @Test
    fun `tap targets cover both ends and the midpoint`() {
        assertEquals(0L, notificationSeekPosition(180_000L, 0))
        assertEquals(90_000L, notificationSeekPosition(180_000L, 20))
        assertEquals(180_000L, notificationSeekPosition(180_000L, 40))
        assertEquals(10_001L, notificationSeekPosition(10_001L, 40))
        assertEquals(Long.MAX_VALUE, notificationSeekPosition(Long.MAX_VALUE, 40))
    }

    @Test
    fun `unknown duration and invalid steps cannot create controls`() {
        assertNull(notificationSeekPosition(0L, 10))
        assertNull(notificationSeekPosition(-1L, 10))
        assertNull(notificationSeekPosition(100L, -1))
        assertNull(notificationSeekPosition(100L, 41))
    }

    @Test
    fun `old cards cannot seek a different song`() {
        assertNull(resolveNotificationSeekPosition("new", 100L, "old", 50L))
        assertNull(resolveNotificationSeekPosition(null, 100L, "old", 50L))
        assertNull(resolveNotificationSeekPosition("", 100L, "", 50L))
    }

    @Test
    fun `valid request is bounded without changing paused state`() {
        assertEquals(50L, resolveNotificationSeekPosition("same", 100L, "same", 50L))
        assertEquals(100L, resolveNotificationSeekPosition("same", 100L, "same", 500L))
        assertNull(resolveNotificationSeekPosition("same", 100L, "same", -1L))
        assertNull(resolveNotificationSeekPosition("same", 0L, "same", 0L))
    }
}
