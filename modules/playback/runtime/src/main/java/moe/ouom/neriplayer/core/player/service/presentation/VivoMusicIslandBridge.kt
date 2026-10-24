package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.media.session.PlaybackState
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.feature.player.R
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.core.player.presentation.widget.formatPlaybackWidgetTime
import moe.ouom.neriplayer.core.player.presentation.widget.playbackWidgetProgress
import moe.ouom.neriplayer.core.player.service.notification.favoriteActionTitle
import moe.ouom.neriplayer.core.player.service.notification.NOTIFICATION_SEEK_STEPS
import moe.ouom.neriplayer.core.player.service.notification.notificationSeekPosition
import moe.ouom.neriplayer.data.identity.stableKey

/** Independent of the native Walkman browser; only posts when this app's own scene is enabled. */
internal class VivoMusicIslandBridge(private val context: Context) {
    companion object {
        const val SCENE = "NERIPLAYER_MUSIC"
        const val CHANNEL = "neriplayer_vivo_music_island"
        const val ID = 0x4e505649
        private const val PREFIX = "notification.superx."
        // CustomSuperXTemplate on the verified OriginOS 6 firmware.
        private const val CUSTOM_TEMPLATE = 7
        private const val NOTIFICATION_LOCKSCREEN_STATUSBAR = 0x111
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val supported = Build.MANUFACTURER.equals("vivo", ignoreCase = true)
    private var published = false
    private var capabilityFailureLogged = false
    private val cardArtwork = VivoMusicCardArtwork(context)
    private val progressMotion = VivoMusicProgressMotion()
    private val animatedCard by lazy { VivoMusicAnimatedCard(context) }
    private val drawingControls = mutableMapOf<Pair<String, String>, PendingIntent>()
    private var lastInputs: PlaybackServiceNotificationRenderInputs? = null
    private var lastProgressUpdateMs = 0L
    private var seekIdentity: Pair<String, Long>? = null
    private var seekIntents: List<PendingIntent> = emptyList()
    private var seekTargets: List<RemoteViews> = emptyList()
    private val checkedIntents = mutableMapOf<String, PendingIntent>()
    private val transportIntents = mutableMapOf<String, PendingIntent>()
    private val openIntent by lazy {
        PendingIntent.getActivity(context, ID,
            PlayerDependencies.presentation.mainActivityIntent(context).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private data class PendingControls(
        val songKey: String,
        val playing: Boolean? = null,
        val favorite: Boolean? = null,
        val positionMs: Long? = null,
        val expiresAtMs: Long,
    )
    private var pendingControls: PendingControls? = null
    private val handler = Handler(Looper.getMainLooper())
    private val rollback = Runnable {
        pendingControls = null
        lastInputs?.let(::publish)
    }

    fun previewControl(playing: Boolean?, favorite: Boolean?, positionMs: Long?) {
        val inputs = lastInputs ?: return
        val songKey = inputs.song?.stableKey() ?: return
        if (positionMs != null) {
            val now = SystemClock.elapsedRealtime()
            val playingNow = inputs.playbackControlPlaying && !inputs.audioRouteMuted
            progressMotion.update(songKey, inputs.durationMs, inputs.positionMs, playingNow, now)
            progressMotion.update(songKey, inputs.durationMs, positionMs, playingNow, now, seek = true)
        }
        val previous = pendingControls?.takeIf { it.songKey == songKey }
        pendingControls = PendingControls(songKey, playing ?: previous?.playing,
            favorite ?: previous?.favorite, positionMs ?: previous?.positionMs,
            SystemClock.elapsedRealtime() + 3_000L)
        handler.removeCallbacks(rollback)
        handler.postDelayed(rollback, 3_000L)
        publish(inputs)
    }

    private fun resolvePendingInputs(inputs: PlaybackServiceNotificationRenderInputs): PlaybackServiceNotificationRenderInputs {
        val pending = pendingControls ?: return inputs
        if (pending.songKey != inputs.song?.stableKey() || SystemClock.elapsedRealtime() >= pending.expiresAtMs) {
            pendingControls = null
            handler.removeCallbacks(rollback)
            return inputs
        }
        val remaining = pending.copy(
            playing = pending.playing?.takeUnless { it == inputs.playbackControlPlaying && !inputs.audioRouteMuted },
            favorite = pending.favorite?.takeUnless { it == inputs.favorite },
            positionMs = pending.positionMs?.takeUnless { kotlin.math.abs(it - inputs.positionMs) < 1_000L },
        )
        pendingControls = remaining.takeUnless { it.playing == null && it.favorite == null && it.positionMs == null }
        if (pendingControls == null) handler.removeCallbacks(rollback)
        return inputs.copy(
            playbackControlPlaying = remaining.playing ?: inputs.playbackControlPlaying,
            audioRouteMuted = if (remaining.playing == true) false else inputs.audioRouteMuted,
            favorite = remaining.favorite ?: inputs.favorite,
            positionMs = remaining.positionMs ?: inputs.positionMs,
        )
    }

    fun updatePlaybackState(state: PlaybackState) {
        val inputs = lastInputs ?: return
        if (!context.getSystemService(PowerManager::class.java).isInteractive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressUpdateMs < 1_000L) return
        if (inputs.positionMs == state.position) return
        publish(inputs.copy(positionMs = state.position))
    }

    fun publish(inputs: PlaybackServiceNotificationRenderInputs) {
        if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (inputs.song == null || !available()) {
            clear()
            return
        }
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.vivo_music_island), NotificationManager.IMPORTANCE_LOW,
        ))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            clear()
            return
        }
        runCatching {
            val exists = published && manager.activeNotifications.any { it.id == ID }
            manager.notify(ID, notification(resolvePendingInputs(inputs), if (exists) 1 else 0))
            published = true
            lastInputs = inputs
            lastProgressUpdateMs = SystemClock.elapsedRealtime()
        }.onFailure { NPLogger.w("NERI-VivoIsland", "Music island publication failed", it) }
    }

    private fun available(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return try {
            NotificationManager::class.java.getDeclaredMethod(
                "getSceneStatus", String::class.java, String::class.java,
            ).invoke(manager, context.packageName, SCENE) == true
        } catch (failure: ReflectiveOperationException) {
            if (!capabilityFailureLogged) {
                capabilityFailureLogged = true
                NPLogger.w("NERI-VivoIsland", "Unable to query the music scene", failure)
            }
            false
        } catch (failure: SecurityException) {
            if (!capabilityFailureLogged) {
                capabilityFailureLogged = true
                NPLogger.w("NERI-VivoIsland", "Music scene query denied", failure)
            }
            false
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    internal fun notification(inputs: PlaybackServiceNotificationRenderInputs, operation: Int): Notification {
        val song = checkNotNull(inputs.song)
        val title = song.displayName()
        val artist = song.displayArtist()
        progressMotion.update(song.stableKey(), inputs.durationMs, inputs.positionMs,
            inputs.playbackControlPlaying && !inputs.audioRouteMuted, SystemClock.elapsedRealtime())
        cardArtwork.update(inputs.artwork)
        val open = openIntent
        val icon = cardArtwork.cover?.let(Icon::createWithBitmap)
            ?: Icon.createWithResource(context, CommonR.drawable.ic_notification_small)
        val data = Bundle().apply {
            putInt(PREFIX + "operation", operation)
            putString(PREFIX + "scene", SCENE)
            putInt(PREFIX + "template", CUSTOM_TEMPLATE)
            putInt(PREFIX + "displays", NOTIFICATION_LOCKSCREEN_STATUSBAR)
            putBoolean(PREFIX + "showNotify", false)
            putBoolean(PREFIX + "sound", false)
            putBoolean(PREFIX + "dismissWhenKill", true)
            putBoolean(PREFIX + "disableInvertColor", true)
            putParcelable(PREFIX + "clickResp", open)
            putParcelable(PREFIX + "customTemplate", card(inputs, open, false))
            putBundle(PREFIX + "baseInfos", Bundle().apply {
                putParcelable(PREFIX + "baseInfos.icon", icon)
                putCharSequence(PREFIX + "baseInfos.title", title)
                putCharSequence(PREFIX + "baseInfos.content", artist)
            })
            putBundle(PREFIX + "island", Bundle().apply {
                putInt("island.superx.leftTemplate", 1)
                putInt("island.superx.rightTemplate", 1)
                putBundle("island.superx.leftInfo", Bundle().apply {
                    putParcelable("island.superx.leftInfo.icon", icon)
                })
                putBundle("island.superx.rightInfo", Bundle().apply {
                    putInt("island.superx.rightInfo.waveState", if (inputs.playbackControlPlaying && !inputs.audioRouteMuted) 1 else 0)
                    putIntegerArrayList("island.superx.rightInfo.waveColor", arrayListOf(0xFF00B7E3.toInt(), 0xFF00D0CF.toInt()))
                })
                putInt("island.superx.islandClick", 0)
                putInt("island.superx.template", CUSTOM_TEMPLATE)
                putParcelable("island.superx.customTemplate", card(inputs, open, true))
                putParcelable("island.superx.clickResp", open)
            })
            putBundle(PREFIX + "capsule", Bundle().apply {
                putInt(PREFIX + "capsule.state", 1)
                putParcelable(PREFIX + "capsule.icon", icon)
                putString(PREFIX + "capsule.content", title)
                putParcelable(PREFIX + "capsule.clickResp", open)
            })
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(CommonR.drawable.ic_notification_small)
            .setContentTitle(title).setContentText(artist).setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true).addExtras(data).build()
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private fun card(inputs: PlaybackServiceNotificationRenderInputs, open: PendingIntent, cameraSafe: Boolean): RemoteViews {
        val xml = classicCard(inputs, open, cameraSafe)
        // Preserve the existing card on older hosts; the verified OriginOS 6 host draws format 6.
        if (!VivoMusicAnimatedCard.supported()) return xml
        val songKey = checkNotNull(inputs.song).stableKey()
        val actions = linkedMapOf(
            R.id.vivo_music_card to open,
            R.id.vivo_music_app to open,
            R.id.vivo_music_previous to control(AudioPlayerService.ACTION_PREV, 2),
            R.id.vivo_music_next to control(AudioPlayerService.ACTION_NEXT, 3),
            R.id.vivo_music_play to drawingControl(AudioPlayerService.ACTION_TOGGLE_PLAY_PAUSE, songKey, 10),
            R.id.vivo_music_favorite to if (inputs.interactiveFavorite) open else
                drawingControl(AudioPlayerService.ACTION_TOGGLE_FAV, songKey, 11),
        )
        return animatedCard.render(xml, inputs, cameraSafe,
            progressMotion.frame(SystemClock.elapsedRealtime()), actions, seekIntents)
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private fun classicCard(inputs: PlaybackServiceNotificationRenderInputs, open: PendingIntent, cameraSafe: Boolean) =
        RemoteViews(context.packageName, R.layout.notification_vivo_music).apply {
            val song = checkNotNull(inputs.song)
            val durationMs = inputs.durationMs.coerceAtLeast(0L)
            setTextViewText(R.id.vivo_music_title, song.displayName())
            setTextViewText(R.id.vivo_music_artist,
                inputs.lyricLine?.takeIf { it.isNotBlank() } ?: song.displayArtist())
            setOnClickPendingIntent(R.id.vivo_music_card, open)
            cardArtwork.update(inputs.artwork)
            setImageViewBitmap(R.id.vivo_music_backdrop, cardArtwork.background)
            setImageViewBitmap(R.id.vivo_music_app, cardArtwork.appIcon)
            setContentDescription(R.id.vivo_music_app, context.getString(CommonR.string.app_name))
            setOnClickPendingIntent(R.id.vivo_music_app, open)
            // Use our owned bitmap instead of the shared small-icon resource as the card placeholder.
            setImageViewBitmap(R.id.vivo_music_artwork, cardArtwork.cover ?: cardArtwork.appIcon)
            setViewVisibility(R.id.vivo_music_progress_row, if (durationMs > 0L) View.VISIBLE else View.GONE)
            setTextViewText(R.id.vivo_music_elapsed, formatPlaybackWidgetTime(inputs.positionMs.coerceIn(0L, durationMs)).padStart(5, '0'))
            setTextViewText(R.id.vivo_music_duration, formatPlaybackWidgetTime(durationMs).padStart(5, '0'))
            setProgressBar(R.id.vivo_music_progress, 1000, playbackWidgetProgress(inputs.positionMs, durationMs), false)
            removeAllViews(R.id.vivo_music_seek_targets)
            ensureSeekIntents(song.stableKey(), durationMs)
            seekTargets.forEachIndexed { step, target ->
                // Adding a child changes its hierarchy root; never share that mutable root between cards.
                addStableView(R.id.vivo_music_seek_targets, target.clone(), step)
            }
            setCompoundButtonChecked(R.id.vivo_music_favorite, inputs.favorite)
            setContentDescription(R.id.vivo_music_favorite, context.getString(favoriteActionTitle(inputs.favorite)))
            if (inputs.interactiveFavorite) {
                setOnClickPendingIntent(R.id.vivo_music_favorite, open)
            } else {
                setOnCheckedChangeResponse(R.id.vivo_music_favorite, RemoteViews.RemoteResponse.fromPendingIntent(
                    checkedControl(AudioPlayerService.ACTION_VIVO_SET_FAVORITE, song.stableKey(), 4)))
            }
            val choice = moe.ouom.neriplayer.core.player.service.notification.playbackControlActionChoice(
                inputs.audioRouteMuted, inputs.playbackControlPlaying,
            )
            setCompoundButtonChecked(R.id.vivo_music_play, inputs.playbackControlPlaying && !inputs.audioRouteMuted)
            setContentDescription(R.id.vivo_music_play, context.getString(choice.titleRes))
            setOnCheckedChangeResponse(R.id.vivo_music_play, RemoteViews.RemoteResponse.fromPendingIntent(
                checkedControl(AudioPlayerService.ACTION_VIVO_SET_PLAYING, song.stableKey(), 1)))
            setContentDescription(R.id.vivo_music_previous, context.getString(CommonR.string.player_previous))
            setContentDescription(R.id.vivo_music_next, context.getString(CommonR.string.player_next))
            setOnClickPendingIntent(R.id.vivo_music_previous, control(AudioPlayerService.ACTION_PREV, 2))
            setOnClickPendingIntent(R.id.vivo_music_next, control(AudioPlayerService.ACTION_NEXT, 3))
            val density = context.resources.displayMetrics.density
            // Match the native music surface on the verified firmware, including the camera-safe header.
            val horizontal = (27 * density).toInt()
            val top = ((if (cameraSafe) 27 else 16) * density).toInt()
            setViewPadding(R.id.vivo_music_card, horizontal, top, horizontal, (18 * density).toInt())
        }

    private fun ensureSeekIntents(songKey: String, durationMs: Long) {
        val identity = songKey to durationMs
        if (identity == seekIdentity) return
        seekIdentity = identity
        checkedIntents.clear()
        drawingControls.clear()
        seekIntents = if (durationMs <= 0L) emptyList() else (0..NOTIFICATION_SEEK_STEPS).map { step ->
            val intent = Intent(context, AudioPlayerService::class.java)
                .setAction(AudioPlayerService.ACTION_SEEK_TO)
                .setData(Uri.Builder().scheme("neriplayer-vivo-seek").authority("progress")
                    .appendPath(songKey).appendPath(step.toString()).build())
                .putExtra(AudioPlayerService.EXTRA_SEEK_POSITION_MS, notificationSeekPosition(durationMs, step)!!)
                .putExtra(AudioPlayerService.EXTRA_SEEK_SONG_KEY, songKey)
                .putExtra(AudioPlayerService.EXTRA_START_SOURCE, "vivo_music_seek")
            PendingIntent.getService(context, ID + 100 + step, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        // Cache target actions and descriptions; each card gets its own hierarchy via clone().
        seekTargets = seekIntents.mapIndexed { step, intent ->
            RemoteViews(context.packageName, R.layout.vivo_music_seek_target).apply {
                val position = checkNotNull(notificationSeekPosition(durationMs, step))
                setContentDescription(R.id.vivo_music_seek_target, context.getString(
                    R.string.vivo_music_seek_to, formatPlaybackWidgetTime(position),
                ))
                setOnClickPendingIntent(R.id.vivo_music_seek_target, intent)
            }
        }
    }

    private fun checkedControl(action: String, songKey: String, request: Int) = checkedIntents.getOrPut(action) { PendingIntent.getService(
        context, ID + request, Intent(context, AudioPlayerService::class.java).setAction(action)
            .setData(Uri.Builder().scheme("neriplayer-vivo-control").authority("checked").appendPath(songKey).build())
            .putExtra(AudioPlayerService.EXTRA_SEEK_SONG_KEY, songKey),
        // Only this explicit in-app component may receive the system-added EXTRA_CHECKED value.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ) }

    private fun drawingControl(action: String, songKey: String, request: Int) = drawingControls.getOrPut(action to songKey) {
        PendingIntent.getService(context, ID + request,
            Intent(context, AudioPlayerService::class.java).setAction(action)
                .setData(Uri.Builder().scheme("neriplayer-vivo-control").authority("toggle").appendPath(songKey).build())
                .putExtra(AudioPlayerService.EXTRA_SEEK_SONG_KEY, songKey),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun control(action: String, request: Int) = transportIntents.getOrPut(action) { PendingIntent.getService(context, ID + request,
        Intent(context, AudioPlayerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }

    fun clear() {
        if (supported) runCatching {
            // OriginOS can replace the untagged identity with VIVO_SUPERX_TAG.
            manager.activeNotifications.filter { it.id == ID }.forEach {
                manager.cancel(it.tag, it.id)
            }
            manager.cancel(ID)
        }.onFailure { NPLogger.w("NERI-VivoIsland", "Music island cancellation failed", it) }
        published = false
        lastInputs = null
        pendingControls = null
        seekIdentity = null
        seekIntents = emptyList()
        seekTargets = emptyList()
        checkedIntents.clear()
        drawingControls.clear()
        progressMotion.clear()
        if (Build.VERSION.SDK_INT >= 35) animatedCard.clear()
        handler.removeCallbacks(rollback)
    }
}
