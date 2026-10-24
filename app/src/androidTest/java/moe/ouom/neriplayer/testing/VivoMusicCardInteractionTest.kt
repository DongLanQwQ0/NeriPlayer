package moe.ouom.neriplayer.testing

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Rendering is read-only; playback fixtures require an explicitly selected, idle emulator. */
@ManualInstrumentedTest
@RunWith(AndroidJUnit4::class)
class VivoMusicCardInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun renderControlsWithoutStartingPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVivoCardRender") == "true")
        instrumentation.runOnMainSync {
            val preview = card(SongItem(-1L, "Music controls", "Artist", "test", 0L, 60_000L, null))
            val width = context.resources.displayMetrics.widthPixels
            preview.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            preview.layout(0, 0, width, preview.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, preview.measuredHeight, Bitmap.Config.ARGB_8888)
            assertLiveImages(preview)
            preview.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), "vivo-controls-preview.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val inspected = classicCard(SongItem(-1L, "Music controls", "Artist", "test", 0L, 60_000L, null))
            val favorite = inspected.findViewById<android.widget.CheckBox>(id("vivo_music_favorite"))
            inspected.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            inspected.layout(0, 0, width, inspected.measuredHeight)
            assertEquals((44 * context.resources.displayMetrics.density).roundToInt(), favorite.width)
            assertFalse(favorite.isChecked)
            val targets = inspected.findViewById<LinearLayout>(id("vivo_music_seek_targets"))
            assertEquals(41, targets.childCount)
            for (index in 0 until targets.childCount) assertNull(targets.getChildAt(index).background)
        }
    }

    @Test
    fun classicProgressReapplyReusesTheExistingTapTargets() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVivoCardRender") == "true")
        instrumentation.runOnMainSync {
            val song = SongItem(-1L, "Music controls", "Artist", "test", 0L, 60_000L, null)
            val remote = cardRemoteViews(song, classic = true)
            val preview = remote.apply(context, null)
            val row = preview.findViewById<LinearLayout>(id("vivo_music_seek_targets"))
            val original = (0 until row.childCount).map(row::getChildAt)
            assertEquals(41, original.size)
            cardRemoteViews(song, classic = true).reapply(context, preview)
            assertEquals(41, row.childCount)
            original.forEachIndexed { index, target ->
                assertSame("Progress updates must reuse target $index", target, row.getChildAt(index))
            }
        }
    }

    @Test
    fun progressTapSeeksPausedPlaybackAndRejectsThePreviousSong() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVivoCardInteraction") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true))
        assumeFalse("Recovery mode must be resolved through the application's normal recovery flow",
            moe.ouom.neriplayer.core.player.host.PlayerDependencies.presentation.shouldEnterSafeMode(context))
        assumeTrue(PlayerManager.currentQueueFlow.value.isEmpty())
        assumeTrue(!PlayerManager.isPlayingFlow.value && !PlayerManager.playWhenReadyFlow.value)
        val file = File(context.cacheDir, "vivo-seek-fixture.wav")
        silentWav(file)
        val first = SongItem(-0x4e505649L, "Vivo music seek test", "Android test", "test", 0L,
            60_000L, null, mediaUri = file.toURI().toString(), localFilePath = file.absolutePath)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync {
                val application = context.applicationContext as android.app.Application
                moe.ouom.neriplayer.core.di.AppContainer.initialize(application)
                PlayerManager.initialize(application)
            }
            playPaused(first)
            lateinit var oldCard: View
            instrumentation.runOnMainSync {
                oldCard = card(first)
            }
            scenario.onActivity { activity ->
                activity.setContentView(oldCard, ViewGroup.LayoutParams(oldCard.measuredWidth, oldCard.measuredHeight))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val before = pixels(oldCard)
                tap(oldCard, first, "vivo_music_play")
                assertFalse("The icon must change synchronously", before.contentEquals(pixels(oldCard)))
                tap(oldCard, first, "vivo_music_play")
                assertTrue(before.contentEquals(pixels(oldCard)))
                tap(oldCard, first, "vivo_music_seek_targets", 20)
            }
            await("Tap did not seek the actual player") {
                kotlin.math.abs(PlayerManager.playbackPositionFlow.value - 30_000L) < 500L
            }
            assertFalse("Seeking paused playback must not resume it", PlayerManager.isPlayingFlow.value)

            val second = first.copy(id = first.id - 1L, name = "Second seek fixture", sourceStableKey = "second-vivo-fixture")
            playPaused(second)
            val positionBeforeStaleClick = PlayerManager.playbackPositionFlow.value
            instrumentation.runOnMainSync {
                tap(oldCard, first, "vivo_music_seek_targets", 30)
            }
            Thread.sleep(500)
            assertEquals("Old song controls must not seek the new song", positionBeforeStaleClick, PlayerManager.playbackPositionFlow.value)
            assertFalse(PlayerManager.isPlayingFlow.value)

            instrumentation.runOnMainSync {
                val preview = card(second)
                val width = context.resources.displayMetrics.widthPixels
                preview.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                preview.layout(0, 0, width, preview.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, preview.measuredHeight, Bitmap.Config.ARGB_8888)
                preview.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "vivo-card-preview.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                PlayerManager.pause()
                if (PlayerManager.currentQueueFlow.value.any { it.id == first.id || it.id == first.id - 1L }) {
                    PlayerManager.removeQueueItem(0)
                }
                context.stopService(Intent(context, AudioPlayerService::class.java))
            }
            scenario.close()
            file.delete()
        }
    }

    private fun playPaused(song: SongItem) {
        instrumentation.runOnMainSync { PlayerManager.playPlaylist(listOf(song), 0) }
        await("Silent fixture did not load") { PlayerManager.currentSongFlow.value?.id == song.id && PlayerManager.isPlayingFlow.value }
        instrumentation.runOnMainSync { PlayerManager.pause() }
        await("Fixture did not pause") { !PlayerManager.isPlayingFlow.value && !PlayerManager.playWhenReadyFlow.value }
    }

    private fun card(song: SongItem): View = cardRemoteViews(song).apply(context, null).also { view ->
        val width = (360 * context.resources.displayMetrics.density).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun classicCard(song: SongItem): ViewGroup = cardRemoteViews(song, classic = true).apply(context, null) as ViewGroup

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    private fun tap(view: View, song: SongItem, name: String, step: Int? = null) {
        val layout = classicCard(song)
        layout.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout.layout(0, 0, view.width, layout.measuredHeight)
        val target = if (step == null) layout.findViewById<View>(id(name)) else
            layout.findViewById<LinearLayout>(id(name)).getChildAt(step)
        val rect = Rect(0, 0, target.width, target.height)
        layout.offsetDescendantRectToMyCoords(target, rect)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, rect.exactCenterX(), rect.exactCenterY(), 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun cardRemoteViews(song: SongItem, classic: Boolean = false): RemoteViews {
        val type = Class.forName("moe.ouom.neriplayer.core.player.service.presentation.VivoMusicIslandBridge")
        val bridge = type.getDeclaredConstructor(Context::class.java).newInstance(context)
        val lyricType = Class.forName("moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState")
        val lyric = lyricType.getDeclaredConstructor(Boolean::class.javaPrimitiveType, String::class.java).newInstance(false, null)
        val inputsType = Class.forName("moe.ouom.neriplayer.core.player.service.presentation.PlaybackServiceNotificationRenderInputs")
        val inputs = inputsType.declaredConstructors.single { it.parameterCount == 13 }.newInstance(
            song, "Android test", false, false, false, false, false, lyric, null, null, 15_000L, "A real lyric line", song.durationMs,
        )
        val notification = type.declaredMethods.single { it.name.startsWith("notification$") }
            .invoke(bridge, inputs, 0) as Notification
        val remote = if (classic) type.getDeclaredMethod("classicCard", inputsType,
            android.app.PendingIntent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(bridge, inputs, notification.contentIntent, false) as RemoteViews else
            notification.extras.getParcelable("notification.superx.customTemplate", RemoteViews::class.java)!!
        // Match the actual Binder boundary: system hosts render a parcelled RemoteViews, not its local bitmap cache.
        val parcel = android.os.Parcel.obtain()
        val delivered = try {
            remote.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        return delivered
    }

    private fun id(name: String) = context.resources.getIdentifier(name, "id", context.packageName)

    private fun assertLiveImages(view: View) {
        val bitmap = ((view as? ImageView)?.drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null) {
            val name = if (view.id == View.NO_ID) view.javaClass.simpleName else context.resources.getResourceName(view.id)
            assertFalse("Recycled image in $name before rendering", bitmap.isRecycled)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertLiveImages(view.getChildAt(index))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }

    private fun silentWav(file: File) {
        val bytes = 60 * 8_000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes).array()
        RandomAccessFile(file, "rw").use { it.setLength(44L + bytes); it.write(header) }
    }
}
