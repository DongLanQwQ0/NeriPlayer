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
class VivoMusicBackdropTransitionTest {
    private fun bitmap(color: Int) = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun pixel(frame: VivoMusicBackdropTransition.Frame): Int = bitmap(Color.TRANSPARENT).let { output ->
        Canvas(output).apply {
            drawBitmap(frame.from, 0f, 0f, null)
            drawBitmap(frame.to, 0f, 0f, Paint().apply { alpha = (frame.alpha * 255).toInt() })
        }
        output.getPixel(1, 1).also { output.recycle() }
    }

    @Test fun `background fades through an alpha blend instead of switching images`() {
        val fade = VivoMusicBackdropTransition()
        val red = bitmap(Color.RED)
        val blue = bitmap(Color.BLUE)
        assertEquals(Color.RED, pixel(fade.update(red, 0)))
        assertEquals(Color.RED, pixel(fade.update(blue, 100)))
        val middle = pixel(checkNotNull(fade.frame(250)))
        assertTrue(Color.red(middle) in 126..129)
        assertTrue(Color.blue(middle) in 126..129)
        assertEquals(Color.BLUE, pixel(checkNotNull(fade.frame(400))))
        fade.clear()
        assertNull(fade.frame(500))
    }

    @Test fun `another artwork change starts from the visible blend`() {
        val fade = VivoMusicBackdropTransition()
        fade.update(bitmap(Color.RED), 0)
        fade.update(bitmap(Color.BLUE), 100)
        val visible = pixel(checkNotNull(fade.frame(250)))
        val next = fade.update(bitmap(Color.GREEN), 250)
        assertEquals(visible, pixel(next))
        assertEquals(Color.GREEN, pixel(checkNotNull(fade.frame(550))))
    }
}
