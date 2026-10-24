package moe.ouom.neriplayer.core.player.service.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance
import moe.ouom.neriplayer.core.player.presentation.widget.formatPlaybackWidgetTime
import moe.ouom.neriplayer.feature.player.R
import kotlin.math.roundToInt

/** Renders the production XML and background without binding any playback PendingIntents. */
class VivoMusicIslandPreview(context: Context) : AppCompatImageView(context) {
    private val artworkRenderer = VivoMusicCardArtwork(context)
    private val card = LayoutInflater.from(context).inflate(R.layout.notification_vivo_music, null)
    private var snapshot: Bitmap? = null

    init {
        adjustViewBounds = true
        scaleType = ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun update(song: SongItem?, artwork: Bitmap?, appearance: VivoMusicIslandAppearance) {
        artworkRenderer.update(artwork, appearance)
        card.findViewById<ImageView>(R.id.vivo_music_backdrop).setImageBitmap(artworkRenderer.background)
        card.findViewById<ImageView>(R.id.vivo_music_artwork).setImageBitmap(artworkRenderer.cover ?: artworkRenderer.appIcon)
        card.findViewById<ImageView>(R.id.vivo_music_app).setImageBitmap(artworkRenderer.appIcon)
        card.findViewById<TextView>(R.id.vivo_music_title).text = song?.displayName() ?: "NeriPlayer"
        card.findViewById<TextView>(R.id.vivo_music_artist).text = song?.displayArtist().orEmpty()
        card.findViewById<TextView>(R.id.vivo_music_elapsed).text = "00:00"
        card.findViewById<TextView>(R.id.vivo_music_duration).text = formatPlaybackWidgetTime(song?.durationMs ?: 0L)
        card.findViewById<CheckBox>(R.id.vivo_music_play).isChecked = true
        card.findViewById<ProgressBar>(R.id.vivo_music_progress).progress = 0
        val density = resources.displayMetrics.density
        val content = card.findViewById<View>(R.id.vivo_music_card)
        content.setPadding(
            (27 * density).toInt(), (16 * density).toInt(), (27 * density).toInt(), (18 * density).toInt())
        val width = (360 * density).roundToInt()
        // Background bitmap resolution must not determine the displayed card height.
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.findViewById<ImageView>(R.id.vivo_music_backdrop)
            .setImageBitmap(artworkRenderer.backgroundFor(width, content.measuredHeight))
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(content.measuredHeight, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, card.measuredHeight)
        val bitmap = snapshot?.takeIf { it.width == width && it.height == card.height }
            ?: Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888).also { snapshot = it }
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        card.draw(Canvas(bitmap))
        contentDescription = listOfNotNull(song?.displayName(), song?.displayArtist()).joinToString(", ")
        setImageBitmap(bitmap)
        invalidate()
    }
}
