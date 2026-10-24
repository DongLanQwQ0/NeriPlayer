package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.IdentityHashMap
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.feature.player.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowBuild
import java.time.Duration
import moe.ouom.neriplayer.data.identity.stableKey
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

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `high detail unblurred crossfade keeps both notification documents within the transaction budget`() {
        val bridge = VivoMusicIslandBridge(context)
        bridge.javaClass.getDeclaredField("appearance").apply { isAccessible = true }.set(bridge,
            moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance(0, 0))
        val card = bridge.javaClass.getDeclaredMethod("card", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun artwork(seed: Int): Bitmap {
            val random = kotlin.random.Random(seed)
            return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(512 * 512) { 0xff000000.toInt() or random.nextInt(0x1000000) }
                setPixels(pixels, 0, 512, 0, 0, 512, 512)
            }
        }
        val thumbnails = IdentityHashMap<Bitmap, Bitmap>()
        fun document(art: Bitmap, cameraSafe: Boolean) = card.invoke(bridge,
            inputs.copy(artwork = thumbnails.getOrPut(art) { Bitmap.createScaledBitmap(art, 256, 256, true) },
                backgroundArtwork = art), open, cameraSafe, SystemClock.elapsedRealtime()) as RemoteViews
        document(artwork(1), false)
        val next = artwork(2)
        val bundle = android.os.Bundle().apply {
            putParcelable("notification", document(next, false))
            putParcelable("island", document(next, true))
        }
        val parcel = android.os.Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            println("unblurred crossfade documents bytes=${parcel.dataSize()}")
            assertTrue("Crossfade exceeds the Binder transaction budget: ${parcel.dataSize()}", parcel.dataSize() < 900_000)
        } finally { parcel.recycle(); bridge.clear() }
    }

    @Test fun `vivo notification sends a transparent bitmap glyph instead of an adaptive icon resource`() {
        val manufacturer = Build.MANUFACTURER
        try {
            ShadowBuild.setManufacturer("vivo")
            val icon = createPlaybackNotificationIcon(context)
            assertEquals(Icon.TYPE_BITMAP, icon.type)
            val bitmap = (icon.loadDrawable(context) as BitmapDrawable).bitmap
            assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
            assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y ->
                android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0
            } })
            ShadowBuild.setManufacturer("other")
            assertEquals(Icon.TYPE_RESOURCE, createPlaybackNotificationIcon(context).type)
        } finally { ShadowBuild.setManufacturer(manufacturer) }
    }

    @Test fun `rendering both icon states must never send playback or favorite commands`() {
        val application = shadowOf(context as Application)
        val bridge = VivoMusicIslandBridge(context)
        val card = bridge.javaClass.getDeclaredMethod("card", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
        for (checked in listOf(false, true)) for (cameraSafe in listOf(false, true)) {
            card.invoke(bridge, inputs.copy(playbackControlPlaying = checked, favorite = checked), open, cameraSafe, SystemClock.elapsedRealtime())
            val command = application.nextStartedService
            assertNull("Rendering sent a real player command: ${command?.action}", command)
        }
        bridge.clear()
    }

    @Test fun `rapid seeks keep the existing document until the latest curve settles and reject old acknowledgements`() {
        val bridge = VivoMusicIslandBridge(context)
        bridge.javaClass.getDeclaredField("lastInputs").apply { isAccessible = true }.set(bridge, inputs)
        val motion = bridge.javaClass.getDeclaredField("progressMotion").apply { isAccessible = true }
            .get(bridge) as VivoMusicProgressMotion
        motion.update(checkNotNull(inputs.song).stableKey(), inputs.durationMs, inputs.positionMs, false, SystemClock.elapsedRealtime())
        val defer = bridge.javaClass.getDeclaredMethod("deferSeekPublication", PlaybackServiceNotificationRenderInputs::class.java)
            .apply { isAccessible = true }
        val resolve = bridge.javaClass.getDeclaredMethod("resolvePendingInputs", PlaybackServiceNotificationRenderInputs::class.java)
            .apply { isAccessible = true }
        bridge.previewControl(null, null, 48_000L)
        assertEquals(true, defer.invoke(bridge, inputs.copy(positionMs = 48_000L)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        bridge.previewControl(null, null, 12_000L)
        // Even an early acknowledgement of the second seek must not release the first one.
        assertEquals(12_000L, (resolve.invoke(bridge, inputs.copy(positionMs = 12_000L)) as PlaybackServiceNotificationRenderInputs).positionMs)
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        assertEquals(true, defer.invoke(bridge, inputs.copy(positionMs = 48_000L)))
        assertEquals(12_000L, (resolve.invoke(bridge, inputs.copy(positionMs = 48_000L)) as PlaybackServiceNotificationRenderInputs).positionMs)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        assertEquals(false, defer.invoke(bridge, inputs.copy(positionMs = 12_000L)))
        assertEquals(0.2f, motion.frame(SystemClock.elapsedRealtime()).from, 0.001f)
        bridge.clear()
    }

    @Test fun `remote host alpha blends the background after document reapply`() {
        val bridge = VivoMusicIslandBridge(context)
        val xml = bridge.javaClass.getDeclaredMethod("classicCard", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(bridge, inputs, open, false) as RemoteViews
        val renderer = VivoMusicAnimatedCard(context)
        fun document(color: Int): RemoteViews {
            val background = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                setHasAlpha(false)
                eraseColor(color)
            }
            return renderer.render(xml, inputs, { _, _ -> renderer.prepareBackground(background) }, false,
                VivoMusicProgressMotion.Frame(0.1f, 0.1f, 0L, false), emptyMap(), emptyList())
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = document(android.graphics.Color.RED).apply(activity, null)
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = view.measuredHeight
        activity.setContentView(view, ViewGroup.LayoutParams(360, height))
        view.layout(0, 0, 360, height)
        fun pixel(): Int {
            val bitmap = Bitmap.createBitmap(360, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap.getPixel(5, 40).also { bitmap.recycle() }
        }
        fun assertColor(expected: Int, actual: Int) {
            assertEquals(255, android.graphics.Color.alpha(actual))
            for (shift in listOf(0, 8, 16)) assertTrue("JPEG changed the solid background color",
                kotlin.math.abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 3)
        }
        assertColor(android.graphics.Color.RED, pixel())
        val delivered = document(android.graphics.Color.BLUE)
        // Notification transport/host inflation can take longer than the fade itself. The
        // visible host keeps drawing the old document while the replacement is in transit.
        Thread.sleep(450)
        ShadowSystemClock.advanceBy(Duration.ofMillis(450))
        assertColor(android.graphics.Color.RED, pixel())
        delivered.reapply(activity, view)
        val first = pixel()
        Thread.sleep(120)
        ShadowSystemClock.advanceBy(Duration.ofMillis(120))
        val middle = pixel()
        Thread.sleep(300)
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        val after = pixel()
        println("background host frames: ${Integer.toHexString(first)}, ${Integer.toHexString(middle)}, ${Integer.toHexString(after)}")
        assertTrue("Background switched on its first frame", android.graphics.Color.red(first) > android.graphics.Color.blue(first))
        assertTrue("Background had no blended intermediate frame", android.graphics.Color.red(middle) > 15 && android.graphics.Color.blue(middle) > 15)
        assertColor(android.graphics.Color.BLUE, after)
        activity.finish()
        renderer.clear()
        bridge.clear()
    }

    @Test fun `production card animates seeks and reuses static artwork on progress ticks`() {
        val bridge = VivoMusicIslandBridge(context)
        val motion = bridge.javaClass.getDeclaredField("progressMotion").apply { isAccessible = true }
            .get(bridge) as VivoMusicProgressMotion
        motion.update("song", 60_000L, 6_000L, false, SystemClock.elapsedRealtime())
        fun card(data: PlaybackServiceNotificationRenderInputs): RemoteViews = bridge.javaClass
            .getDeclaredMethod("card", PlaybackServiceNotificationRenderInputs::class.java,
                PendingIntent::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(bridge, data, open, false, SystemClock.elapsedRealtime()) as RemoteViews
        val xml = bridge.javaClass.getDeclaredMethod("classicCard", PlaybackServiceNotificationRenderInputs::class.java,
            PendingIntent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(bridge, inputs, open, false) as RemoteViews
        val layout = xml.apply(context, null) as ViewGroup
        val content = layout.findViewById<View>(R.id.vivo_music_card)
        content.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(content.measuredHeight, View.MeasureSpec.EXACTLY))
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
        // Multiple clicks before any intervening paint must cancel the former curve rather
        // than feed partially-mutated endpoints back into its next starting value.
        val beforeBurst = width("burst-before")
        tap(0.05f)
        tap(0.9f)
        tap(0.1f)
        val burstFirst = width("burst-first")
        Thread.sleep(120)
        val burstMiddle = width("burst-middle")
        Thread.sleep(400)
        val burstAfter = width("burst-after")
        assertTrue("Back-to-back clicks flashed before the next paint", kotlin.math.abs(burstFirst - beforeBurst) < 15)
        assertTrue("Burst resumed a retired animation", burstMiddle in (burstAfter + 2)..(beforeBurst - 2))
        assertTrue("Burst did not settle at its latest target", burstAfter < bounds.width() * 0.2)
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
