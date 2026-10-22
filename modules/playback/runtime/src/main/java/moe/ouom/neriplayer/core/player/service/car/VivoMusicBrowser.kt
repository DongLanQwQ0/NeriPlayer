package moe.ouom.neriplayer.core.player.service.car

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist

internal const val VIVO_CURRENT_LIST = "vivomusicmix_current_list"
internal const val VIVO_FAVORITE_LIST = "vivomusicmix_favorite_list"
internal const val VIVO_LOCAL_LIST = "vivomusicmix_local_list"
private const val VIVO_PAGE = "vivomusicmix_key_media_page"
private const val VIVO_HAS_MORE = "vivomusicmix_key_has_more"
private const val VIVO_PAGE_SIZE = 50

internal fun isVivoMusicList(id: String): Boolean =
    id == VIVO_CURRENT_LIST || id == VIVO_FAVORITE_LIST || id == VIVO_LOCAL_LIST

/** Use the existing library IDs so playFromMediaId keeps its identity and queue checks. */
internal fun vivoMusicChildren(
    context: Context,
    library: CarMediaLibrary,
    parentId: String,
    options: Bundle?,
): List<MediaBrowserCompat.MediaItem> {
    val directory = when (parentId) {
        VIVO_CURRENT_LIST -> CarMediaIds.QUEUE
        VIVO_LOCAL_LIST -> CarMediaIds.OFFLINE
        VIVO_FAVORITE_LIST -> FavoritesPlaylist.firstOrNull(PlayerManager.playlistsFlow.value, context)
            ?.let { CarMediaIds.playlist(it.id) } ?: return emptyList()
        else -> return emptyList()
    }
    // The OEM protocol uses its own page number, not MediaBrowser's PAGE/PAGE_SIZE pair.
    val page = options?.getInt(VIVO_PAGE, 0) ?: 0
    if (page < 0) return emptyList()
    val start = page.toLong() * VIVO_PAGE_SIZE
    if (start > Int.MAX_VALUE) return emptyList()
    val items = sequence {
        val pending = ArrayDeque(library.children(directory))
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (item.isPlayable) yield(item)
            else library.children(item.mediaId).asReversed().forEach(pending::addFirst)
        }
    }.drop(start.toInt()).take(VIVO_PAGE_SIZE + 1).toList()
    return items.take(VIVO_PAGE_SIZE).map { item ->
        val androidItem = item.toAndroidMediaItem(context)
        val description = checkNotNull(MediaDescriptionCompat.fromMediaDescription(androidItem.description))
        val extras = Bundle(description.extras ?: Bundle()).apply {
            putBoolean(VIVO_HAS_MORE, items.size > VIVO_PAGE_SIZE)
            putInt(VIVO_PAGE, page)
        }
        val copy = MediaDescriptionCompat.Builder()
            .setMediaId(description.mediaId)
            .setTitle(description.title)
            .setSubtitle(description.subtitle)
            .setDescription(description.description)
            .setIconUri(description.iconUri)
            .setExtras(extras)
            .build()
        MediaBrowserCompat.MediaItem(copy, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }
}
