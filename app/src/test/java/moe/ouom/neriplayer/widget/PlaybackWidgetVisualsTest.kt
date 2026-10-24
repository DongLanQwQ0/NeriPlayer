package moe.ouom.neriplayer.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import moe.ouom.neriplayer.common.graphics.artworkBoxBlurPass
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackWidgetVisualsTest {

    @Test
    fun `blur source retains enough samples and matches the widget aspect`() {
        val artwork = solidBitmap(512, 512, Color.RED)

        val wide = playbackWidgetBlurSource(artwork, targetWidth = 1024, targetHeight = 512)
        val tall = playbackWidgetBlurSource(artwork, targetWidth = 300, targetHeight = 600)
        val square = playbackWidgetBlurSource(artwork, targetWidth = 480, targetHeight = 480)

        assertEquals(512 to 256, wide.width to wide.height)
        assertEquals(256 to 512, tall.width to tall.height)
        assertEquals(480 to 480, square.width to square.height)
        assertEquals(Color.RED, square[20, 20])
        assertEquals(512, artwork.width)
        assertTrue(!artwork.isRecycled)
    }

    @Test
    fun `small artwork is never upscaled for the blur source`() {
        val blur = playbackWidgetBlurSource(solidBitmap(16, 16, Color.BLUE), 160, 160)

        assertEquals(16 to 16, blur.width to blur.height)
    }

    @Test fun `three reduced box radii preserve the 24px reference strength with a smooth kernel`() {
        val cover = createBitmap(512, 512).apply {
            for (y in 0 until height) for (x in 0 until width) {
                this[x, y] = if (x < 256) Color.WHITE else Color.BLACK
            }
        }
        val actual = playbackWidgetBlurSource(cover, 1280, 1280)
        assertEquals(512 to 512, actual.width to actual.height)
        val pixels = IntArray(512 * 512)
        cover.getPixels(pixels, 0, 512, 0, 0, 512, 512)
        fun blurred(radii: List<Int>): IntArray {
            var result = pixels
            for (radius in radii) {
                val horizontal = artworkBoxBlurPass(result, 512, 512, radius, horizontal = true)
                result = artworkBoxBlurPass(horizontal, 512, 512, radius, horizontal = false)
            }
            return result
        }
        assertEquals(listOf(13, 14, 14), playbackWidgetBlurRadii(24, 512).toList())
        assertEquals(listOf(27, 28, 28), playbackWidgetBlurRadii(24, 1024).toList())
        val expected = blurred(listOf(13, 14, 14))
        for (x in 0 until 512) assertEquals(expected[256 * 512 + x], actual[x, 256])
        val edgeSlope = Color.red(actual[228, 256]) - Color.red(actual[229, 256])
        val centerSlope = Color.red(actual[255, 256]) - Color.red(actual[256, 256])
        assertTrue("Kernel retained a flat box profile", centerSlope > edgeSlope)
        assertTrue(!cover.isRecycled)
    }

    @Test
    fun `compact backdrop keeps the surface size and renders a soft blur under the scrim`() {
        val stripes = createBitmap(192, 192).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    this[x, y] = if ((x / 4) % 2 == 0) Color.WHITE else Color.BLACK
                }
            }
        }
        val size = PlaybackWidgetSize(widthDp = 160, heightDp = 160)

        val plain = playbackWidgetBackdrop(stripes, size, cornerRadiusDp = 16f, renderScale = 3f)
        val scrimmed = playbackWidgetBackdrop(
            stripes,
            size,
            cornerRadiusDp = 16f,
            applyScrim = true,
            renderScale = 3f
        )

        assertEquals(480 to 480, plain.width to plain.height)
        val row = plain.height / 2
        var maxNeighborDelta = 0
        for (x in 40 until plain.width - 41) {
            maxNeighborDelta = maxOf(maxNeighborDelta, abs(Color.red(plain[x, row]) - Color.red(plain[x + 1, row])))
        }
        // The widest pass bounds the slope; subsequent averaging cannot increase that bound.
        // spreads that step over the output pixels, with one level allowed for rounding.
        val widestKernel = 2 * playbackWidgetBlurRadii(24, stripes.width).max() + 1
        val maxFilteredStep = ceil(255f / widestKernel / (plain.width.toFloat() / stripes.width)).toInt() + 1
        assertTrue("adjacent pixels jump by $maxNeighborDelta", maxNeighborDelta <= maxFilteredStep)
        assertTrue(Color.red(scrimmed[240, 240]) < Color.red(plain[240, 240]))
        assertEquals(0, Color.alpha(plain[0, 0]))
        assertEquals(255, Color.alpha(plain[240, 240]))
    }

    @Test
    fun `box blur pass averages neighbors along one axis and clamps at edges`() {
        val black = Color.BLACK
        val white = Color.WHITE
        val row = intArrayOf(white, black, black, black, white)

        val horizontal = artworkBoxBlurPass(row, width = 5, height = 1, radius = 1, horizontal = true)
        val vertical = artworkBoxBlurPass(row, width = 1, height = 5, radius = 1, horizontal = false)

        val twoThirds = 0xFF * 2 / 3
        val oneThird = 0xFF / 3
        assertEquals(Color.argb(255, twoThirds, twoThirds, twoThirds), horizontal[0])
        assertEquals(Color.argb(255, oneThird, oneThird, oneThird), horizontal[1])
        assertEquals(black, horizontal[2])
        assertEquals(horizontal.toList(), vertical.toList())
    }

    @Test
    fun `visuals without artwork fall back to the default seed palette`() {
        val light = buildPlaybackWidgetVisuals(artwork = null)
        val dark = buildPlaybackWidgetVisuals(artwork = null, isDarkTheme = true)

        assertNull(light.artwork)
        assertNull(light.compactArtwork)
        assertEquals(255, Color.alpha(light.primaryControlColor))
        assertEquals(light.textPrimary, light.controlTint)
        assertEquals(Color.rgb(12, 24, 18), light.primaryControlTint)
        assertTrue(luminance(light.backgroundColor) > luminance(dark.backgroundColor))
        assertTrue(luminance(dark.textPrimary) > luminance(light.textPrimary))
    }

    @Test
    fun `compact artwork retains the source aspect while regular artwork stays square and bounded`() {
        val wideRed = solidBitmap(800, 600, Color.rgb(200, 30, 30))

        val visuals = buildPlaybackWidgetVisuals(wideRed)

        val compact = visuals.compactArtwork
        assertNotNull(compact)
        assertEquals(800 to 600, compact!!.width to compact.height)
        assertEquals(512 to 512, visuals.artwork!!.width to visuals.artwork!!.height)
        assertEquals(255, Color.alpha(visuals.primaryControlColor))
        assertEquals(0, Color.alpha(visuals.artwork!![0, 0]))
        val background = visuals.backgroundColor
        assertTrue(Color.red(background) > Color.green(background))
        assertNotEquals(buildPlaybackWidgetVisuals(null).backgroundColor, background)
    }

    @Test fun `compact backdrop caches full density surfaces across playback state updates`() {
        val cover = solidBitmap(192, 192, Color.RED)
        val visuals = buildPlaybackWidgetVisuals(cover)
        val size = PlaybackWidgetSize(260, 188)
        val backdrop = visuals.compactBackdrop(cover, size, 24f, 3.125f)
        assertEquals(813 to 588, backdrop.width to backdrop.height)
        repeat(20) { assertSame(backdrop, visuals.compactBackdrop(cover, size, 24f, 3.125f)) }
        org.junit.Assert.assertNotSame(backdrop, visuals.compactBackdrop(cover, size, 24f, 3.125f, 1))
        assertTrue(!cover.isRecycled)
    }

    @Test fun `wide surfaces retain native pixels above 1024 while bounding bitmap memory`() {
        val native = playbackWidgetSurface(PlaybackWidgetSize(420, 240), 24f, Color.WHITE, 3f)
        assertEquals(1260 to 720, native.width to native.height)
        for (size in listOf(PlaybackWidgetSize(1000, 1000), PlaybackWidgetSize(1000, 100))) {
            val bounded = playbackWidgetSurface(size, 24f, Color.WHITE, 4f)
            assertTrue(bounded.width <= 2048 && bounded.height <= 2048)
            assertTrue("Surface exceeded the existing memory budget", bounded.byteCount <= 4 * 1024 * 1024)
            assertEquals(size.widthDp.toFloat() / size.heightDp, bounded.width.toFloat() / bounded.height, 0.02f)
        }
    }

    @Test fun `high resolution backdrop retains album details under the configured blur`() {
        val cover = createBitmap(512, 512).apply {
            for (y in 0 until height) for (x in 0 until width) {
                this[x, y] = if (x / 32 % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        val visuals = buildPlaybackWidgetVisuals(cover)
        val source = playbackWidgetBlurSource(visuals.compactArtwork!!, 1260, 720, radiusPx = 2)
        assertEquals(512, source.width)
        assertTrue(Color.red(source[16, source.height / 2]) > 240)
        assertTrue(Color.red(source[48, source.height / 2]) < 15)
    }

    @Test fun `high resolution source fills the native working surface without a 512px bottleneck`() {
        val visuals = buildPlaybackWidgetVisuals(solidBitmap(1800, 1800, Color.BLUE))
        assertEquals(1536 to 1536, visuals.compactArtwork!!.width to visuals.compactArtwork!!.height)
        assertEquals(512 to 512, visuals.artwork!!.width to visuals.artwork!!.height)
        val source = playbackWidgetBlurSource(visuals.compactArtwork!!, 1260, 720)
        assertEquals(1260 to 720, source.width to source.height)
    }

    @Test fun `zero radius is clear and very small radii remain useful and monotonic`() {
        val source = createBitmap(512, 8).apply {
            for (y in 0 until height) for (x in 0 until width) this[x, y] = if (x < 256) Color.WHITE else Color.BLACK
        }
        var previousWidth = -1
        for (radius in 0..24) {
            val blurred = playbackWidgetBlurSource(source, 512, 8, radius)
            if (radius == 0) for (x in 0 until 512) assertEquals(source[x, 4], blurred[x, 4])
            val edge = (0 until 512).count { Color.red(blurred[it, 4]) in 10..245 }
            assertTrue("Blur contracted at radius $radius", edge >= previousWidth)
            if (radius == 1) assertTrue("Weak blur disappeared through rounding", edge > 0)
            previousWidth = edge
        }
    }

    @Test fun `published builds ignore any locally tuned radius`() {
        for (stored in listOf(-5, 0, 1, 12, 48, 100)) {
            assertEquals(24, playbackWidgetTuningRadius(false, stored))
        }
        assertEquals(0, playbackWidgetTuningRadius(true, -5))
        assertEquals(1, playbackWidgetTuningRadius(true, 1))
        assertEquals(48, playbackWidgetTuningRadius(true, 100))
    }

    @Test
    fun `seed sampling ignores transparent pixels and weighs saturated colors`() {
        val transparent = buildPlaybackWidgetVisuals(solidBitmap(64, 64, Color.TRANSPARENT))
        val mixed = createBitmap(96, 96).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    this[x, y] = when ((x + y) % 4) {
                        0 -> Color.BLACK
                        1 -> Color.WHITE
                        2 -> Color.TRANSPARENT
                        else -> Color.rgb(20, 160, 40)
                    }
                }
            }
        }

        val gray = buildPlaybackWidgetVisuals(solidBitmap(64, 64, Color.rgb(112, 112, 112)))
        val green = buildPlaybackWidgetVisuals(mixed)

        assertEquals(gray.backgroundColor, transparent.backgroundColor)
        assertEquals(gray.textPrimary, transparent.textPrimary)
        assertTrue(Color.green(green.backgroundColor) > Color.red(green.backgroundColor))
        assertTrue(Color.green(green.backgroundColor) > Color.blue(green.backgroundColor))
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        createBitmap(width, height).apply { eraseColor(color) }

    private fun luminance(color: Int): Int = Color.red(color) + Color.green(color) + Color.blue(color)
}
