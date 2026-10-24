package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.abs
import moe.ouom.neriplayer.feature.player.R
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance
import moe.ouom.neriplayer.common.graphics.artworkBoxBlurPass
import moe.ouom.neriplayer.common.graphics.scaledArtworkBlurRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VivoMusicCardArtworkTest {
    private fun renderer() = VivoMusicCardArtwork(ApplicationProvider.getApplicationContext<Application>())

    private fun splitCover(vertical: Boolean, first: Int = Color.RED, second: Int = Color.BLUE): Bitmap =
        Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(first)
                drawRect(if (vertical) 80f else 0f, if (vertical) 0f else 80f, 160f, 160f,
                    Paint().apply { color = second })
            }
        }

    @Test
    fun `backdrop retains album composition instead of just its mean color`() {
        val renderer = renderer()
        val source = splitCover(vertical = true)
        renderer.update(source)
        val background = renderer.background!!
        assertEquals(512, background.width)
        assertEquals(288, background.height)
        assertTrue(Color.red(background.getPixel(60, 90)) > Color.blue(background.getPixel(60, 90)))
        assertTrue(Color.blue(background.getPixel(420, 144)) > Color.red(background.getPixel(420, 144)))
        assertEquals(Color.RED, source.getPixel(10, 10))
        assertEquals(Color.BLUE, source.getPixel(150, 10))

        renderer.update(splitCover(vertical = false))
        assertNotSame(background, renderer.background)
        assertTrue(Color.red(renderer.background!!.getPixel(256, 20)) > Color.blue(renderer.background!!.getPixel(256, 20)))
        assertTrue(Color.blue(renderer.background!!.getPixel(256, 260)) > Color.red(renderer.background!!.getPixel(256, 260)))
    }

    @Test
    fun `album edges are blurred and bright covers stay readable behind text`() {
        val renderer = renderer()
        renderer.update(splitCover(vertical = true, first = Color.WHITE, second = Color.BLACK))
        val bitmap = renderer.background!!
        val row = (192..320).map { Color.red(bitmap.getPixel(it, 144)) }
        assertTrue(row.any { it in 5..30 })
        assertTrue(row.zipWithNext().all { (a, b) -> abs(a - b) <= 5 })
        assertTrue(Color.red(bitmap.getPixel(10, 10)) in 110..125)
        assertTrue(Color.red(bitmap.getPixel(10, 170)) < Color.red(bitmap.getPixel(10, 10)))
        val output = File("build/reports/vivo-artwork/blurred-cover.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `actual card layout keeps secondary text readable above album artwork`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val renderer = renderer()
        renderer.update(splitCover(vertical = true))
        val card = LayoutInflater.from(context).inflate(R.layout.notification_vivo_music, null)
        card.findViewById<ImageView>(R.id.vivo_music_backdrop).setImageBitmap(renderer.background)
        card.findViewById<ImageView>(R.id.vivo_music_artwork).setImageBitmap(renderer.cover)
        card.findViewById<TextView>(R.id.vivo_music_title).text = "Album artwork"
        card.findViewById<TextView>(R.id.vivo_music_artist).text = "Blurred cover background"
        card.findViewById<TextView>(R.id.vivo_music_elapsed).text = "00:42"
        card.findViewById<TextView>(R.id.vivo_music_duration).text = "03:15"
        for (id in listOf(R.id.vivo_music_artist, R.id.vivo_music_elapsed, R.id.vivo_music_duration)) {
            val color = card.findViewById<TextView>(id).currentTextColor
            assertTrue(ColorUtils.calculateContrast(color, Color.rgb(72, 72, 72)) >= 4.5)
        }
        card.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        card.draw(Canvas(bitmap))
        val output = File("build/reports/vivo-artwork/card.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `progress updates reuse the cached cover and backdrop`() {
        val renderer = renderer()
        val source = splitCover(vertical = true)
        renderer.update(source)
        val background = renderer.background
        val cover = renderer.cover
        repeat(20) { renderer.update(source) }
        assertSame(background, renderer.background)
        assertSame(cover, renderer.cover)
    }

    @Test
    fun `missing artwork clears the previous album and reuses its neutral backdrop`() {
        val renderer = renderer()
        renderer.update(splitCover(vertical = true))
        val previous = renderer.background
        renderer.update(null)
        val empty = renderer.background!!
        assertNull(renderer.cover)
        assertNotSame(previous, empty)
        val center = empty.getPixel(160, 90)
        assertTrue(Color.red(center) <= 14 && Color.green(center) <= 14 && Color.blue(center) <= 16)
        renderer.update(null)
        assertSame(empty, renderer.background)
    }

    @Test
    fun `mask and blur controls change actual pixels without regenerating the album cover`() {
        val renderer = renderer()
        val source = splitCover(vertical = true, first = Color.WHITE, second = Color.BLACK)
        val defaults = VivoMusicIslandAppearance()
        renderer.update(source, defaults)
        val cover = renderer.cover
        val original = renderer.background!!
        renderer.update(source, defaults.copy(maskOpacityPercent = 0))
        val bright = renderer.background!!
        assertSame(cover, renderer.cover)
        assertTrue(Color.red(bright.getPixel(20, 90)) > Color.red(original.getPixel(20, 90)))
        assertEquals(255, Color.red(bright.getPixel(20, 90)))
        renderer.update(source, defaults.copy(maskOpacityPercent = 0, blurRadius = 0))
        val sharp = renderer.background!!
        renderer.update(source, defaults.copy(maskOpacityPercent = 0, blurRadius = 12))
        val blurred = renderer.background!!
        val sharpTransition = (0 until sharp.width).count { Color.red(sharp.getPixel(it, 144)) in 10..245 }
        val blurredTransition = (0 until blurred.width).count { Color.red(blurred.getPixel(it, 144)) in 10..245 }
        assertTrue("Blur radius did not change the transition width", blurredTransition > sharpTransition + 30)
        assertSame(cover, renderer.cover)
        assertEquals(Color.WHITE, source.getPixel(10, 10))
    }

    @Test fun `zero blur retains pixel details from the high resolution artwork instead of the notification thumbnail`() {
        val renderer = renderer()
        val full = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) for (y in 0 until height) {
                setPixel(x, y, if (x % 4 < 2) Color.WHITE else Color.BLACK)
            }
        }
        val thumbnail = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        renderer.update(thumbnail, VivoMusicIslandAppearance(0, 0), backgroundArtwork = full)
        val clear = renderer.background!!
        for (x in 0 until 512) assertEquals(full.getPixel(x, 256), clear.getPixel(x, 144))
        assertEquals(Color.RED, renderer.cover!!.getPixel(128, 128))
        val sample = renderer.javaClass.getDeclaredField("blurredSample").apply { isAccessible = true }
        val cropped = sample.get(renderer)
        renderer.update(thumbnail, VivoMusicIslandAppearance(30, 0), backgroundArtwork = full)
        assertSame(cropped, sample.get(renderer))
        assertTrue(!clear.isRecycled && !full.isRecycled && !thumbnail.isRecycled)
        renderer.update(thumbnail, VivoMusicIslandAppearance(30, 1), backgroundArtwork = full)
        assertEquals(256, (sample.get(renderer) as Bitmap).width)
        renderer.update(thumbnail, VivoMusicIslandAppearance(30, 12), backgroundArtwork = full)
        assertEquals(128, (sample.get(renderer) as Bitmap).width)
    }

    @Test fun `weak blur closely matches an output resolution reference and grows smoothly with radius`() {
        val renderer = renderer()
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, if ((x / 48 + y / 48) % 2 == 0) Color.WHITE else Color.BLACK)
            }
        }
        renderer.update(source, VivoMusicIslandAppearance(0, 1))
        val actual = renderer.background!!
        var reference = IntArray(512 * 288)
        source.getPixels(reference, 0, 512, 0, 112, 512, 288)
        val radius = scaledArtworkBlurRadius(1, 8f)
        repeat(3) {
            reference = artworkBoxBlurPass(reference, 512, 288, radius, horizontal = true)
            reference = artworkBoxBlurPass(reference, 512, 288, radius, horizontal = false)
        }
        var error = 0.0
        for (y in 0 until 288) for (x in 0 until 512) {
            error += abs(Color.red(actual.getPixel(x, y)) - Color.red(reference[y * 512 + x]))
        }
        assertTrue("Weak blur mean channel error = ${error / reference.size}", error / reference.size < 5)

        val split = splitCover(true, Color.WHITE, Color.BLACK)
        var lastWidth = -1
        for (setting in 0..12) {
            renderer.update(split, VivoMusicIslandAppearance(0, setting))
            val bitmap = renderer.background!!
            val transitionWidth = (0 until bitmap.width).count { Color.red(bitmap.getPixel(it, 144)) in 10..245 }
            assertTrue("Blur contracted at $setting", transitionWidth > lastWidth)
            lastWidth = transitionWidth
        }
    }

    @Test
    fun `settings preview shares the real background and never binds playback controls`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preview = VivoMusicIslandPreview(context)
        val source = splitCover(vertical = true)
        preview.update(null, source, VivoMusicIslandAppearance(30, 6))
        val previewBitmap = (preview.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertTrue("Backdrop resolution expanded the preview height", previewBitmap.width.toFloat() / previewBitmap.height > 2f)
        val expected = renderer().apply { update(source, VivoMusicIslandAppearance(30, 6)) }.background!!
        val renderer = preview.javaClass.getDeclaredField("artworkRenderer").apply { isAccessible = true }
            .get(preview) as VivoMusicCardArtwork
        assertTrue(expected.sameAs(renderer.background!!))
        val measuredBackground = renderer.backgroundFor(previewBitmap.width, previewBitmap.height)
        assertEquals(previewBitmap.width.toFloat() / previewBitmap.height,
            measuredBackground.width.toFloat() / measuredBackground.height, 0.01f)
        assertTrue(!preview.hasOnClickListeners())
        val output = File("build/reports/vivo-artwork/settings-preview.png")
        output.parentFile!!.mkdirs()
        val bitmap = (preview.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `measured target crops a round object without stretching and retains wide source composition`() {
        val source = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.BLUE)
            drawCircle(500f, 200f, 75f, Paint().apply { color = Color.WHITE })
            drawRect(0f, 0f, 60f, 400f, Paint().apply { color = Color.RED })
            drawRect(940f, 0f, 1000f, 400f, Paint().apply { color = Color.GREEN })
        }
        val renderer = renderer()
        renderer.update(source, VivoMusicIslandAppearance(0, 0))
        for ((width, height) in listOf(360 to 134, 360 to 145, 180 to 240)) {
            val result = renderer.backgroundFor(width, height)
            assertEquals(width.toFloat() / height, result.width.toFloat() / result.height, 0.02f)
            val y = result.height / 2
            val x = result.width / 2
            val diameterX = (0 until result.width).count { Color.red(result.getPixel(it, y)) > 245 && Color.blue(result.getPixel(it, y)) > 245 }
            val diameterY = (0 until result.height).count { Color.red(result.getPixel(x, it)) > 245 && Color.blue(result.getPixel(x, it)) > 245 }
            assertTrue("Round album object was stretched: $diameterX x $diameterY", abs(diameterX - diameterY) <= 2)
            assertSame(result, renderer.backgroundFor(width, height))
        }
        val wide = renderer.backgroundFor(360, 134)
        assertEquals(Color.RED, wide.getPixel(5, wide.height / 2))
        assertEquals(Color.GREEN, wide.getPixel(wide.width - 6, wide.height / 2))
    }
}
