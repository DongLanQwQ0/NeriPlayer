package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
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
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VivoMusicContentAnimationTest {
    @Test fun `cover title artist and local play icon have real intermediate frames and latest clicks win`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun art(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val inputs = PlaybackServiceNotificationRenderInputs(
            SongItem(1, "AAAA", "AAAA", "Album", 0, 60000, null), "AAAA", false, false,
            false, false, false, StatusBarLyricNotificationState(false, null), art(Color.RED), null)
        val next = inputs.copy(song = inputs.song!!.copy(id = 2, name = "MMMMMMMM", artist = "MMMMMMMM"), artwork = art(Color.BLUE))
        val bridge = VivoMusicIslandBridge(context)
        val renderer = VivoMusicAnimatedCard(context)
        val background = art(Color.BLACK)
        val open = PendingIntent.getBroadcast(context, 1, Intent("test.OPEN").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val classic = bridge.javaClass.getDeclaredMethod("classicCard", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        fun document(data: PlaybackServiceNotificationRenderInputs) = renderer.render(
            classic.invoke(bridge, data, open, false) as RemoteViews, data, { _, _ -> renderer.prepareBackground(background) },
            false, VivoMusicProgressMotion.Frame(.2f, .2f, 0, false), emptyMap(), emptyList())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val view = document(inputs).apply(activity, null)
            val surface = (renderer.javaClass.getDeclaredField("surfaces").apply { isAccessible = true }.get(renderer) as Map<*, *>)[false]!!
            @Suppress("UNCHECKED_CAST")
            val bounds = surface.javaClass.getDeclaredField("bounds").apply { isAccessible = true }.get(surface) as Map<Int, RectF>
            val h = (surface.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }.get(surface) as Bitmap).height
            activity.setContentView(view, ViewGroup.LayoutParams(360, h))
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, h)
            fun frame(name: String): Bitmap = Bitmap.createBitmap(360, h, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
                val file = File("build/reports/vivo-content-animation/$name.png"); file.parentFile!!.mkdirs()
                file.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
            fun pixels(bitmap: Bitmap, id: Int): IntArray {
                val r = bounds.getValue(id)
                return IntArray(r.width().toInt() * r.height().toInt()).also {
                    bitmap.getPixels(it, 0, r.width().toInt(), r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
                }
            }
            fun wait(ms: Long) { Thread.sleep(ms); ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }
            val before = frame("before")
            document(next).reapply(activity, view)
            val first = frame("first")
            wait(80)
            val middle = frame("middle")
            wait(190)
            val after = frame("after")
            for (id in listOf(R.id.vivo_music_artwork, R.id.vivo_music_title, R.id.vivo_music_artist)) {
                assertFalse("$id never changed", pixels(before, id).contentEquals(pixels(after, id)))
                assertFalse("$id skipped intermediate motion", pixels(first, id).contentEquals(pixels(middle, id)))
                assertFalse("$id snapped to the end", pixels(middle, id).contentEquals(pixels(after, id)))
            }
            val button = bounds.getValue(R.id.vivo_music_play)
            fun tap() {
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, button.centerX(), button.centerY(), 0)
                    view.dispatchTouchEvent(event); event.recycle()
                }
            }
            tap()
            val clicked = frame("play-first")
            wait(65)
            val playMiddle = frame("play-middle")
            tap() // Reverse before completing: the second target must replace the first.
            val reversed = frame("play-reversed")
            val a = pixels(playMiddle, R.id.vivo_music_play)
            val b = pixels(reversed, R.id.vivo_music_play)
            assertTrue("Reversal flashed", a.indices.count { a[it] != b[it] } < a.size / 10)
            assertFalse("Play button did not animate", pixels(clicked, R.id.vivo_music_play).contentEquals(a))
            wait(180)
            assertArrayEquals("Latest pause state did not win", pixels(after, R.id.vivo_music_play), pixels(frame("play-latest"), R.id.vivo_music_play))
        } finally { activity.finish(); renderer.clear(); bridge.clear() }
    }
}
