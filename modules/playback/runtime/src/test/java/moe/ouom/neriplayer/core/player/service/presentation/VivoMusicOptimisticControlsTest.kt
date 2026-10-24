package moe.ouom.neriplayer.core.player.service.presentation

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VivoMusicOptimisticControlsTest {
    private val actual = PlaybackServiceNotificationRenderInputs(
        SongItem(1L, "Song", "Artist", "Album", 0L, 60_000L, null), "Artist", false, false,
        false, false, false, StatusBarLyricNotificationState(false, null), null, null,
    )

    private fun bridge(): VivoMusicIslandBridge = VivoMusicIslandBridge(ApplicationProvider.getApplicationContext<Context>()).also {
        it.javaClass.getDeclaredField("lastInputs").apply { isAccessible = true }.set(it, actual)
    }

    private fun resolve(bridge: VivoMusicIslandBridge, inputs: PlaybackServiceNotificationRenderInputs) =
        bridge.javaClass.getDeclaredMethod("resolvePendingInputs", PlaybackServiceNotificationRenderInputs::class.java)
            .apply { isAccessible = true }.invoke(bridge, inputs) as PlaybackServiceNotificationRenderInputs

    @Test
    fun `progress ticks reuse seek targets but song and duration changes rebuild them`() {
        val bridge = bridge()
        val prepare = bridge.javaClass.getDeclaredMethod("ensureSeekIntents", String::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val targets = bridge.javaClass.getDeclaredField("seekTargets").apply { isAccessible = true }
        prepare.invoke(bridge, "first", 60_000L)
        val first = targets.get(bridge) as List<*>
        assertEquals(41, first.size)
        prepare.invoke(bridge, "first", 60_000L)
        assertSame(first, targets.get(bridge))
        prepare.invoke(bridge, "first", 30_000L)
        assertNotSame(first, targets.get(bridge))
        val preview = targets.get(bridge)
        prepare.invoke(bridge, "second", 30_000L)
        assertNotSame(preview, targets.get(bridge))
        prepare.invoke(bridge, "second", 0L)
        assertTrue((targets.get(bridge) as List<*>).isEmpty())
        bridge.clear()
    }

    @Test
    fun `pending controls render before the player acknowledges them`() {
        val bridge = bridge()
        bridge.previewControl(true, true, 30_000L)
        val preview = resolve(bridge, actual)
        assertTrue(preview.playbackControlPlaying)
        assertTrue(preview.favorite)
        assertEquals(30_000L, preview.positionMs)
        val acknowledged = actual.copy(playbackControlPlaying = true, favorite = true, positionMs = 30_000L)
        assertEquals(acknowledged, resolve(bridge, acknowledged))
        bridge.clear()
    }

    @Test
    fun `unacknowledged action rolls back to actual state after three seconds`() {
        val bridge = bridge()
        bridge.previewControl(true, true, 30_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val restored = resolve(bridge, actual)
        assertFalse(restored.playbackControlPlaying)
        assertFalse(restored.favorite)
        assertEquals(0L, restored.positionMs)
        bridge.clear()
    }

    @Test
    fun `pending control does not follow a song change`() {
        val bridge = bridge()
        bridge.previewControl(true, null, 30_000L)
        val next = actual.copy(song = actual.song!!.copy(id = 2L))
        assertEquals(next, resolve(bridge, next))
        bridge.clear()
    }

    @Test fun `latest seek acknowledgement does not reopen the gate for a retired target`() {
        val bridge = bridge()
        bridge.previewControl(null, null, 48_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        bridge.previewControl(null, null, 12_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(12_000L, resolve(bridge, actual.copy(positionMs = 12_000L)).positionMs)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals("An old acknowledgement replaced the latest settled target", 12_000L,
            resolve(bridge, actual.copy(positionMs = 48_000L)).positionMs)
        bridge.clear()
    }
}
