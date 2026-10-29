package moe.ouom.neriplayer.core.player.service.presentation

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.CheckBox
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.remote.core.RcPlatformServices
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.ConditionalOperations
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.core.operations.layout.managers.ColumnLayout
import androidx.compose.remote.core.operations.layout.managers.RowLayout
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.semantics.AccessibleComponent
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.*
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.actions.Action
import androidx.compose.remote.creation.actions.HostAction
import androidx.compose.remote.creation.actions.ValueFloatChange
import androidx.compose.remote.creation.actions.ValueFloatExpressionChange
import androidx.compose.remote.creation.modifiers.RecordingModifier
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import moe.ouom.neriplayer.feature.player.R
import moe.ouom.neriplayer.core.player.presentation.widget.formatPlaybackWidgetTime
import moe.ouom.neriplayer.core.player.service.notification.notificationSeekPosition
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.core.player.metadata.ExternalLyricWindow

/** Keeps the existing XML design; only motion and hit regions are drawn by the system renderer. */
@RequiresApi(35)
@Suppress("RestrictedApi")
internal class VivoMusicAnimatedCard(private val context: Context) {
    fun prepareBackground(background: Bitmap, cameraSafe: Boolean = false, now: Long = SystemClock.elapsedRealtime()) =
        backdropTransitions.getOrPut(cameraSafe) { VivoMusicBackdropTransition() }.update(background, now)
    private data class Surface(
        val key: PlaybackServiceNotificationRenderInputs,
        val bitmap: Bitmap,
        val bounds: Map<Int, RectF>,
        val icons: Map<Int, List<Bitmap>>,
        val elapsedBaseline: Float,
        val elapsedSize: Float,
        val configuration: Int,
        val descriptions: Map<Int, String>,
        val lyricView: TextView,
    )
    private val surfaces = mutableMapOf<Boolean, Surface>()
    private val encodedImages = IdentityHashMap<Bitmap, ByteArray>()
    private val backdropTransitions = mutableMapOf<Boolean, VivoMusicBackdropTransition>()
    private val backdropFrames = mutableMapOf<Boolean, VivoMusicBackdropTransition.Frame>()
    private val lyricTransitions = mutableMapOf<Boolean, VivoMusicLyricTransition>()
    private val lyricFrames = mutableMapOf<Boolean, VivoMusicLyricTransition.Frame>()
    private data class ContentMotion(val cover: VivoMusicLayerMotion, val title: VivoMusicLayerMotion,
        val artist: VivoMusicLayerMotion, var songKey: String? = null)
    private val contentMotions = mutableMapOf<Boolean, ContentMotion>()
    private val contentFrames = mutableMapOf<Boolean, List<VivoMusicLayerMotion.Frame>>()
    private val playMotions = mutableMapOf<Boolean, VivoMusicToggleMotion>()
    fun previewPlaying(playing: Boolean, now: Long) { playMotions.values.forEach { it.update(playing, now) } }
    private val platform = object : RcPlatformServices by RcPlatformServices.None {
        override fun getImageWidth(image: Any) = (image as Bitmap).width
        override fun getImageHeight(image: Any) = (image as Bitmap).height
        override fun isAlpha8Image(image: Any) = (image as Bitmap).config == Bitmap.Config.ALPHA_8
        override fun imageToByteArray(image: Any): ByteArray = encodedImages.getOrPut(image as Bitmap) {
            ByteArrayOutputStream().use { output ->
                // Opaque album backdrops need bounded payloads even at zero blur. UI layers
                // retain lossless alpha; crossfades still animate the host layer's opacity.
                val format = if (image.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(image.compress(format, 92, output))
                output.toByteArray()
            }
        }
    }

    fun render(
        xml: RemoteViews,
        inputs: PlaybackServiceNotificationRenderInputs,
        background: (Int, Int) -> VivoMusicBackdropTransition.Frame,
        cameraSafe: Boolean,
        motion: VivoMusicProgressMotion.Frame,
        actions: Map<Int, PendingIntent>,
        seeks: List<PendingIntent>,
        now: Long = SystemClock.elapsedRealtime(),
        lyrics: ExternalLyricWindow? = null,
    ): RemoteViews {
        // Progress-only changes do not inflate XML, redraw artwork, or compress bitmaps again.
        val key = inputs.copy(positionMs = 0L, backgroundArtwork = null, lyricLine = null, playbackSpeed = 0f, positionUpdatedAtMs = 0L,
            text = "", lyricState = StatusBarLyricNotificationState(false, null))
        val surface = surfaces[cameraSafe]?.takeIf {
            it.key == key && it.configuration == context.resources.configuration.hashCode()
        } ?: createSurface(xml, key).also { next ->
            surfaces.put(cameraSafe, next)?.let { old ->
                (listOf(old.bitmap) + old.icons.values.flatten()).forEach { encodedImages.remove(it) }
                if (old.bitmap.width != next.bitmap.width || old.bitmap.height != next.bitmap.height ||
                    old.configuration != next.configuration) {
                    lyricTransitions.remove(cameraSafe)
                    contentMotions.remove(cameraSafe)
                }
            }
        }
        val width = surface.bitmap.width
        val height = surface.bitmap.height
        val backdrop = background(width, height)
        backdropFrames[cameraSafe] = backdrop
        val song = checkNotNull(inputs.song)
        val songKey = song.stableKey()
        val lyricLine = if (lyrics == null) inputs.lyricLine?.takeIf { it.isNotBlank() } else lyrics.current
        val lyricText = lyricLine ?: song.displayArtist()
        val speed = inputs.playbackSpeed.takeIf { inputs.playbackControlPlaying && !inputs.audioRouteMuted && it > 0f }
        val nextLyric = if (speed != null && lyrics?.nextPositionMs != null) lyrics.next ?: song.displayArtist() else null
        val boundaryMs = if (speed != null && lyrics?.nextPositionMs != null)
            (lyrics.nextPositionMs - lyrics.positionMs) / speed else 0f
        val lyricDurationMs = if (speed != null && lyrics != null)
            minOf(VivoMusicLyricTransition.DURATION_MS.toFloat(), lyrics.intervalMs / speed / 2f) else 0f
        val padding = (8 * context.resources.displayMetrics.density).roundToInt()
        val lyric = lyricTransitions.getOrPut(cameraSafe) {
            VivoMusicLyricTransition(context.resources.displayMetrics.density)
        }.prepare(songKey, lyricText, nextLyric, boundaryMs, lyricDurationMs,
            surface.lyricView.currentTextColor) { text ->
            val view = surface.lyricView
            view.text = text
            val rect = surface.bounds.getValue(R.id.vivo_music_artist)
            view.measure(View.MeasureSpec.makeMeasureSpec(rect.width().roundToInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rect.height().roundToInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            Bitmap.createBitmap(view.width + 2 * padding, view.height + 2 * padding, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { translate(padding.toFloat(), padding.toFloat()); view.draw(this) }
            }
        }
        lyricFrames[cameraSafe] = lyric
        val density = context.resources.displayMetrics.density
        val content = contentMotions.getOrPut(cameraSafe) { ContentMotion(
            VivoMusicLayerMotion(CONTENT_DURATION_MS, 0.03f, 0f),
            VivoMusicLayerMotion(CONTENT_DURATION_MS, 0f, 3f * density),
            VivoMusicLayerMotion(CONTENT_DURATION_MS, 0f, 3f * density)) }
        fun crop(id: Int): Bitmap {
            val rect = surface.bounds.getValue(id)
            return Bitmap.createBitmap(surface.bitmap, rect.left.toInt(), rect.top.toInt(), rect.width().toInt(), rect.height().toInt())
        }
        val coverMotion = content.cover.update(inputs.artwork ?: "default-cover", now) { crop(R.id.vivo_music_artwork) }
        val titleMotion = content.title.update(songKey to song.name, now) { crop(R.id.vivo_music_title) }
        val currentGlyph = (lyric.from ?: lyric.to).levels.first()
        val artistMotion = content.artist.update(songKey to lyricText, now, animate = content.songKey != songKey) { currentGlyph }
        content.songKey = songKey
        contentFrames[cameraSafe] = listOf(coverMotion, titleMotion, artistMotion)
        val playMotion = playMotions.getOrPut(cameraSafe) { VivoMusicToggleMotion() }
            .update(inputs.playbackControlPlaying && !inputs.audioRouteMuted, now)
        val retained = java.util.Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>()).apply {
            surfaces.values.forEach { add(it.bitmap); addAll(it.icons.values.flatten()) }
            backdropFrames.values.forEach { add(it.from); add(it.to) }
            lyricFrames.values.forEach { addAll(it.bitmaps) }
            contentFrames.values.flatten().forEach { addAll(it.bitmaps) }
        }
        encodedImages.keys.retainAll(retained)
        val writer = RemoteComposeWriter(width, height, "NeriPlayer music", 6, 0, platform)
        val calendar = Calendar.getInstance()
        val anchor = calendar.get(Calendar.MINUTE) * 60f + calendar.get(Calendar.SECOND) + calendar.get(Calendar.MILLISECOND) / 1000f
        val preparationMs = (SystemClock.elapsedRealtime() - now).coerceAtLeast(0L)
        val lyricPreparationMs = (SystemClock.elapsedRealtime() - (lyrics?.sampledAtMs?.takeIf { it > 0L } ?: now)).coerceAtLeast(0L)
        val startedAt = writer.addFloatConstant(anchor)
        val from = writer.addFloatConstant(motion.from)
        val target = writer.addFloatConstant(motion.target)
        val frozen = writer.addFloatConstant(motion.from)
        // Expression-change actions only copy registered expressions, not float constants.
        val frozenValue = writer.floatExpression(frozen, 0f, ADD)
        val running = writer.addFloatConstant(1f)
        val seconds = writer.addFloatConstant((motion.remainingMs / 1000f).coerceAtLeast(0.001f))
        val restartRequested = writer.addFloatConstant(0f)
        val initialized = writer.addFloatConstant(0f)
        val hostStartedAt = writer.addFloatConstant(0f)
        val playFrom = writer.addFloatConstant(playMotion.from)
        val playClicked = writer.addFloatConstant(0f)
        val playStartedAt = writer.addFloatConstant(anchor)
        val playRestart = writer.addFloatConstant(0f)
        val localChecked = surface.icons.keys.filter {
            it == R.id.vivo_music_play || (it == R.id.vivo_music_favorite && !inputs.interactiveFavorite)
        }.associateWith { id -> writer.addFloatConstant(if (if (id == R.id.vivo_music_play)
            inputs.playbackControlPlaying && !inputs.audioRouteMuted else inputs.favorite) 1f else 0f) }
        writer.startRoot()
        writer.startBox(RecordingModifier().fillMaxSize(), BoxLayout.START, BoxLayout.TOP)
        writer.startCanvas(RecordingModifier().fillMaxSize())
        // Root scaling changes paint coordinates but Android 16 still hit-tests in host pixels.
        // Native weighted layout and drawing share these viewport proportions; glyphs stay square.
        val sx = writer.floatExpression(RemoteContext.FLOAT_WINDOW_WIDTH, width.toFloat(), DIV)
        val sy = writer.floatExpression(RemoteContext.FLOAT_WINDOW_HEIGHT, height.toFloat(), DIV)
        val uiScale = writer.floatExpression(sx, sy, MIN)
        fun x(value: Float) = writer.floatExpression(value, sx, MUL)
        fun y(value: Float) = writer.floatExpression(value, sy, MUL)
        fun sized(value: Float) = writer.floatExpression(value, uiScale, MUL)
        // Expressions must belong to a painted canvas so host click actions can evaluate them.
        val clock = writer.floatExpression(RemoteComposeWriter.TIME_IN_CONTINUOUS_SEC)
        // Start a clicked curve at its first painted frame, not the previous frame's stale clock.
        writer.conditionalOperations(ConditionalOperations.TYPE_EQ, restartRequested, 1f)
        writer.startRunActions()
        ValueFloatExpressionChange(Utils.idFromNan(startedAt), Utils.idFromNan(clock)).write(writer)
        ValueFloatChange(Utils.idFromNan(restartRequested), 0f).write(writer)
        writer.endRunActions()
        writer.endConditionalOperations()
        writer.conditionalOperations(ConditionalOperations.TYPE_EQ, playRestart, 1f)
        writer.startRunActions()
        ValueFloatExpressionChange(Utils.idFromNan(playStartedAt), Utils.idFromNan(clock)).write(writer)
        ValueFloatChange(Utils.idFromNan(playRestart), 0f).write(writer)
        writer.endRunActions()
        writer.endConditionalOperations()
        // Reapply can retain the old canvas clock even when it hasn't drawn during delivery.
        // Initialize on the first actual paint, using only local value actions (no player commands).
        val hostClock = writer.floatExpression(RemoteContext.FLOAT_ANIMATION_TIME)
        writer.conditionalOperations(ConditionalOperations.TYPE_EQ, initialized, 0f)
        writer.startRunActions()
        ValueFloatExpressionChange(Utils.idFromNan(hostStartedAt), Utils.idFromNan(hostClock)).write(writer)
        ValueFloatChange(Utils.idFromNan(initialized), 1f).write(writer)
        writer.endRunActions()
        writer.endConditionalOperations()
        val hostElapsed = writer.floatExpression(hostClock, hostStartedAt, SUB, 0f, MAX)
        val backgroundAlpha = if (backdrop.from === backdrop.to) 1f else writer.floatExpression(
            backdrop.alpha, hostElapsed, VivoMusicBackdropTransition.DURATION_MS / 1000f, DIV, ADD, 1f, MIN)
        // Lyrics use the playback deadline, including preparation/delivery time. Reapply must
        // never grant another entrance animation after the first syllable has already started.
        // Keep animation-time in the dependency graph: continuous wall-clock expressions
        // alone do not request every display frame on a paused OriginOS host.
        val documentElapsed = writer.floatExpression(clock, anchor, SUB, 5400f, ADD, 3600f, MOD, 1800f, SUB, 0f, MAX,
            hostClock, 0f, MUL, ADD)
        val lyricTime = if (lyric.from == null) 1f else writer.floatExpression(
            documentElapsed, 1000f, MUL, lyricPreparationMs.toFloat(), ADD, lyric.boundaryMs, SUB,
            lyric.durationMs, ADD, lyric.durationMs, DIV, 0f, MAX, 1f, MIN)
        val lyricT = writer.floatExpression(lyricTime, lyricTime, MUL, 3f, 2f, lyricTime, MUL, SUB, MUL)
        fun contentTime(frame: VivoMusicLayerMotion.Frame): Float {
            if (frame.from == null) return 1f
            val time = writer.floatExpression(frame.progress, hostElapsed, CONTENT_DURATION_MS / 1000f, DIV, ADD, 1f, MIN)
            return writer.floatExpression(time, time, MUL, 3f, 2f, time, MUL, SUB, MUL)
        }
        val coverT = contentTime(coverMotion)
        val titleT = contentTime(titleMotion)
        val artistT = contentTime(artistMotion)
        val playInitialTime = writer.floatExpression(playMotion.progress, documentElapsed, preparationMs / 1000f, ADD,
            VivoMusicToggleMotion.DURATION_MS / 1000f, DIV, ADD, 1f, MIN)
        val playClickTime = writer.floatExpression(clock, playStartedAt, SUB, 5400f, ADD, 3600f, MOD, 1800f, SUB,
            VivoMusicToggleMotion.DURATION_MS / 1000f, DIV, 0f, MAX, 1f, MIN)
        val playTime = writer.floatExpression(playInitialTime, playClicked, playClickTime, playInitialTime, SUB, MUL, ADD)
        val playT = writer.floatExpression(playTime, playTime, MUL, 3f, 2f, playTime, MUL, SUB, MUL)
        val playValue = writer.floatExpression(localChecked.getValue(R.id.vivo_music_play), playFrom, SUB, playT, MUL, playFrom, ADD)
        // The host clock wraps once per hour. Seek and playback must continue across that boundary.
        val elapsed = writer.floatExpression(clock, startedAt, SUB, 5400f, ADD, 3600f, MOD, 1800f, SUB, 0f, MAX)
        val t = writer.floatExpression(elapsed, seconds, DIV, 0f, MAX, 1f, MIN)
        val eased = writer.floatExpression(t, t, MUL, 3f, 2f, t, MUL, SUB, MUL)
        val progressSpeed = if (motion.playing && inputs.durationMs > 0L) 1000f / inputs.durationMs else 0f
        val animatedFraction = writer.floatExpression(target, from, SUB, eased, MUL, from, ADD,
            elapsed, progressSpeed, MUL, ADD, 0f, MAX, 1f, MIN)
        val fraction = writer.floatExpression(frozen, running, animatedFraction, frozen, SUB, MUL, ADD)
        val toggled = localChecked.mapValues { (_, checked) -> writer.floatExpression(1f, checked, SUB) }
        writer.endCanvas()
        fun described(modifier: RecordingModifier, description: String?): RecordingModifier {
            if (description != null) {
                val label = writer.addText(description)
                modifier.then(RecordingModifier.Element { instructions ->
                    instructions.addSemanticsModifier(label, AccessibleComponent.Role.BUTTON.ordinal.toByte(),
                        0, 0, AccessibleComponent.Mode.SET.ordinal, true, true)
                })
            }
            return modifier
        }
        fun hit(width: Float, vararg onClick: Action, description: String? = null) {
            if (width <= 0f) return
            val modifier = described(RecordingModifier().horizontalWeight(width).fillMaxHeight(), description)
            if (onClick.isNotEmpty()) modifier.onClick(*onClick)
            writer.startCanvas(modifier)
            writer.endCanvas()
        }
        val progress = surface.bounds.getValue(R.id.vivo_music_progress)
        val row = surface.bounds.getValue(R.id.vivo_music_progress_row)
        val controls = surface.bounds.getValue(R.id.vivo_music_controls)
        val headerHeight = if (inputs.durationMs > 0L) row.top else controls.top
        writer.startColumn(RecordingModifier().fillMaxSize(), ColumnLayout.START, ColumnLayout.TOP)
        writer.startCanvas(described(RecordingModifier().fillMaxWidth().verticalWeight(headerHeight)
            .onClick(HostAction(R.id.vivo_music_card)), "${inputs.song.name}, $lyricText"))
        writer.endCanvas()
        if (inputs.durationMs > 0L) {
            writer.startRow(RecordingModifier().fillMaxWidth().verticalWeight(row.height()), RowLayout.START, RowLayout.TOP)
            hit(progress.left)
            seeks.forEachIndexed { step, _ ->
                hit(progress.width() / seeks.size,
                    // Snapshot first, cancel the previous curve, then atomically initialize its replacement.
                    // The snapshot is separate from `from`, avoiding mutation of a running curve's own input.
                    ValueFloatExpressionChange(Utils.idFromNan(frozen), Utils.idFromNan(fraction)),
                    ValueFloatChange(Utils.idFromNan(running), 0f),
                    ValueFloatExpressionChange(Utils.idFromNan(from), Utils.idFromNan(frozenValue)),
                    ValueFloatExpressionChange(Utils.idFromNan(startedAt), Utils.idFromNan(clock)),
                    ValueFloatChange(Utils.idFromNan(restartRequested), 1f),
                    ValueFloatChange(Utils.idFromNan(seconds), VivoMusicProgressMotion.SEEK_DURATION_MS / 1000f),
                    ValueFloatChange(Utils.idFromNan(target), step.toFloat() / (seeks.size - 1)),
                    ValueFloatChange(Utils.idFromNan(running), 1f),
                    HostAction(SEEK_ACTION_ID + step), description = context.getString(R.string.vivo_music_seek_to,
                        formatPlaybackWidgetTime(checkNotNull(notificationSeekPosition(inputs.durationMs, step)))))
            }
            hit(width - progress.right)
            writer.endRow()
        }
        writer.startRow(RecordingModifier().fillMaxWidth().verticalWeight(controls.height()), RowLayout.START, RowLayout.TOP)
        var cursor = 0f
        for (id in listOf(R.id.vivo_music_app, R.id.vivo_music_previous, R.id.vivo_music_play,
            R.id.vivo_music_next, R.id.vivo_music_favorite)) {
            val rect = surface.bounds.getValue(id)
            hit((rect.left - cursor).coerceAtLeast(0f))
            val checked = localChecked[id]
            if (id == R.id.vivo_music_play && checked != null) {
                hit(rect.width(),
                    ValueFloatExpressionChange(Utils.idFromNan(playFrom), Utils.idFromNan(playValue)),
                    ValueFloatExpressionChange(Utils.idFromNan(playStartedAt), Utils.idFromNan(clock)),
                    ValueFloatChange(Utils.idFromNan(playClicked), 1f),
                    ValueFloatChange(Utils.idFromNan(playRestart), 1f),
                    ValueFloatExpressionChange(Utils.idFromNan(checked), Utils.idFromNan(toggled.getValue(id))), HostAction(id),
                    description = surface.descriptions[id])
            } else if (checked != null) {
                hit(rect.width(),
                    ValueFloatExpressionChange(Utils.idFromNan(checked), Utils.idFromNan(toggled.getValue(id))), HostAction(id),
                    description = surface.descriptions[id])
            } else hit(rect.width(), HostAction(id), description = surface.descriptions[id])
            cursor = rect.right
        }
        hit(width - cursor)
        writer.endRow()
        writer.startCanvas(RecordingModifier().fillMaxWidth().verticalWeight(height - controls.bottom))
        writer.endCanvas()
        writer.endColumn()
        // Paint above hit regions so Android's default click ripple cannot flash across the card.
        writer.startCanvas(RecordingModifier().fillMaxSize())
        fun drawBackdrop(bitmap: Bitmap) {
            val scale = writer.floatExpression(RemoteContext.FLOAT_WINDOW_WIDTH, bitmap.width.toFloat(), DIV,
                RemoteContext.FLOAT_WINDOW_HEIGHT, bitmap.height.toFloat(), DIV, MAX)
            val w = writer.floatExpression(bitmap.width.toFloat(), scale, MUL)
            val h = writer.floatExpression(bitmap.height.toFloat(), scale, MUL)
            val left = writer.floatExpression(RemoteContext.FLOAT_WINDOW_WIDTH, w, SUB, 2f, DIV)
            val top = writer.floatExpression(RemoteContext.FLOAT_WINDOW_HEIGHT, h, SUB, 2f, DIV)
            writer.drawBitmap(bitmap, left, top, writer.floatExpression(left, w, ADD), writer.floatExpression(top, h, ADD), null)
        }
        writer.rcPaint.setAlpha(1f).commit()
        drawBackdrop(backdrop.from)
        if (backdrop.from !== backdrop.to) {
            writer.rcPaint.setAlpha(backgroundAlpha).commit()
            drawBackdrop(backdrop.to)
            writer.rcPaint.setAlpha(1f).commit()
        }
        fun staticPart(id: Int, left: Float, centerY: Float) {
            val rect = surface.bounds.getValue(id)
            val w = sized(rect.width())
            val h = sized(rect.height())
            val top = writer.floatExpression(centerY, h, 2f, DIV, SUB)
            writer.drawScaledBitmap(surface.bitmap, rect.left, rect.top, rect.right, rect.bottom,
                left, top, writer.floatExpression(left, w, ADD), writer.floatExpression(top, h, ADD),
                RemoteComposeWriter.IMAGE_SCALE_FILL_BOUNDS, 1f, null)
        }
        val cover = surface.bounds.getValue(R.id.vivo_music_artwork)
        fun layer(bitmap: Bitmap, left: Float, centerY: Float, visibility: Float,
            scaleDelta: Float, travel: Float, incoming: Boolean, centered: Boolean) {
            val phase = writer.floatExpression(1f, visibility, SUB)
            val scale = writer.floatExpression(1f, scaleDelta, phase, MUL, SUB)
            val w = writer.floatExpression(sized(bitmap.width.toFloat()), scale, MUL)
            val h = writer.floatExpression(sized(bitmap.height.toFloat()), scale, MUL)
            val actualLeft = if (centered) writer.floatExpression(left, sized(bitmap.width.toFloat()), w, SUB, 2f, DIV, ADD) else left
            val top = writer.floatExpression(centerY, h, 2f, DIV, SUB,
                sized(travel), phase, MUL, if (incoming) 1f else -1f, MUL, ADD)
            writer.rcPaint.setAlpha(visibility).commit()
            writer.drawBitmap(bitmap, actualLeft, top, writer.floatExpression(actualLeft, w, ADD),
                writer.floatExpression(top, h, ADD), null)
        }
        fun contentLayer(frame: VivoMusicLayerMotion.Frame, t: Float, left: Float, centerY: Float,
            motion: VivoMusicLayerMotion) {
            frame.from?.let { layer(it, left, centerY, writer.floatExpression(1f, t, SUB),
                motion.scaleDelta, motion.travel, false, motion.travel == 0f) }
            layer(frame.to, left, centerY, t, motion.scaleDelta, motion.travel, true, motion.travel == 0f)
            writer.rcPaint.setAlpha(1f).commit()
        }
        contentLayer(coverMotion, coverT, x(cover.left), y(cover.centerY()), content.cover)
        val title = surface.bounds.getValue(R.id.vivo_music_title)
        contentLayer(titleMotion, titleT, writer.floatExpression(x(cover.left), sized(title.left - cover.left), ADD),
            writer.floatExpression(y(cover.centerY()), sized(title.centerY() - cover.centerY()), ADD), content.title)
        val lyricRect = surface.bounds.getValue(R.id.vivo_music_artist)
        val lyricLeft = writer.floatExpression(x(cover.left), sized(lyricRect.left - cover.left - padding), ADD)
        val lyricY = writer.floatExpression(y(cover.centerY()), sized(lyricRect.centerY() - cover.centerY()), ADD)
        fun drawLyric(glyph: VivoMusicLyricTransition.Glyph, entering: Boolean) {
            val visibility = if (entering) lyricT else writer.floatExpression(1f, lyricT, SUB)
            val phase = writer.floatExpression(1f, visibility, SUB)
            val scale = writer.floatExpression(1f, VivoMusicLyricTransition.SCALE_DELTA, phase, MUL, SUB)
            val travel = sized(VivoMusicLyricTransition.TRAVEL_DP * context.resources.displayMetrics.density)
            val offset = writer.floatExpression(phase, travel, MUL, if (entering) 1f else -1f, MUL)
            val bitmap = glyph.levels.first()
            val w = writer.floatExpression(sized(bitmap.width.toFloat()), scale, MUL)
            val h = writer.floatExpression(sized(bitmap.height.toFloat()), scale, MUL)
            val top = writer.floatExpression(lyricY, offset, ADD, h, 2f, DIV, SUB,
                sized(content.artist.travel), 1f, artistT, SUB, MUL, ADD)
            val layers = if (lyric.from == null) glyph.levels.take(1) else glyph.levels
            for ((level, layer) in layers.withIndex()) {
                val weight = writer.floatExpression(1f, phase, (VivoMusicLyricTransition.BLUR_LEVELS - 1).toFloat(),
                    MUL, level.toFloat(), SUB, ABS, SUB, 0f, MAX)
                writer.rcPaint.setAlpha(writer.floatExpression(visibility, weight, MUL, artistT, MUL)).commit()
                writer.drawBitmap(layer, lyricLeft, top, writer.floatExpression(lyricLeft, w, ADD),
                    writer.floatExpression(top, h, ADD), null)
            }
        }
        writer.save()
        writer.clipRect(lyricLeft,
            writer.floatExpression(lyricY, sized(lyricRect.height() / 2 + 2 * context.resources.displayMetrics.density), SUB),
            RemoteContext.FLOAT_WINDOW_WIDTH,
            writer.floatExpression(lyricY, sized(lyricRect.height() / 2 + 4 * context.resources.displayMetrics.density), ADD))
        artistMotion.from?.let { layer(it, lyricLeft, lyricY, writer.floatExpression(1f, artistT, SUB),
            0f, content.artist.travel, false, false) }
        lyric.from?.let { drawLyric(it, false) }
        drawLyric(lyric.to, true)
        writer.restore()
        writer.rcPaint.setAlpha(1f).commit()
        if (inputs.durationMs > 0L) {
            val duration = surface.bounds.getValue(R.id.vivo_music_duration)
            staticPart(R.id.vivo_music_duration, writer.floatExpression(x(duration.right), sized(duration.width()), SUB),
                writer.floatExpression(y(row.centerY()), sized(duration.centerY() - row.centerY()), ADD))
        }
        surface.icons.forEach { (id, icons) ->
            val rect = surface.bounds.getValue(id)
            val checked = if (id == R.id.vivo_music_play) playValue else localChecked[id]
            val w = sized(rect.width())
            val h = sized(rect.height())
            val left = writer.floatExpression(x(rect.centerX()), w, 2f, DIV, SUB)
            val top = writer.floatExpression(y(rect.centerY()), h, 2f, DIV, SUB)
            val right = writer.floatExpression(left, w, ADD)
            val bottom = writer.floatExpression(top, h, ADD)
            if (checked == null) {
                writer.drawBitmap(icons[if (icons.size == 1) 0 else if (inputs.favorite) 1 else 0], left, top, right, bottom, null)
            } else {
                for (state in 0..1) {
                    val alpha = if (state == 0) writer.floatExpression(1f, checked, SUB) else checked
                    val inset = if (id == R.id.vivo_music_play) writer.floatExpression(1f, alpha, SUB, 0.03f, MUL) else 0f
                    val dx = writer.floatExpression(w, inset, MUL)
                    val dy = writer.floatExpression(h, inset, MUL)
                    writer.rcPaint.setAlpha(alpha).commit()
                    writer.drawBitmap(icons[state], writer.floatExpression(left, dx, ADD), writer.floatExpression(top, dy, ADD),
                        writer.floatExpression(right, dx, SUB), writer.floatExpression(bottom, dy, SUB), null)
                }
                writer.rcPaint.setAlpha(1f).commit()
            }
        }
        if (inputs.durationMs > 0L) {
            writer.rcPaint.setColor(0xff343638.toInt()).commit()
            val radius = sized(progress.height() / 2)
            val top = writer.floatExpression(y(progress.centerY()), radius, SUB)
            val bottom = writer.floatExpression(y(progress.centerY()), radius, ADD)
            writer.drawRoundRect(x(progress.left), top, x(progress.right), bottom, radius, radius)
            val right = writer.floatExpression(fraction, x(progress.width()), MUL, x(progress.left), ADD)
            writer.rcPaint.setColor(0xffe8eaed.toInt()).commit()
            writer.drawRoundRect(x(progress.left), top, right, bottom, radius, radius)
            val elapsedBounds = surface.bounds.getValue(R.id.vivo_music_elapsed)
            writer.rcPaint.setColor(0xffb8bdc4.toInt()).setTextSize(sized(surface.elapsedSize)).commit()
            val elapsedText = formatPlaybackWidgetTime(inputs.positionMs.coerceIn(0L, inputs.durationMs)).padStart(5, '0')
            writer.drawTextRun(elapsedText, 0, elapsedText.length, 0, elapsedText.length,
                x(elapsedBounds.left), writer.floatExpression(y(row.centerY()), sized(surface.elapsedBaseline - row.centerY()), ADD), false)
        }
        writer.endCanvas()
        writer.endBox()
        writer.endRoot()
        return RemoteViews(RemoteViews.DrawInstructions.Builder(listOf(writer.encodeToByteArray())).build()).apply {
            actions.forEach { (id, pending) -> setOnClickPendingIntent(id, pending) }
            seeks.forEachIndexed { step, pending -> setOnClickPendingIntent(SEEK_ACTION_ID + step, pending) }
        }
    }

    private fun createSurface(xml: RemoteViews, key: PlaybackServiceNotificationRenderInputs): Surface {
        val root = xml.apply(context, null)
        // Artwork transitions are independent of text, controls and progress.
        root.background = null
        root.findViewById<View>(R.id.vivo_music_backdrop).visibility = View.INVISIBLE
        val width = (360 * context.resources.displayMetrics.density).roundToInt()
        // Measure content first so higher-resolution artwork cannot inflate the card.
        val content = root.findViewById<View>(R.id.vivo_music_card)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(content.measuredHeight, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, root.measuredHeight)
        val ids = listOf(R.id.vivo_music_card, R.id.vivo_music_artwork, R.id.vivo_music_title, R.id.vivo_music_artist,
            R.id.vivo_music_duration, R.id.vivo_music_controls, R.id.vivo_music_app, R.id.vivo_music_previous, R.id.vivo_music_play,
            R.id.vivo_music_next, R.id.vivo_music_favorite, R.id.vivo_music_progress,
            R.id.vivo_music_progress_row, R.id.vivo_music_elapsed)
        val bounds = ids.associateWith { id ->
            val view = root.findViewById<View>(id)
            val rect = Rect(0, 0, view.width, view.height)
            (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, rect)
            RectF(rect)
        }
        val icons = listOf(R.id.vivo_music_app, R.id.vivo_music_previous, R.id.vivo_music_next,
            R.id.vivo_music_play, R.id.vivo_music_favorite).associateWith { id ->
            val box = root.findViewById<View>(id)
            // This is an offscreen snapshot, not a user interaction. RemoteViews.apply binds
            // real PendingIntents; sampling checked states must not send player commands.
            (box as? CheckBox)?.setOnCheckedChangeListener(null)
            (0..if (box is CheckBox) 1 else 0).map { selected ->
                if (box is CheckBox) box.isChecked = selected == 1
                box.jumpDrawablesToCurrentState()
                Bitmap.createBitmap(box.width, box.height, Bitmap.Config.ARGB_8888).also { box.draw(Canvas(it)) }
            }.also { box.visibility = View.INVISIBLE }
        }
        val elapsed = root.findViewById<TextView>(R.id.vivo_music_elapsed)
        val baseline = bounds.getValue(R.id.vivo_music_elapsed).top + elapsed.baseline
        elapsed.visibility = View.INVISIBLE
        val lyricView = root.findViewById<TextView>(R.id.vivo_music_artist)
        lyricView.visibility = View.INVISIBLE
        root.findViewById<View>(R.id.vivo_music_progress).visibility = View.INVISIBLE
        val bitmap = Bitmap.createBitmap(width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val descriptions = ids.mapNotNull { id -> root.findViewById<View>(id).contentDescription?.toString()?.let { id to it } }.toMap()
        (lyricView.parent as android.view.ViewGroup).removeView(lyricView)
        lyricView.visibility = View.VISIBLE
        return Surface(key, bitmap, bounds, icons, baseline, elapsed.textSize, context.resources.configuration.hashCode(), descriptions, lyricView)
    }

    fun clear() {
        surfaces.clear(); encodedImages.clear(); backdropTransitions.clear(); backdropFrames.clear()
        lyricTransitions.clear(); lyricFrames.clear()
        contentMotions.clear(); contentFrames.clear(); playMotions.clear()
    }

    companion object {
        const val CONTENT_DURATION_MS = 220L
        const val SEEK_ACTION_ID = 0x4e50fb00
        @ChecksSdkIntAtLeast(api = 35)
        fun supported() = Build.VERSION.SDK_INT >= 35 && RemoteViews.DrawInstructions.getSupportedVersion() >= 6
    }
}
