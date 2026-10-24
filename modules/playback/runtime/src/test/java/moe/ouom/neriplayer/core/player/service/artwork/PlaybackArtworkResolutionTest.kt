package moe.ouom.neriplayer.core.player.service.artwork

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackArtworkResolutionTest {
    @Test fun `retained widget cover is high resolution while metadata and notifications remain bounded`() = runTest {
        val song = SongItem(1, "Test", "Artist", "Album", 0, 60_000, null)
        val resolver = mock(PlaybackCoverSourceResolver::class.java)
        `when`(resolver.immediate(song)).thenReturn("content://artwork/1")
        val cover = Bitmap.createBitmap(1536, 1536, Bitmap.Config.ARGB_8888)
        var loads = 0
        val owner = PlaybackArtworkOwner(resolver, PlaybackArtworkBitmapLoader { loads++; cover },
            PlaybackArtworkClock { 1000 }, backgroundScope, StandardTestDispatcher(testScheduler), {})
        owner.observe(song)
        runCurrent()
        val snapshot = owner.snapshotFor(song)
        assertTrue(snapshot.mediaReady && snapshot.notificationReady)
        assertSame(cover, owner.widgetArtwork())
        assertEquals(512, snapshot.mediaBitmap!!.width)
        assertEquals(256, snapshot.notificationBitmap!!.width)
        repeat(20) { owner.observe(song) }
        runCurrent()
        assertEquals(1, loads)
        owner.close()
        assertNull(owner.widgetArtwork())
        assertFalse(cover.isRecycled)
    }
}
