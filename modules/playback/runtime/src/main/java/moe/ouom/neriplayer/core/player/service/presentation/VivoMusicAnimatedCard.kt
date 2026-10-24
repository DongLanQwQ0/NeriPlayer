package moe.ouom.neriplayer.core.player.service.presentation

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.widget.CheckBox
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.remote.core.RcPlatformServices
import androidx.compose.remote.core.operations.ConditionalOperations
import androidx.compose.remote.core.operations.RootContentBehavior
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

/** Keeps the existing XML design; only motion and hit regions are drawn by the system renderer. */
@RequiresApi(35)
@Suppress("RestrictedApi")
internal class VivoMusicAnimatedCard(private val context: Context) {
    private data class Surface(
        val key: PlaybackServiceNotificationRenderInputs,
        val bitmap: Bitmap,
        val bounds: Map<Int, RectF>,
        val icons: Map<Int, List<Bitmap>>,
        val elapsedBaseline: Float,
        val elapsedSize: Float,
        val configuration: Int,
        val descriptions: Map<Int, String>,
    )
    private val surfaces = mutableMapOf<Boolean, Surface>()
    private val encodedImages = IdentityHashMap<Bitmap, ByteArray>()
    private val platform = object : RcPlatformServices by RcPlatformServices.None {
        override fun getImageWidth(image: Any) = (image as Bitmap).width
        override fun getImageHeight(image: Any) = (image as Bitmap).height
        override fun isAlpha8Image(image: Any) = (image as Bitmap).config == Bitmap.Config.ALPHA_8
        override fun imageToByteArray(image: Any): ByteArray = encodedImages.getOrPut(image as Bitmap) {
            ByteArrayOutputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        }
    }

