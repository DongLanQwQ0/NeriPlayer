package moe.ouom.neriplayer.core.player.service.presentation

import android.media.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowBuild

@RunWith(AndroidJUnit4::class)
class VivoMusicMetadataTest {
    private val artwork = PlaybackArtworkSnapshot(null, null, null, false, false, false)
    private val song = SongItem(1L, "Song", "Artist", "Album", 0L, 60_000L, null)
    private val snapshot = serviceMetadataSnapshot(
        song, serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false), artwork,
    )

    @Test
    fun `native heart reflects saved state and supported actions`() {
        ShadowBuild.setManufacturer("vivo")
        for (favorite in listOf(false, true)) {
            val metadata = serviceMediaMetadata(snapshot.copy(favorite = favorite, canToggleFavorite = true), artwork)
            assertEquals(1879L, metadata.getLong("vivomusicmix.media.metadata.support_event"))
            for (listEvent in listOf(256L, 512L, 1024L)) {
                assertTrue(metadata.getLong("vivomusicmix.media.metadata.support_event") and listEvent != 0L)
            }
            assertEquals(favorite, checkNotNull(metadata.getRating(MediaMetadata.METADATA_KEY_RATING)).hasHeart())
            assertEquals(favorite, checkNotNull(metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING)).hasHeart())
            assertEquals(60_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        }
    }

    @Test
    fun `unavailable favorite disables the native heart without disabling transport`() {
        ShadowBuild.setManufacturer("VIVO")
        val metadata = serviceMediaMetadata(snapshot.copy(canToggleFavorite = false), artwork)
        val events = metadata.getLong("vivomusicmix.media.metadata.support_event")
        assertEquals(1815L, events)
        assertFalse(events and 64L != 0L)
        assertTrue(events and 16L != 0L)
    }

    @Test
    fun `other manufacturers do not receive vivo capability metadata`() {
        ShadowBuild.setManufacturer("Google")
        val metadata = serviceMediaMetadata(snapshot.copy(canToggleFavorite = true), artwork)
        assertFalse(metadata.containsKey("vivomusicmix.media.metadata.support_event"))
    }
}
