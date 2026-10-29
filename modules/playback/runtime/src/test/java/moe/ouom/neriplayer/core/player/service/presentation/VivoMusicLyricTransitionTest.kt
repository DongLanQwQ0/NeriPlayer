package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VivoMusicLyricTransitionTest {
    private val color = 0xffb8bdc4.toInt()
    private fun glyph(left: Float = 12f): Bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawRect(left, 15f, left + 14f, 25f, Paint().apply { this.color = this@VivoMusicLyricTransitionTest.color })
    }

    @Test fun `lookahead reuses upcoming glyph and finishes at the line boundary`() {
        val state = VivoMusicLyricTransition(1f)
        var renders = 0
        fun prepare(current: String, next: String?, boundary: Float) = state.prepare("song", current, next, boundary, 180f, color) { renders++; glyph() }
        val start = prepare("first", "second", 1000f)
        assertNotNull(start.from)
        assertEquals(0f, start.progress(819f), 0f)
        assertEquals(0.5f, start.progress(910f), 0.001f)
        assertEquals(1f, start.progress(1000f), 0f)
        val middle = prepare("first", "second", 100f)
        assertSame(start.to, middle.to)
        assertEquals(1f, middle.progress(450f), 0f)
        val next = prepare("second", null, 0f)
        assertSame(start.to, next.to)
        assertNull(next.from)
        assertEquals(2, renders)
    }

    @Test fun `seek and pause show current text immediately instead of replaying an old entrance`() {
        val state = VivoMusicLyricTransition(1f)
        val old = state.prepare("song", "first", "second", 1000f, 180f, color) { glyph(12f) }
        val retargeted = state.prepare("song", "third", null, 0f, 0f, color) { glyph(28f) }
        assertNull(retargeted.from)
        assertEquals(1f, retargeted.progress(0f), 0f)
        assertNotSame(old.to, retargeted.to)
        val newSong = state.prepare("other-song", "third", null, 0f, 0f, color) { glyph() }
        assertNotSame(retargeted.to, newSong.to)
        assertNull(newSong.from)
    }

    @Test fun `blur spreads glyph coverage while retaining transparent background and text color`() {
        val frame = VivoMusicLyricTransition(1f).prepare("song", "text", null, 0f, 0f, color) { glyph() }
        val sharp = frame.to.levels.first()
        val blurred = frame.to.levels.last()
        assertEquals(0, Color.alpha(sharp.getPixel(10, 20)))
        assertTrue(Color.alpha(blurred.getPixel(10, 20)) > 0)
        assertTrue(Color.alpha(blurred.getPixel(18, 20)) < 255)
        assertEquals(0, Color.alpha(blurred.getPixel(0, 0)))
        val edge = blurred.getPixel(18, 20)
        for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs((color ushr shift and 255) - (edge ushr shift and 255)) <= 2)
    }
}
