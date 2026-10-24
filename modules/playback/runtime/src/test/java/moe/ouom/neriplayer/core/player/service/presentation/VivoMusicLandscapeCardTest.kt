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
import android.view.MotionEvent
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Proxy
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.feature.player.R
import org.junit.Assert.assertEquals
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

    @Test fun `unknown duration still routes the visible transport buttons without a seek row`() =
        assertFullWidth(Configuration.ORIENTATION_LANDSCAPE, durationMs = 0)

    private fun assertFullWidth(orientation: Int, hostWidth: Int = 660, hostHeight: Int = 208, durationMs: Long = 60_000) {
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
            positionMs = 12_000, backgroundArtwork = background, durationMs = durationMs)
        (bridge.javaClass.getDeclaredField("progressMotion").apply { isAccessible = true }.get(bridge) as VivoMusicProgressMotion)
            .update(checkNotNull(inputs.song).stableKey(), durationMs, 12_000, false, SystemClock.elapsedRealtime())
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
                val callback = view.javaClass.methods.single { it.name == "addIdActionListener" }
                val received = mutableListOf<Int>()
                callback.invoke(view, Proxy.newProxyInstance(callback.parameterTypes[0].classLoader,
                    arrayOf(callback.parameterTypes[0])) { _, method, args ->
                    if (method.name == "onAction") received += args!![0] as Int
                    null
                })
                for ((id, fraction) in listOf(R.id.vivo_music_previous to 0.32f,
                    R.id.vivo_music_play to 0.5f, R.id.vivo_music_next to 0.68f,
                    R.id.vivo_music_favorite to 0.88f)) {
                    val white = buildList {
                        for (y in hostHeight / 2 until hostHeight) {
                            for (x in ((fraction - 0.045f) * hostWidth).toInt()..((fraction + 0.045f) * hostWidth).toInt()) {
                                val color = bitmap.getPixel(x, y)
                                if (Color.red(color) > 240 && Color.green(color) > 240 && Color.blue(color) > 240) add(x to y)
                            }
                        }
                    }
                    assertTrue("No rendered transport icon", white.isNotEmpty())
                    val x = white.map { it.first }.average().toFloat()
                    val y = white.map { it.second }.average().toFloat()
                    received.clear()
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        MotionEvent.obtain(time, time, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
                    }
                    assertEquals("Tap on rendered icon at $x,$y dispatched the wrong command", listOf(id), received)
                }
                fun tap(x: Float, y: Float) {
                    received.clear()
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        MotionEvent.obtain(time, time, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
                    }
                }
                tap(hostWidth / 2f, hostHeight - 2f)
                assertTrue("Bottom safe padding still activates a playback control", received.isEmpty())
                if (durationMs > 0) {
                    fun barColor(c: Int) = c == 0xff343638.toInt() || c == 0xffe8eaed.toInt()
                    val barY = (0 until hostHeight).maxBy { yy -> (0 until hostWidth).count { barColor(bitmap.getPixel(it, yy)) } }
                    val barXs = (0 until hostWidth).filter { barColor(bitmap.getPixel(it, barY)) }
                    tap(barXs.first() + (barXs.last() - barXs.first()) * 0.75f, barY.toFloat())
                    val after = Bitmap.createBitmap(hostWidth, hostHeight, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(after)) // The curve starts when the first post-click frame is painted.
                    Thread.sleep(450)
                    after.eraseColor(Color.TRANSPARENT)
                    view.draw(Canvas(after))
                    val fraction = barXs.count { after.getPixel(it, barY) == 0xffe8eaed.toInt() }.toFloat() / barXs.size
                    assertEquals("Visible seek target and native hit area disagree", 0.75f, fraction, 0.05f)
                    after.recycle()
                }
                bitmap.recycle()
            }
        } finally { activity.finish(); bridge.clear() }
    }
}
