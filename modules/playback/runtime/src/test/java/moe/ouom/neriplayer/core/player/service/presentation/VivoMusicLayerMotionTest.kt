package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.os.SystemClock
import java.time.Duration
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.model.SongItem
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VivoMusicLayerMotionTest {
    private fun image(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    @Test fun `rapid song changes retain the current blend and progress ticks reuse layers`() {
        val motion = VivoMusicLayerMotion(220, .03f, 0f)
        val first = motion.update("one", 0) { image(Color.RED) }
        val second = motion.update("two", 100) { image(Color.BLUE) }
        assertSame(first.to, second.from)
        val middle = motion.update("two", 210) { error("Progress must not rasterize") }
        assertEquals(.5f, middle.progress, .001f)
        val third = motion.update("three", 210) { image(Color.GREEN) }
        val pixel = third.from!!.getPixel(16, 16)
        assertTrue(Color.red(pixel) > 0); assertTrue(Color.blue(pixel) > 0)
        assertNull(motion.update("three", 431) { error("Must reuse") }.from)
        assertNull(motion.update("seek", 440, animate = false) { image(Color.BLACK) }.from)
    }
    @Test fun `reversing play pause retargets the current value and latest state wins`() {
        val motion = VivoMusicToggleMotion()
        assertEquals(0f, motion.update(false, 0).from, 0f)
        assertEquals(0f, motion.update(true, 100).from, 0f)
        val reversed = motion.update(false, 170)
        assertEquals(.5f, reversed.from, .001f)
        assertEquals(0f, reversed.to, 0f)
        assertEquals(1f, motion.update(false, 311).progress, 0f)
    }
    @Test fun `play acknowledgements wait for the latest local motion and source position accounts for age`() {
        val bridge = VivoMusicIslandBridge(ApplicationProvider.getApplicationContext<Context>())
        val inputs = PlaybackServiceNotificationRenderInputs(
            SongItem(1, "Song", "Artist", "Album", 0, 60000, null), "Artist", false, true,
            false, false, false, StatusBarLyricNotificationState(false, null), null, null,
            positionMs = 6000, playbackSpeed = 2f, positionUpdatedAtMs = SystemClock.elapsedRealtime())
        bridge.javaClass.getDeclaredField("lastInputs").apply { isAccessible = true }.set(bridge, inputs)
        val defer = bridge.javaClass.getDeclaredMethod("deferSeekPublication", PlaybackServiceNotificationRenderInputs::class.java).apply { isAccessible = true }
        val resolve = bridge.javaClass.getDeclaredMethod("resolvePendingInputs", PlaybackServiceNotificationRenderInputs::class.java).apply { isAccessible = true }
        try {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100))
            assertEquals(6200L, (resolve.invoke(bridge, inputs) as PlaybackServiceNotificationRenderInputs).positionMs)
            bridge.previewControl(false, null, null)
            assertEquals(true, defer.invoke(bridge, inputs))
            ShadowSystemClock.advanceBy(Duration.ofMillis(70))
            bridge.previewControl(true, null, null)
            ShadowSystemClock.advanceBy(Duration.ofMillis(100))
            assertEquals(true, defer.invoke(bridge, inputs))
            ShadowSystemClock.advanceBy(Duration.ofMillis(40))
            assertEquals(false, defer.invoke(bridge, inputs))
        } finally { bridge.clear() }
    }
}
