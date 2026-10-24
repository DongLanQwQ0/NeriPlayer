package moe.ouom.neriplayer.core.player.service.presentation

import moe.ouom.neriplayer.data.identity.stableKey

import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.notification.ServiceWidgetLabels
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import android.content.Intent
import android.media.session.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackServicePresentationOwnerTest {
    @Test fun `widget receives full media artwork instead of the notification thumbnail`() = runTest {
        val current = song()
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        val full = mock(android.graphics.Bitmap::class.java)
        val thumbnail = mock(android.graphics.Bitmap::class.java)
        `when`(port.hasInstalledWidgets()).thenReturn(true)
        `when`(port.widgetLabels()).thenReturn(
            ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready"))
        `when`(artwork.snapshotFor(current)).thenReturn(
            PlaybackArtworkSnapshot("cover", full, thumbnail, true, true, false))
        owner(FakeSource(playback(current)), backgroundScope, port, artwork).updateWidget(true, false)
        val widget = mockingDetails(port).invocations.single { it.method.name == "publishWidget" }
        org.junit.Assert.assertSame(full, widget.arguments[1])
    }

    @Test fun `notification backdrop receives existing media artwork without enlarging its cover payload`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        val full = mock(android.graphics.Bitmap::class.java)
        val thumbnail = mock(android.graphics.Bitmap::class.java)
        `when`(artwork.snapshotFor(current)).thenReturn(
            PlaybackArtworkSnapshot("cover", full, thumbnail, true, true, false))
        owner(source, backgroundScope, port, artwork).updateNotification(false, true,
            StatusBarLyricNotificationState(false, null), false)
        val inputs = mockingDetails(port).invocations.single { it.method.name == "publishNotification" }
            .arguments[0] as PlaybackServiceNotificationRenderInputs
        org.junit.Assert.assertSame(full, inputs.backgroundArtwork)
        org.junit.Assert.assertSame(thumbnail, inputs.artwork)
    }

    @Test
    fun `notification progress uses actual preview duration and republishes duration changes`() = runTest {
        val current = song().copy(durationMs = 155_000L)
        val source = FakeSource(playback(current).copy(playerDurationMs = 30_000L))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val owner = owner(source, backgroundScope, port, lyricArtwork(current))
        val lyricState = StatusBarLyricNotificationState(false, null)
        owner.updateNotification(false, true, lyricState, false)
        source.currentPlayback = source.currentPlayback.copy(playerDurationMs = 60_000L)
        owner.updateNotification(false, true, lyricState, false)
        val inputs = mockingDetails(port).invocations
            .filter { it.method.name == "publishNotification" }
            .map { it.arguments[0] as PlaybackServiceNotificationRenderInputs }
        assertEquals(listOf(30_000L, 60_000L), inputs.map { it.durationMs })
        assertTrue(inputs.all { it.song == current })
    }

    @Test
    fun `transport intent retains pause control without reporting advancing playback`() {
        val requested = playback(song()).copy(
            transportActive = true,
            playbackControlPlaying = true,
        )

        assertEquals(PlaybackState.STATE_PAUSED, resolveServicePlaybackState(requested))
        assertEquals(0.0f, servicePlaybackSpeed(resolveServicePlaybackState(requested), requested))
        assertTrue(servicePlaybackControlPlaying(requested))
        assertFalse(servicePlaybackControlPlaying(requested.copy(playbackControlPlaying = false)))
        // Explicit pause must take effect even while the engine finishes its audio fade.
        assertFalse(servicePlaybackControlPlaying(requested.copy(enginePlaying = true, playbackControlPlaying = false)))
    }

    @Test
    fun `playback state projection prioritizes buffering and room fallback`() {
        val idle = playback(null)
        val fallback = playback(song()).copy(playerSongPresent = false, roomPlaying = true)
        assertEquals(PlaybackState.STATE_PAUSED, resolveServicePlaybackState(idle))
        assertEquals(PlaybackState.STATE_BUFFERING, resolveServicePlaybackState(fallback))
        assertEquals(PlaybackState.STATE_BUFFERING, resolveServicePlaybackState(fallback.copy(transportActive = true)))
        assertEquals(PlaybackState.STATE_PLAYING, resolveServicePlaybackState(playback(song()).copy(enginePlaying = true)))
        assertEquals(PlaybackState.STATE_BUFFERING, resolveServicePlaybackState(fallback.copy(buffering = true)))
        assertEquals(8_000L, servicePlaybackPositionMs(fallback))
        assertEquals(2_000L, servicePlaybackPositionMs(fallback.copy(playerSongPresent = true)))
        assertEquals(2_000L, servicePlaybackPositionMs(idle))
        assertEquals(1.25f, servicePlaybackSpeed(PlaybackState.STATE_PLAYING, fallback))
        assertEquals(0.0f, servicePlaybackSpeed(PlaybackState.STATE_PAUSED, fallback))
        assertEquals(0, serviceFavoriteControlFingerprint(false, true))
        assertEquals(1, serviceFavoriteControlFingerprint(true, false))
        assertEquals(2, serviceFavoriteControlFingerprint(true, true))
    }

    @Test
    fun `buffering retains pause controls without reporting advancing playback`() {
        val buffering = playback(song()).copy(
            transportActive = true,
            buffering = true,
            playbackControlPlaying = true,
        )

        assertEquals(PlaybackState.STATE_BUFFERING, resolveServicePlaybackState(buffering))
        assertEquals(0.0f, servicePlaybackSpeed(resolveServicePlaybackState(buffering), buffering))
        assertTrue(servicePlaybackControlPlaying(buffering))
        assertFalse(servicePlaybackControlPlaying(buffering.copy(playbackControlPlaying = false)))
        assertTrue(servicePlaybackControlPlaying(playback(song()).copy(enginePlaying = true, playbackControlPlaying = true)))
    }

    @Test
    fun `external notification inputs reject unrelated media buttons and private share links`() {
        val current = song()
        assertNull(mediaButtonKeyEvent(null))
        val unrelated = mock(Intent::class.java)
        `when`(unrelated.action).thenReturn(Intent.ACTION_VIEW)
        assertNull(mediaButtonKeyEvent(unrelated))
        assertNull(resolveXiaomiIslandShare(null, "https://music.163.com/song?id=1"))
        assertNull(resolveXiaomiIslandShare(current, null))
        assertNull(resolveXiaomiIslandShare(current, "https://example.com/song"))
        assertEquals(
            current to "https://music.163.com/song?id=1",
            resolveXiaomiIslandShare(current, "https://music.163.com/song?id=1"),
        )
    }

    @Test
    fun `favorite refresh owns previous keys and reports only current song changes`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val owner = owner(source, backgroundScope)
        assertFalse(owner.refreshFavoriteSongKeys())
        source.favorites = setOf("another song")
        assertFalse(owner.refreshFavoriteSongKeys())
        source.favorites = setOf(current.stableKey())
        assertTrue(owner.refreshFavoriteSongKeys())
        assertFalse(owner.refreshFavoriteSongKeys())
        owner.resetFavoriteSongKeys()
        assertTrue(owner.refreshFavoriteSongKeys())
        source.favorites = emptySet()
        assertTrue(owner.refreshFavoriteSongKeys())
    }

    @Test
    fun `favorite controls require a ready playlist and noninteractive song`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val owner = owner(source, backgroundScope)

        assertFalse(owner.canToggleFavorite(null))
        assertTrue(owner.canToggleFavorite(current))
        val local = current.copy(mediaUri = "/music/local.mp3", localFilePath = "/music/local.mp3")
        source.currentPlayback = playback(local)
        source.localSong = true
        assertFalse(owner.canToggleFavorite(local))
        source.currentPlayback = playback(current)
        source.localSong = false
        source.playlistsReady = false
        assertFalse(owner.canToggleFavorite(current))
    }

    @Test
    fun `foreground notification publishes only a changed snapshot or forced refresh`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        val initialArtwork = artwork(null)
        val changedArtwork = artwork("content://covers/next.jpg")
        `when`(artwork.snapshotFor(current)).thenReturn(initialArtwork, initialArtwork, changedArtwork, changedArtwork)
        val owner = owner(source, backgroundScope, port, artwork)
        val lyricState = StatusBarLyricNotificationState(false, null)

        owner.updateNotification(false, false, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(true, true, lyricState, false)

        assertEquals(3, callCount(port, "publishNotification"))
        assertEquals(3, source.shareUrlRequests)
    }

    @Test
    fun `notification controls stay stable across track replacement and accept pause during loading`() = runTest {
        val current = song()
        val source = FakeSource(playback(current).copy(transportActive = true, playbackControlPlaying = true))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(artwork.snapshotFor(current)).thenReturn(artwork(null))
        val owner = owner(source, backgroundScope, port, artwork)
        val lyricState = StatusBarLyricNotificationState(false, null)

        // These callbacks can arrive independently: old media stops before the load job
        // exists, then buffering ends before onIsPlayingChanged(true) is delivered.
        val stages = listOf(
            source.currentPlayback.copy(enginePlaying = true),
            source.currentPlayback.copy(enginePlaying = false),
            source.currentPlayback.copy(buffering = true),
            source.currentPlayback.copy(buffering = false),
            source.currentPlayback.copy(enginePlaying = true),
            source.currentPlayback.copy(buffering = true, playbackControlPlaying = false),
            source.currentPlayback.copy(enginePlaying = true, playbackControlPlaying = false),
            source.currentPlayback.copy(enginePlaying = false, playbackControlPlaying = false),
        )
        for (stage in stages) {
            source.currentPlayback = stage
            owner.updateNotification(true, true, lyricState, false)
        }
        val inputs = mockingDetails(port).invocations
            .filter { it.method.name == "publishNotification" }
            .map { it.arguments[0] as PlaybackServiceNotificationRenderInputs }
        assertEquals(listOf(true, true, true, true, true, false, false, false),
            inputs.map { it.playbackControlPlaying })
    }

    @Test
    fun `unchanged notification still publishes widget playback and buffering transitions`() = runTest {
        val current = song()
        val source = FakeSource(playback(current).copy(
            transportActive = true,
            enginePlaying = true,
            playbackControlPlaying = true,
        ))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(port.hasInstalledWidgets()).thenReturn(true)
        `when`(port.widgetLabels()).thenReturn(
            ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready")
        )
        `when`(artwork.snapshotFor(current)).thenReturn(artwork(null))
        val owner = owner(source, backgroundScope, port, artwork)
        val lyricState = StatusBarLyricNotificationState(false, null)

        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        source.currentPlayback = source.currentPlayback.copy(enginePlaying = false, buffering = true)
        owner.updateNotification(false, true, lyricState, false)
        owner.updateNotification(false, true, lyricState, false)
        source.currentPlayback = source.currentPlayback.copy(enginePlaying = true, buffering = false)
        owner.updateNotification(false, true, lyricState, false)

        val states = mockingDetails(port).invocations
            .filter { it.method.name == "publishWidget" }
            .map { it.arguments[0] as PlaybackWidgetState }
        assertEquals(listOf(true, false, true), states.map { it.isPlaying })
        assertEquals(listOf("Playing", "Buffering", "Playing"), states.map { it.status })
        assertTrue(states.all { it.showPauseAction })
        assertEquals(1, callCount(port, "publishNotification"))
        assertEquals(1, source.shareUrlRequests)
    }

    @Test
    fun `widget projection keeps its last state and respects force`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(port.hasInstalledWidgets()).thenReturn(true)
        `when`(port.widgetLabels()).thenReturn(
            ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready")
        )
        `when`(artwork.snapshotFor(current)).thenReturn(artwork(null))
        val owner = owner(source, backgroundScope, port, artwork)

        owner.updateWidget(false, false)
        owner.updateWidget(false, false)
        owner.updateWidget(true, false)

        assertEquals(2, callCount(port, "publishWidget"))
    }

    @Test
    fun `widget progress switches between full and partial updates`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(port.hasInstalledWidgets()).thenReturn(true)
        `when`(port.widgetLabels()).thenReturn(
            ServiceWidgetLabels("NeriPlayer", "Idle", "Buffering", "Playing", "Paused", "Ready")
        )
        `when`(artwork.snapshotFor(current)).thenReturn(artwork(null))
        val owner = owner(source, backgroundScope, port, artwork)

        owner.updateWidgetProgress(false)
        owner.updateWidgetProgress(false)
        source.currentPlayback = source.currentPlayback.copy(transportActive = true, enginePlaying = true)
        owner.updateWidgetProgress(false)
        owner.updateWidgetProgress(false)

        assertEquals(2, callCount(port, "publishWidget"))
        assertEquals(1, callCount(port, "publishWidgetProgress"))
    }

    @Test
    fun `lyric metadata changes repost the notification once while the screen is on`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = lyricArtwork(current)
        val owner = owner(source, backgroundScope, port, artwork)
        val lyricState = StatusBarLyricNotificationState(false, null)
        owner.updateNotification(false, true, lyricState, false)

        source.lyric = ExternalBluetoothLyricPayload("first line")
        owner.refreshLyricMetadata(true, true, lyricState, false)
        owner.refreshLyricMetadata(true, true, lyricState, false)
        source.lyric = ExternalBluetoothLyricPayload("second line")
        owner.refreshLyricMetadata(true, true, lyricState, false)

        assertEquals(2, callCount(port, "setMetadata"))
        assertEquals(3, callCount(port, "publishNotification"))
    }

    @Test
    fun `screen off defers the lyric repost until the screen turns on`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val owner = owner(source, backgroundScope, port, lyricArtwork(current))
        val lyricState = StatusBarLyricNotificationState(false, null)
        owner.updateNotification(false, true, lyricState, false)

        source.lyric = ExternalBluetoothLyricPayload("first line")
        owner.refreshLyricMetadata(false, true, lyricState, false)
        source.lyric = ExternalBluetoothLyricPayload("second line")
        owner.refreshLyricMetadata(false, true, lyricState, false)
        assertEquals(1, callCount(port, "publishNotification"))

        owner.onScreenInteractive(true, lyricState, false)
        owner.onScreenInteractive(true, lyricState, false)

        assertEquals(2, callCount(port, "publishNotification"))
    }

    @Test
    fun `flyme status bar lyrics keep their own per line notification`() = runTest {
        val current = song()
        val source = FakeSource(playback(current))
        val port = mock(PlaybackServicePresentationPort::class.java)
        val owner = owner(source, backgroundScope, port, lyricArtwork(current))
        val flymeState = StatusBarLyricNotificationState(true, "line")
        owner.updateNotification(false, true, flymeState, false)

        source.lyric = ExternalBluetoothLyricPayload("first line")
        owner.refreshLyricMetadata(true, true, flymeState, false)

        assertEquals(1, callCount(port, "setMetadata"))
        assertEquals(1, callCount(port, "publishNotification"))
    }

    @Test
    fun `floating lyrics action persists the selected state in the owner scope`() = runTest {
        val source = FakeSource(playback(null))
        val owner = owner(source, backgroundScope)

        owner.applyFloatingLyricsExternalAction(currentEnabled = false, legacyHideAction = false)
        owner.applyFloatingLyricsExternalAction(currentEnabled = true, legacyHideAction = true)
        runCurrent()

        assertEquals(listOf(true, false), source.floatingLyricsWrites)
    }

    private fun owner(
        source: FakeSource,
        scope: CoroutineScope,
        port: PlaybackServicePresentationPort = mock(PlaybackServicePresentationPort::class.java),
        artwork: PlaybackArtworkOwner = mock(PlaybackArtworkOwner::class.java),
    ) = PlaybackServicePresentationOwner(source, port, artwork, scope)

    private fun playback(song: SongItem?) = PlaybackServicePlaybackSnapshot(
        song = song,
        playerSongPresent = song != null,
        playerPositionMs = 2_000L,
        roomPositionMs = 8_000L,
        buffering = false,
        transportActive = false,
        enginePlaying = false,
        roomPlaying = false,
        playbackControlPlaying = false,
        audioRouteMuted = false,
        playbackSpeed = 1.25f,
    )

    private fun song() = SongItem(
        id = 1,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 1,
        durationMs = 10_000,
        coverUrl = null,
    )

    private fun artwork(source: String?) = PlaybackArtworkSnapshot(source, null, null, false, false, false)

    private fun lyricArtwork(song: SongItem): PlaybackArtworkOwner = mock(PlaybackArtworkOwner::class.java).also {
        `when`(it.snapshotFor(song)).thenReturn(artwork(null))
        `when`(it.observe(song)).thenReturn(artwork(null))
    }

    private fun callCount(port: PlaybackServicePresentationPort, method: String): Int =
        mockingDetails(port).invocations.count { it.method.name == method }

    private class FakeSource(var currentPlayback: PlaybackServicePlaybackSnapshot) : PlaybackServicePresentationSource {
        var favorites: Set<String> = emptySet()
        var playlistsReady = true
        var localSong = false
        val floatingLyricsWrites = mutableListOf<Boolean>()
        var shareUrlRequests = 0
        var lyric = ExternalBluetoothLyricPayload()

        override fun playback() = currentPlayback
        override fun metadata() = PlaybackServiceMetadataInputs(lyric, null, lyric.lyric != null)
        override fun timer() = PlaybackServiceTimerInputs(SleepTimerState(), "")
        override fun favoriteSongKeys() = favorites
        override fun localPlaylistsReady() = playlistsReady
        override fun isLocalSong(song: SongItem) = localSong
        override fun shareUrl(song: SongItem): String? {
            shareUrlRequests++
            return null
        }
        override suspend fun setFloatingLyricsEnabled(enabled: Boolean) {
            floatingLyricsWrites += enabled
        }
    }
}