    fun render(
        xml: RemoteViews,
        inputs: PlaybackServiceNotificationRenderInputs,
        cameraSafe: Boolean,
        motion: VivoMusicProgressMotion.Frame,
        actions: Map<Int, PendingIntent>,
        seeks: List<PendingIntent>,
    ): RemoteViews {
        // Progress-only changes do not inflate XML, redraw artwork, or compress bitmaps again.
        val key = inputs.copy(positionMs = 0L)
        val surface = surfaces[cameraSafe]?.takeIf {
            it.key == key && it.configuration == context.resources.configuration.hashCode()
        } ?: createSurface(xml, key).also { next ->
            surfaces.put(cameraSafe, next)?.let { old ->
                (listOf(old.bitmap) + old.icons.values.flatten()).forEach { encodedImages.remove(it) }
            }
        }
        val width = surface.bitmap.width
        val height = surface.bitmap.height
        val writer = RemoteComposeWriter(width, height, "NeriPlayer music", 6, 0, platform)
        writer.setRootContentBehavior(RootContentBehavior.NONE, RootContentBehavior.ALIGNMENT_CENTER,
            RootContentBehavior.SIZING_SCALE, RootContentBehavior.SCALE_FILL_WIDTH)
        val now = Calendar.getInstance()
        val anchor = now.get(Calendar.MINUTE) * 60f + now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val startedAt = writer.addFloatConstant(anchor)
        val from = writer.addFloatConstant(motion.from)
        val target = writer.addFloatConstant(motion.target)
        val seconds = writer.addFloatConstant((motion.remainingMs / 1000f).coerceAtLeast(0.001f))
        val localChecked = surface.icons.keys.filterNot {
            it == R.id.vivo_music_favorite && inputs.interactiveFavorite
        }.associateWith { id -> writer.addFloatConstant(if (if (id == R.id.vivo_music_play)
            inputs.playbackControlPlaying && !inputs.audioRouteMuted else inputs.favorite) 1f else 0f) }
        writer.startRoot()
        writer.startBox(RecordingModifier().size(width, height), BoxLayout.START, BoxLayout.TOP)
        writer.startCanvas(RecordingModifier().size(width, height))
        // Expressions must belong to a painted canvas so host click actions can evaluate them.
        val clock = writer.floatExpression(RemoteComposeWriter.TIME_IN_CONTINUOUS_SEC)
        // The host clock wraps once per hour. Seek and playback must continue across that boundary.
        val elapsed = writer.floatExpression(clock, startedAt, SUB, 5400f, ADD, 3600f, MOD, 1800f, SUB, 0f, MAX)
        val t = writer.floatExpression(elapsed, seconds, DIV, 0f, MAX, 1f, MIN)
        val eased = writer.floatExpression(t, t, MUL, 3f, 2f, t, MUL, SUB, MUL)
        val speed = if (motion.playing && inputs.durationMs > 0L) 1000f / inputs.durationMs else 0f
        val fraction = writer.floatExpression(target, from, SUB, eased, MUL, from, ADD,
            elapsed, speed, MUL, ADD, 0f, MAX, 1f, MIN)
        val toggled = localChecked.mapValues { (_, checked) -> writer.floatExpression(1f, checked, SUB) }
        writer.endCanvas()
        fun hit(width: Float, height: Float, vararg onClick: Action, description: String? = null) {
            val modifier = RecordingModifier().width(width).height(height).onClick(*onClick)
            if (description != null) {
                val label = writer.addText(description)
                modifier.then(RecordingModifier.Element { instructions ->
                    instructions.addSemanticsModifier(label, AccessibleComponent.Role.BUTTON.ordinal.toByte(),
                        0, 0, AccessibleComponent.Mode.SET.ordinal, true, true)
                })
            }
            writer.startCanvas(modifier)
            writer.endCanvas()
        }
        val progress = surface.bounds.getValue(R.id.vivo_music_progress)
        val row = surface.bounds.getValue(R.id.vivo_music_progress_row)
        val headerHeight = if (inputs.durationMs > 0L) row.top else surface.bounds.getValue(R.id.vivo_music_app).top
        writer.startColumn(RecordingModifier().size(width, height), ColumnLayout.START, ColumnLayout.TOP)
        hit(width.toFloat(), headerHeight, HostAction(R.id.vivo_music_card), description = inputs.text)
        if (inputs.durationMs > 0L) {
            writer.startRow(RecordingModifier().width(width.toFloat()).height(row.height()), RowLayout.START, RowLayout.TOP)
            hit(progress.left, row.height())
            seeks.forEachIndexed { step, _ ->
                hit(progress.width() / seeks.size, row.height(),
                    ValueFloatExpressionChange(Utils.idFromNan(from), Utils.idFromNan(fraction)),
                    ValueFloatExpressionChange(Utils.idFromNan(startedAt), Utils.idFromNan(clock)),
                    ValueFloatChange(Utils.idFromNan(seconds), VivoMusicProgressMotion.SEEK_DURATION_MS / 1000f),
                    ValueFloatChange(Utils.idFromNan(target), step.toFloat() / (seeks.size - 1)),
                    HostAction(SEEK_ACTION_ID + step), description = context.getString(R.string.vivo_music_seek_to,
                        formatPlaybackWidgetTime(checkNotNull(notificationSeekPosition(inputs.durationMs, step)))))
            }
            writer.endRow()
        }
        val controlHeight = height - if (inputs.durationMs > 0L) row.bottom else headerHeight
        writer.startRow(RecordingModifier().width(width.toFloat()).height(controlHeight), RowLayout.START, RowLayout.TOP)
        var cursor = 0f
        for (id in listOf(R.id.vivo_music_app, R.id.vivo_music_previous, R.id.vivo_music_play,
            R.id.vivo_music_next, R.id.vivo_music_favorite)) {
            val rect = surface.bounds.getValue(id)
            hit((rect.left - cursor).coerceAtLeast(0f), controlHeight)
            val checked = localChecked[id]
            if (checked != null) {
                hit(rect.width(), controlHeight,
                    ValueFloatExpressionChange(Utils.idFromNan(checked), Utils.idFromNan(toggled.getValue(id))), HostAction(id),
                    description = surface.descriptions[id])
            } else hit(rect.width(), controlHeight, HostAction(id), description = surface.descriptions[id])
            cursor = rect.right
        }
        writer.endRow()
        writer.endColumn()
        // Paint above hit regions so Android's default click ripple cannot flash across the card.
        writer.startCanvas(RecordingModifier().size(width, height))
        writer.drawBitmap(surface.bitmap, 0f, 0f, width.toFloat(), height.toFloat(),
            "${inputs.song?.name.orEmpty()}, ${inputs.lyricLine ?: inputs.text}")
        surface.icons.forEach { (id, icons) ->
            val rect = surface.bounds.getValue(id)
            val checked = localChecked[id]
            if (checked == null) {
                writer.drawBitmap(icons[if (inputs.favorite) 1 else 0], rect.left, rect.top, rect.right, rect.bottom, null)
            } else {
                for (state in 0..1) {
                    writer.conditionalOperations(ConditionalOperations.TYPE_EQ, checked, state.toFloat())
                    writer.drawBitmap(icons[state], rect.left, rect.top, rect.right, rect.bottom, null)
                    writer.endConditionalOperations()
                }
            }
        }
        if (inputs.durationMs > 0L) {
            writer.rcPaint.setColor(0xff343638.toInt()).commit()
            writer.drawRoundRect(progress.left, progress.top, progress.right, progress.bottom, progress.height() / 2, progress.height() / 2)
            val right = writer.floatExpression(fraction, progress.width(), MUL, progress.left, ADD)
            writer.rcPaint.setColor(0xffe8eaed.toInt()).commit()
            writer.drawRoundRect(progress.left, progress.top, right, progress.bottom, progress.height() / 2, progress.height() / 2)
            val elapsedBounds = surface.bounds.getValue(R.id.vivo_music_elapsed)
            writer.rcPaint.setColor(0xffb8bdc4.toInt()).setTextSize(surface.elapsedSize).commit()
            val elapsedText = formatPlaybackWidgetTime(inputs.positionMs.coerceIn(0L, inputs.durationMs)).padStart(5, '0')
            writer.drawTextRun(elapsedText, 0, elapsedText.length, 0, elapsedText.length,
                elapsedBounds.left, surface.elapsedBaseline, false)
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
        val width = (360 * context.resources.displayMetrics.density).roundToInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, width, root.measuredHeight)
        val ids = listOf(R.id.vivo_music_card, R.id.vivo_music_app, R.id.vivo_music_previous, R.id.vivo_music_play,
            R.id.vivo_music_next, R.id.vivo_music_favorite, R.id.vivo_music_progress,
            R.id.vivo_music_progress_row, R.id.vivo_music_elapsed)
        val bounds = ids.associateWith { id ->
            val view = root.findViewById<View>(id)
            val rect = Rect(0, 0, view.width, view.height)
            (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, rect)
            RectF(rect)
        }
        val icons = listOf(R.id.vivo_music_play, R.id.vivo_music_favorite).associateWith { id ->
            val box = root.findViewById<CheckBox>(id)
            (0..1).map { selected ->
                box.isChecked = selected == 1
                box.jumpDrawablesToCurrentState()
                Bitmap.createBitmap(box.width, box.height, Bitmap.Config.ARGB_8888).also { box.draw(Canvas(it)) }
            }.also { box.visibility = View.INVISIBLE }
        }
        val elapsed = root.findViewById<TextView>(R.id.vivo_music_elapsed)
        val baseline = bounds.getValue(R.id.vivo_music_elapsed).top + elapsed.baseline
        elapsed.visibility = View.INVISIBLE
        root.findViewById<View>(R.id.vivo_music_progress).visibility = View.INVISIBLE
        val bitmap = Bitmap.createBitmap(width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val descriptions = ids.mapNotNull { id -> root.findViewById<View>(id).contentDescription?.toString()?.let { id to it } }.toMap()
        return Surface(key, bitmap, bounds, icons, baseline, elapsed.textSize, context.resources.configuration.hashCode(), descriptions)
    }

    fun clear() { surfaces.clear(); encodedImages.clear() }

    companion object {
        const val SEEK_ACTION_ID = 0x4e50fb00
        @ChecksSdkIntAtLeast(api = 35)
        fun supported() = Build.VERSION.SDK_INT >= 35 && RemoteViews.DrawInstructions.getSupportedVersion() >= 6
    }
}
