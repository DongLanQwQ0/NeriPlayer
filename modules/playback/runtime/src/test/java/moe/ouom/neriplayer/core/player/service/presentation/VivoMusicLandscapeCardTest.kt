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
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.abs
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w840dp-h390dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VivoMusicLandscapeCardTest {
    @Test fun `short wide host centers a compact card without cropping its cover or controls`() =
        assertLayout(Configuration.ORIENTATION_LANDSCAPE)

    @Test fun `portrait document still fits after rotating a paused host without republishing`() =
        assertLayout(Configuration.ORIENTATION_PORTRAIT)

    @Test
    @Config(qualifiers = "w880dp-h405dp-land-xxhdpi")
    fun `physical landscape viewport keeps camera-safe content and touch targets inside its bounds`() =
        assertLayout(Configuration.ORIENTATION_LANDSCAPE, 1584, 499)

    private fun assertLayout(documentOrientation: Int, hostWidth: Int = 660, hostHeight: Int = 208) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val documentContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            orientation = documentOrientation
        })
        val bridge = VivoMusicIslandBridge(documentContext)
        bridge.javaClass.getDeclaredField("appearance").apply { isAccessible = true }
            .set(bridge, VivoMusicIslandAppearance(0, 0))
        fun solid(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val inputs = PlaybackServiceNotificationRenderInputs(
            SongItem(1, "Landscape card", "Artist", "Album", 0, 60_000, null), "Artist",
            false, false, false, false, false, StatusBarLyricNotificationState(false, null), solid(Color.RED), null,
            positionMs = 12_000, backgroundArtwork = solid(Color.BLUE))
        (bridge.javaClass.getDeclaredField("progressMotion").apply { isAccessible = true }
            .get(bridge) as VivoMusicProgressMotion).update("song", 60_000, 12_000, false, SystemClock.elapsedRealtime())
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
                shadowOf(Looper.getMainLooper()).idle()
                val bitmap = Bitmap.createBitmap(hostWidth, hostHeight, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val report = File("build/reports/vivo-landscape/$hostWidth-${if (cameraSafe) "camera" else "normal"}.png")
                report.parentFile!!.mkdirs()
                report.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val occupied = (0 until hostWidth).filter { Color.alpha(bitmap.getPixel(it, hostHeight / 2)) > 0 }
                assertTrue("Landscape card left no horizontal breathing room: ${occupied.first()}..${occupied.last()}",
                    occupied.first() >= 24 && occupied.last() < hostWidth - 24)
                assertTrue(abs(occupied.first() - (hostWidth - 1 - occupied.last())) <= 2)
                assertEquals("Inset card lost its own rounded corners", 0, Color.alpha(bitmap.getPixel(occupied.first() + 1, 1)))
                val red = buildList {
                    for (y in 0 until hostHeight) for (x in 0 until hostWidth) {
                        val color = bitmap.getPixel(x, y)
                        if (Color.red(color) > 220 && Color.green(color) < 40 && Color.blue(color) < 40) add(x to y)
                    }
                }
                val coverWidth = red.maxOf { it.first } - red.minOf { it.first } + 1
                val coverHeight = red.maxOf { it.second } - red.minOf { it.second } + 1
                assertTrue("Cover was clipped or stretched: $coverWidth x $coverHeight", abs(coverWidth - coverHeight) <= 2)
                assertTrue("Cover touches the top edge", red.minOf { it.second } >= 16)
                fun isProgress(color: Int) = color == 0xff343638.toInt() || color == 0xffe8eaed.toInt()
                val barY = (0 until hostHeight).maxBy { y -> (0 until hostWidth).count { isProgress(bitmap.getPixel(it, y)) } }
                val barXs = (0 until hostWidth).filter { isProgress(bitmap.getPixel(it, barY)) }
                val x = barXs.first() + (barXs.last() - barXs.first()) * 0.75f
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time, action, x, barY.toFloat(), 0)
                    view.dispatchTouchEvent(event)
                    event.recycle()
                }
                Thread.sleep(450)
                val after = Bitmap.createBitmap(hostWidth, hostHeight, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(after))
                val fraction = barXs.count { after.getPixel(it, barY) == 0xffe8eaed.toInt() }.toFloat() / barXs.size
                println("barY=$barY xs=${barXs.first()}..${barXs.last()} x=$x before=${barXs.count { bitmap.getPixel(it, barY) == 0xffe8eaed.toInt() }} afterFraction=$fraction")
                File("build/reports/vivo-landscape/after-touch.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertEquals("Centered progress hit area no longer matches its painted location", 0.75f, fraction, 0.05f)
                println("cameraSafe=$cameraSafe content=${occupied.first()}..${occupied.last()}, cover=${coverWidth}x$coverHeight")
            }
        } finally { activity.finish(); bridge.clear() }
    }
}
