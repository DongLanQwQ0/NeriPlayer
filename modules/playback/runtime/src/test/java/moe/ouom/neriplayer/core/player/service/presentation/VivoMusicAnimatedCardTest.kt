package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.feature.player.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VivoMusicAnimatedCardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val inputs = PlaybackServiceNotificationRenderInputs(
        SongItem(1L, "Production card", "Artist", "Album", 0L, 60_000L, null), "Artist",
        false, false, false, false, false, StatusBarLyricNotificationState(false, null), null, null,
        positionMs = 6_000L,
    )
    private val open get() = PendingIntent.getBroadcast(context, 1, Intent("test.OPEN").setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Test fun `production card animates seeks and reuses static artwork on progress ticks`() {
        val bridge = VivoMusicIslandBridge(context)
        val motion = bridge.javaClass.getDeclaredField("progressMotion").apply { isAccessible = true }
            .get(bridge) as VivoMusicProgressMotion
        motion.update("song", 60_000L, 6_000L, false, SystemClock.elapsedRealtime())
        fun card(data: PlaybackServiceNotificationRenderInputs): RemoteViews = bridge.javaClass
            .getDeclaredMethod("card", PlaybackServiceNotificationRenderInputs::class.java,
                PendingIntent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(bridge, data, open, false) as RemoteViews
        val xml = bridge.javaClass.getDeclaredMethod("classicCard", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(bridge, inputs, open, false) as RemoteViews
        val layout = xml.apply(context, null) as ViewGroup
        layout.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout.layout(0, 0, 360, layout.measuredHeight)
        val bar = layout.findViewById<View>(R.id.vivo_music_progress)
        val bounds = Rect(0, 0, bar.width, bar.height)
        layout.offsetDescendantRectToMyCoords(bar, bounds)
        val remote = card(inputs)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = remote.apply(activity, null)
        assertTrue("Production entry must use the remote drawing host", view.javaClass.name.contains("RemoteCompose"))
        activity.setContentView(view, ViewGroup.LayoutParams(360, layout.height))
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(layout.height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, layout.height)
        fun width(name: String): Int {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/reports/vivo-production-motion/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val result = (bounds.left until bounds.right).count { bitmap.getPixel(it, bounds.centerY()) == 0xffe8eaed.toInt() }
            if (name == "before") {
                fun textRows(id: Int): IntRange {
                    val text = layout.findViewById<View>(id)
                    val rect = Rect(0, 0, text.width, text.height)
                    layout.offsetDescendantRectToMyCoords(text, rect)
                    val rows = ((bounds.centerY() - 13)..(bounds.centerY() + 13)).filter { y ->
                        (rect.left until rect.right).any { x -> android.graphics.Color.red(bitmap.getPixel(x, y)) > 80 }
                    }
                    return rows.first()..rows.last()
                }
                assertEquals("Elapsed and duration labels lost their common baseline",
                    textRows(R.id.vivo_music_duration), textRows(R.id.vivo_music_elapsed))
            }
            println("$name=$result")
            bitmap.recycle()
            return result
        }
        fun tap(fraction: Float) {
            val now = SystemClock.uptimeMillis()
            val x = bounds.left + bounds.width() * fraction
            for (type in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, type, x, bounds.exactCenterY(), 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        val before = width("before")
        assertTrue("Production bar was not drawn: $before", before in 10..50)
        tap(0.8f)
        val first = width("first")
        // The framework RemoteClock uses real wall time, not Robolectric's looper clock.
        Thread.sleep(120)
        val middle = width("middle")
        Thread.sleep(400)
        val after = width("after")
        assertTrue("First frame jumped to its target: $before,$first,$middle,$after", first < after - 5)
        assertTrue("No intermediate rendered frame: $before,$first,$middle,$after", middle in (before + 3)..(after - 3))
        assertTrue("Seek did not settle: $after", after > bounds.width() * 0.7)
        tap(0.2f)
        Thread.sleep(160)
        val beforeRetarget = width("retarget-before")
        tap(0.95f)
        val firstRetarget = width("retarget-first")
        Thread.sleep(120)
        val middleRetarget = width("retarget-middle")
        Thread.sleep(400)
        val afterRetarget = width("retarget-after")
        assertTrue("Retarget did not retain its visible starting point",
            kotlin.math.abs(firstRetarget - beforeRetarget) < 15)
        assertTrue("Retarget had no intermediate frame", middleRetarget in (beforeRetarget + 2)..(afterRetarget - 2))
        val renderer = (bridge.javaClass.getDeclaredField("animatedCard\$delegate").apply { isAccessible = true }
            .get(bridge) as Lazy<*>).value!!
        fun cachedBitmap(): Any = (renderer.javaClass.getDeclaredField("surfaces").apply { isAccessible = true }
            .get(renderer) as Map<*, *>)[false]!!.let { surface ->
            surface.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }.get(surface)
        }
        val base = cachedBitmap()
        card(inputs.copy(positionMs = 7_000L))
        assertSame("A progress tick recompressed the static artwork", base, cachedBitmap())
        activity.finish()
        bridge.clear()
    }
}
