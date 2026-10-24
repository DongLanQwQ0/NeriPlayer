package moe.ouom.neriplayer.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackWidgetResolutionTest {
    @Test fun `full resolution cover persists for launcher refreshes without the player`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cover = Bitmap.createBitmap(1536, 1536, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val state = PlaybackWidgetState.idle(context).copy(hasSong = true, artworkReady = true)
        PlaybackWidgetUpdater.updateFromPlaybackService(context, state, cover)
        val stored = BitmapFactory.decodeFile(File(context.filesDir, "playback_widget_artwork.png").absolutePath)
        assertNotNull(stored)
        assertEquals(1536 to 1536, stored.width to stored.height)
        assertEquals(Color.RED, stored.getPixel(256, 256))
    }

    @Test fun `large compact widget keeps native shape controls and a screen density backdrop after parceling`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cover = Bitmap.createBitmap(1536, 1536, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, 1536f, 1536f,
                    intArrayOf(0xFFB75968.toInt(), 0xFF653E23.toInt(), 0xFF1D3222.toInt()),
                    null, Shader.TileMode.CLAMP)
            })
        }
        val visuals = buildPlaybackWidgetVisuals(cover)
        val size = PlaybackWidgetSize(420, 240)
        val density = context.resources.displayMetrics.density
        val state = PlaybackWidgetState.idle(context).copy(
            title = "Inevitability", subtitle = "Tido Kang", hasSong = true, isPlaying = true, showPauseAction = true,
        )
        val views = PlaybackWidgetUpdater.buildRemoteViews(context, R.layout.widget_playback_2x2,
            state, visuals, size)
        val parcel = Parcel.obtain()
        val restored = try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        val root = restored.apply(context, FrameLayout(context))
        val width = (size.widthDp * density).roundToInt()
        val height = (size.heightDp * density).roundToInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val backdrop = (root.findViewById<ImageView>(R.id.widget_album_art).drawable as BitmapDrawable).bitmap
        assertEquals(width to height, backdrop.width to backdrop.height)
        val control = root.findViewById<ImageView>(R.id.widget_play_pause_background)
        assertFalse("Play control must be rasterized by the host", control.drawable is BitmapDrawable)
        assertEquals((36 * density).roundToInt(), control.width)
        val controlBitmap = Bitmap.createBitmap(control.width, control.height, Bitmap.Config.ARGB_8888)
        control.draw(Canvas(controlBitmap))
        assertEquals(visuals.primaryControlColor, controlBitmap.getPixel(control.width / 2, control.height / 2))
        assertEquals(0, Color.alpha(controlBitmap.getPixel(0, 0)))
        val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(snapshot))
        val file = File("build/reports/widget-resolution/compact.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
