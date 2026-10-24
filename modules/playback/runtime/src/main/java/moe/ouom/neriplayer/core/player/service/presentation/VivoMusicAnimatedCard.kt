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
    ): RemoteViews {
        // Progress-only changes do not inflate XML, redraw artwork, or compress bitmaps again.
        val key = inputs.copy(positionMs = 0L, backgroundArtwork = null, lyricLine = null,
            text = "", lyricState = StatusBarLyricNotificationState(false, null))
        val surface = surfaces[cameraSafe]?.takeIf {
            it.key == key && it.configuration == context.resources.configuration.hashCode()
        } ?: createSurface(xml, key).also { next ->
            surfaces.put(cameraSafe, next)?.let { old ->
                (listOf(old.bitmap) + old.icons.values.flatten()).forEach { encodedImages.remove(it) }
                if (old.bitmap.width != next.bitmap.width || old.bitmap.height != next.bitmap.height ||
                    old.configuration != next.configuration) lyricTransitions.remove(cameraSafe)
            }
        }
        val width = surface.bitmap.width
        val height = surface.bitmap.height
        val backdrop = background(width, height)
        backdropFrames[cameraSafe] = backdrop
        val lyricLine = inputs.lyricLine?.takeIf { it.isNotBlank() }
        val lyricText = lyricLine ?: checkNotNull(inputs.song).displayArtist()
        val padding = (8 * context.resources.displayMetrics.density).roundToInt()
        val lyric = lyricTransitions.getOrPut(cameraSafe) {
            VivoMusicLyricTransition(context.resources.displayMetrics.density)
        }.update(checkNotNull(inputs.song).stableKey(), lyricText, lyricLine != null, now,
            surface.lyricView.currentTextColor) {
            val view = surface.lyricView
            view.text = lyricText
            val rect = surface.bounds.getValue(R.id.vivo_music_artist)
            view.measure(View.MeasureSpec.makeMeasureSpec(rect.width().roundToInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rect.height().roundToInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            Bitmap.createBitmap(view.width + 2 * padding, view.height + 2 * padding, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { translate(padding.toFloat(), padding.toFloat()); view.draw(this) }
            }
        }
        lyricFrames[cameraSafe] = lyric
        val retained = java.util.Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>()).apply {
            surfaces.values.forEach { add(it.bitmap); addAll(it.icons.values.flatten()) }
            backdropFrames.values.forEach { add(it.from); add(it.to) }
            lyricFrames.values.forEach { addAll(it.bitmaps) }
        }
        encodedImages.keys.retainAll(retained)
        val writer = RemoteComposeWriter(width, height, "NeriPlayer music", 6, 0, platform)
        val calendar = Calendar.getInstance()
        val anchor = calendar.get(Calendar.MINUTE) * 60f + calendar.get(Calendar.SECOND) + calendar.get(Calendar.MILLISECOND) / 1000f
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
        val lyricTime = if (lyric.from == null) 1f else writer.floatExpression(
            lyric.progress, hostElapsed, VivoMusicLyricTransition.DURATION_MS / 1000f, DIV, ADD, 1f, MIN)
        val lyricT = writer.floatExpression(lyricTime, lyricTime, MUL, 3f, 2f, lyricTime, MUL, SUB, MUL)
        // The host clock wraps once per hour. Seek and playback must continue across that boundary.
        val elapsed = writer.floatExpression(clock, startedAt, SUB, 5400f, ADD, 3600f, MOD, 1800f, SUB, 0f, MAX)
        val t = writer.floatExpression(elapsed, seconds, DIV, 0f, MAX, 1f, MIN)
        val eased = writer.floatExpression(t, t, MUL, 3f, 2f, t, MUL, SUB, MUL)
        val speed = if (motion.playing && inputs.durationMs > 0L) 1000f / inputs.durationMs else 0f
        val animatedFraction = writer.floatExpression(target, from, SUB, eased, MUL, from, ADD,
            elapsed, speed, MUL, ADD, 0f, MAX, 1f, MIN)
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
            if (checked != null) {
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
        staticPart(R.id.vivo_music_artwork, x(cover.left), y(cover.centerY()))
        val title = surface.bounds.getValue(R.id.vivo_music_title)
        staticPart(R.id.vivo_music_title, writer.floatExpression(x(cover.left), sized(title.left - cover.left), ADD),
            writer.floatExpression(y(cover.centerY()), sized(title.centerY() - cover.centerY()), ADD))
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
            val top = writer.floatExpression(lyricY, offset, ADD, h, 2f, DIV, SUB)
            val layers = if (lyric.from == null) glyph.levels.take(1) else glyph.levels
            for ((level, layer) in layers.withIndex()) {
                val weight = writer.floatExpression(1f, phase, (VivoMusicLyricTransition.BLUR_LEVELS - 1).toFloat(),
                    MUL, level.toFloat(), SUB, ABS, SUB, 0f, MAX)
                writer.rcPaint.setAlpha(writer.floatExpression(visibility, weight, MUL)).commit()
                writer.drawBitmap(layer, lyricLeft, top, writer.floatExpression(lyricLeft, w, ADD),
                    writer.floatExpression(top, h, ADD), null)
            }
        }
        writer.save()
        writer.clipRect(lyricLeft,
            writer.floatExpression(lyricY, sized(lyricRect.height() / 2 + 2 * context.resources.displayMetrics.density), SUB),
            RemoteContext.FLOAT_WINDOW_WIDTH,
            writer.floatExpression(lyricY, sized(lyricRect.height() / 2 + 4 * context.resources.displayMetrics.density), ADD))
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
            val checked = localChecked[id]
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
                    writer.rcPaint.setAlpha(if (state == 0) writer.floatExpression(1f, checked, SUB) else checked).commit()
                    writer.drawBitmap(icons[state], left, top, right, bottom, null)
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
    }

    companion object {
        const val SEEK_ACTION_ID = 0x4e50fb00
        @ChecksSdkIntAtLeast(api = 35)
        fun supported() = Build.VERSION.SDK_INT >= 35 && RemoteViews.DrawInstructions.getSupportedVersion() >= 6
    }
}
