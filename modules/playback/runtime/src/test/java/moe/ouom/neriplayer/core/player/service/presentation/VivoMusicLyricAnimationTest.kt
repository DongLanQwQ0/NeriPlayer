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
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.feature.player.R
import moe.ouom.neriplayer.core.player.metadata.ExternalLyricWindow
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
class VivoMusicLyricAnimationTest {
    @Test fun `production lyric entrance finishes on its timestamp and delayed delivery never postpones it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inputs = PlaybackServiceNotificationRenderInputs(
            SongItem(1, "Song", "Artist", "Album", 0, 60_000, null), "Artist", false, false,
            false, false, false, StatusBarLyricNotificationState(false, null), null, null, lyricLine = "AAAA",
            playbackSpeed = 1f).copy(playbackControlPlaying = true)
        val bridge = VivoMusicIslandBridge(context)
        val open = PendingIntent.getBroadcast(context, 1, Intent("test.OPEN").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val classic = bridge.javaClass.getDeclaredMethod("classicCard", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val renderer = VivoMusicAnimatedCard(context)
        val background = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        fun document(line: String, next: String? = null, boundary: Long = 0, position: Long = 0): RemoteViews {
            val data = inputs.copy(lyricLine = line)
            return renderer.render(classic.invoke(bridge, data, open, false) as RemoteViews, data,
                { _, _ -> renderer.prepareBackground(background) }, false,
                VivoMusicProgressMotion.Frame(0.2f, 0.2f, 0, false), emptyMap(), emptyList(),
                lyrics = if (next == null) null else ExternalLyricWindow(line, next, boundary, 2000, position))
        }
        fun surface(): Any = (renderer.javaClass.getDeclaredField("surfaces").apply { isAccessible = true }.get(renderer) as Map<*, *>)[false]!!
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val view = document("AAAA").apply(activity, null)
            val cached = surface()
            val h = (cached.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }.get(cached) as Bitmap).height
            @Suppress("UNCHECKED_CAST")
            val bounds = cached.javaClass.getDeclaredField("bounds").apply { isAccessible = true }.get(cached) as Map<Int, RectF>
            val rect = RectF(bounds.getValue(R.id.vivo_music_artist)).apply { inset(-8f, -8f) }
            activity.setContentView(view, ViewGroup.LayoutParams(360, h))
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, h)
            fun frame(name: String): IntArray {
                val bitmap = Bitmap.createBitmap(360, h, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val report = File("build/reports/vivo-lyric-animation/$name.png"); report.parentFile!!.mkdirs()
                report.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return IntArray(rect.width().toInt() * rect.height().toInt()).also {
                    bitmap.getPixels(it, 0, rect.width().toInt(), rect.left.toInt(), rect.top.toInt(), rect.width().toInt(), rect.height().toInt())
                }
            }
            val before = frame("before")
            val delivered = document("AAAA", "MMMMMMMM", 900)
            assertSame("A lyric change rerasterized the static artwork", cached, surface())
            Thread.sleep(450); ShadowSystemClock.advanceBy(Duration.ofMillis(450))
            delivered.reapply(activity, view)
            val first = frame("first")
            Thread.sleep(350); ShadowSystemClock.advanceBy(Duration.ofMillis(350))
            val middle = frame("middle")
            Thread.sleep(150); ShadowSystemClock.advanceBy(Duration.ofMillis(150))
            val after = frame("after")
            assertArrayEquals("The next line appeared before its advance animation window", before, first)
            assertFalse("No intermediate lyric animation", first.contentEquals(middle))
            assertFalse("Animation had already snapped to its last frame", middle.contentEquals(after))
            assertFalse("New lyric never appeared", before.contentEquals(after))
            document("MMMMMMMM").reapply(activity, view)
            assertArrayEquals("The timestamp still showed an unfinished entrance", frame("sharp-reference"), after)
            val late = document("MMMMMMMM", "WWWW", 100)
            Thread.sleep(450); ShadowSystemClock.advanceBy(Duration.ofMillis(450))
            late.reapply(activity, view)
            val lateFirst = frame("late-first")
            document("WWWW").reapply(activity, view)
            assertArrayEquals("Delivery delay restarted a late lyric animation", frame("late-reference"), lateFirst)
            val fresh = document("WWWW", "NNNN", 1000, 850)
            fresh.reapply(activity, view)
            frame("fresh-position-first")
            Thread.sleep(200); ShadowSystemClock.advanceBy(Duration.ofMillis(200))
            val atDeadline = frame("fresh-position-deadline")
            document("NNNN").reapply(activity, view)
            assertArrayEquals("A stale notification position delayed the lyric deadline", frame("fresh-position-reference"), atDeadline)
        } finally { activity.finish(); renderer.clear(); bridge.clear() }
    }
}
