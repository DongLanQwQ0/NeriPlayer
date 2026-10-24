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

    @Test fun `unchanged lines reuse glyphs and do not restart their transition`() {
        val state = VivoMusicLyricTransition(1f)
        var renders = 0
        fun update(text: String, now: Long) = state.update("song", text, true, now, color) { renders++; glyph() }
        assertNull(update("first", 0).from)
        val start = update("second", 100)
        assertNotNull(start.from)
        assertEquals(0f, start.progress, 0f)
        val middle = update("second", 250)
        assertSame(start.to, middle.to)
        assertEquals(0.5f, middle.progress, 0.001f)
        assertNull(update("second", 401).from)
        assertEquals(2, renders)
    }

    @Test fun `an interrupted line snapshots the visible blend and the latest text wins`() {
        val state = VivoMusicLyricTransition(1f)
        state.update("song", "first", true, 0, color) { glyph(12f) }
        state.update("song", "second", true, 100, color) { glyph(44f) }
        val retargeted = state.update("song", "third", true, 250, color) { glyph(28f) }
        val snapshot = checkNotNull(retargeted.from).levels.first()
        assertTrue(Color.alpha(snapshot.getPixel(18, 20)) > 0)
        assertTrue(Color.alpha(snapshot.getPixel(48, 20)) > 0)
        assertNull(state.frame(551)!!.from)
        assertSame(retargeted.to, state.frame(551)!!.to)
        assertNull(state.update("other-song", "new song", true, 600, color) { glyph() }.from)
        assertNull(state.update("other-song", "artist", false, 650, color) { glyph() }.from)
    }

    @Test fun `blur spreads glyph coverage while retaining transparent background and text color`() {
        val frame = VivoMusicLyricTransition(1f).update("song", "text", true, 0, color) { glyph() }
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
