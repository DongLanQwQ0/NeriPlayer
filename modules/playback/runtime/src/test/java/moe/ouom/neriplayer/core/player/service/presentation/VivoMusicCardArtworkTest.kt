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
        assertEquals(320, background.width)
        assertEquals(180, background.height)
        assertTrue(Color.red(background.getPixel(60, 90)) > Color.blue(background.getPixel(60, 90)))
        assertTrue(Color.blue(background.getPixel(260, 90)) > Color.red(background.getPixel(260, 90)))
        assertEquals(Color.RED, source.getPixel(10, 10))
        assertEquals(Color.BLUE, source.getPixel(150, 10))

        renderer.update(splitCover(vertical = false))
        assertNotSame(background, renderer.background)
        assertTrue(Color.red(renderer.background!!.getPixel(160, 20)) > Color.blue(renderer.background!!.getPixel(160, 20)))
        assertTrue(Color.blue(renderer.background!!.getPixel(160, 160)) > Color.red(renderer.background!!.getPixel(160, 160)))
    }

    @Test
    fun `album edges are blurred and bright covers stay readable behind text`() {
        val renderer = renderer()
        renderer.update(splitCover(vertical = true, first = Color.WHITE, second = Color.BLACK))
        val bitmap = renderer.background!!
        val row = (120..200).map { Color.red(bitmap.getPixel(it, 90)) }
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
        val sharpTransition = (0 until 320).count { Color.red(sharp.getPixel(it, 90)) in 10..245 }
        val blurredTransition = (0 until 320).count { Color.red(blurred.getPixel(it, 90)) in 10..245 }
        assertTrue("Blur radius did not change the transition width", blurredTransition > sharpTransition + 30)
        assertSame(cover, renderer.cover)
        assertEquals(Color.WHITE, source.getPixel(10, 10))
    }

    @Test
    fun `settings preview shares the real background and never binds playback controls`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preview = VivoMusicIslandPreview(context)
        val source = splitCover(vertical = true)
        preview.update(null, source, VivoMusicIslandAppearance(30, 6))
        val expected = renderer().apply { update(source, VivoMusicIslandAppearance(30, 6)) }.background!!
        val renderer = preview.javaClass.getDeclaredField("artworkRenderer").apply { isAccessible = true }
            .get(preview) as VivoMusicCardArtwork
        assertTrue(expected.sameAs(renderer.background!!))
        assertTrue(!preview.hasOnClickListeners())
        val output = File("build/reports/vivo-artwork/settings-preview.png")
        output.parentFile!!.mkdirs()
        val bitmap = (preview.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
