package moe.ouom.neriplayer.core.player.service.car

import android.content.Context
import android.media.Rating
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.player.service.car.library.CarLibrarySnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

@RunWith(AndroidJUnit4::class)
class VivoMusicBrowserTest {
    @Test
    fun `native queue pages contain playable IDs from the same library`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val songs = (1L..125L).map { SongItem(it, "Song $it", "Artist", "Album", 0L, 60_000L, null) }
        val library = CarMediaLibrary(CarLibrarySnapshot(queue = songs))
        val first = vivoMusicChildren(context, library, VIVO_CURRENT_LIST, null)
        val second = vivoMusicChildren(context, library, VIVO_CURRENT_LIST, Bundle().apply {
            putInt("vivomusicmix_key_media_page", 1)
        })
        assertEquals(50, first.size)
        assertEquals(50, second.size)
        assertTrue(first.all { it.isPlayable })
        assertTrue(first.last().description.extras!!.getBoolean("vivomusicmix_key_has_more"))
        val selection = library.resolveId(second.first().mediaId!!)!!
        assertEquals(50, selection.startIndex)
        assertEquals(songs, selection.songs)
        val last = vivoMusicChildren(context, library, VIVO_CURRENT_LIST, Bundle().apply {
            putInt("vivomusicmix_key_media_page", 2)
        })
        assertEquals(25, last.size)
        assertFalse(last.last().description.extras!!.getBoolean("vivomusicmix_key_has_more"))
    }

    @Test
    fun `invalid and exhausted native pages return no items`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val songs = listOf(SongItem(1L, "Song", "Artist", "Album", 0L, 60_000L, null))
        val library = CarMediaLibrary(CarLibrarySnapshot(queue = songs))
        for (page in listOf(-1, 1, Int.MAX_VALUE)) {
            val options = Bundle().apply { putInt("vivomusicmix_key_media_page", page) }
            assertTrue(vivoMusicChildren(context, library, VIVO_CURRENT_LIST, options).isEmpty())
        }
        assertTrue(vivoMusicChildren(context, library, "unknown", null).isEmpty())
        assertTrue(vivoMusicChildren(context, library, VIVO_LOCAL_LIST, null).isEmpty())
    }

    @Test
    fun `heart ratings target the existing favorite owner`() {
        val port = mock(CarMediaSessionControlPort::class.java) { invocation ->
            if (invocation.method.name == "runWhenLibraryReady") invocation.getArgument<() -> Unit>(1).invoke()
            null
        }
        val callback = CarMediaSessionCallback(port)
        callback.onSetRating(Rating.newHeartRating(true))
        callback.onSetRating(Rating.newHeartRating(false))
        verify(port).setFavorite(true)
        verify(port).setFavorite(false)
        clearInvocations(port)
        callback.onSetRating(Rating.newUnratedRating(Rating.RATING_HEART))
        callback.onSetRating(Rating.newStarRating(Rating.RATING_5_STARS, 3f))
        verifyNoInteractions(port)
    }
}
