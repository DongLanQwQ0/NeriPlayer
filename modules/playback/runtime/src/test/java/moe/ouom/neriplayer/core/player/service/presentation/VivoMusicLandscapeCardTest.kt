package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w840dp-h390dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VivoMusicLandscapeCardTest {
    @Test fun `landscape card fills the system owned expanded width`() =
        assertFullWidth(Configuration.ORIENTATION_LANDSCAPE)

    @Test fun `portrait document still fills the width after a paused host rotates`() =
        assertFullWidth(Configuration.ORIENTATION_PORTRAIT)

    @Test
    @Config(qualifiers = "w880dp-h405dp-land-xxhdpi")
    fun `physical landscape viewport has no inset black side gutters`() =
        assertFullWidth(Configuration.ORIENTATION_LANDSCAPE, 1584, 499)

    private fun assertFullWidth(orientation: Int, hostWidth: Int = 660, hostHeight: Int = 208) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val documentContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            this.orientation = orientation
        })
        val bridge = VivoMusicIslandBridge(documentContext)
        bridge.javaClass.getDeclaredField("appearance").apply { isAccessible = true }
            .set(bridge, VivoMusicIslandAppearance(0, 0))
        val background = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val inputs = PlaybackServiceNotificationRenderInputs(
            SongItem(1, "Landscape card", "Artist", "Album", 0, 60_000, null), "Artist",
            false, false, false, false, false, StatusBarLyricNotificationState(false, null), null, null,
            positionMs = 12_000, backgroundArtwork = background)
        val open = PendingIntent.getBroadcast(context, 1, Intent("test.OPEN").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val card = bridge.javaClass.getDeclaredMethod("card", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            for (cameraSafe in listOf(false, true)) {
                val remote = card.invoke(bridge, inputs, open, cameraSafe, SystemClock.elapsedRealtime()) as RemoteViews
                val view = remote.apply(activity, null)
                activity.setContentView(view, ViewGroup.LayoutParams(hostWidth, hostHeight))
                view.measure(View.MeasureSpec.makeMeasureSpec(hostWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(hostHeight, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, hostWidth, hostHeight)
                val bitmap = Bitmap.createBitmap(hostWidth, hostHeight, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                for (x in listOf(0, 8, hostWidth - 9, hostWidth - 1)) {
                    val color = bitmap.getPixel(x, hostHeight / 2)
                    assertTrue("Album backdrop left a side gutter at $x", Color.alpha(color) == 255 && Color.blue(color) > 240)
                }
                val report = File("build/reports/vivo-landscape/$hostWidth-${if (cameraSafe) "camera" else "normal"}.png")
                report.parentFile!!.mkdirs()
                report.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { activity.finish(); bridge.clear() }
    }
}
