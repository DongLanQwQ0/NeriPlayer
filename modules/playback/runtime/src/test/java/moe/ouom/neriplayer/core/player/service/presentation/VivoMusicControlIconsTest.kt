package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.File
import moe.ouom.neriplayer.feature.player.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VivoMusicControlIconsTest {
    private fun icon(id: Int, name: String): Bitmap {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        checkNotNull(context.getDrawable(id)).apply { setBounds(0, 0, 240, 240); draw(Canvas(bitmap)) }
        val output = File("build/reports/vivo-controls/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test fun `previous and next have rounded bar ends and softened triangle corners`() {
        val previous = icon(R.drawable.vivo_music_previous, "previous")
        val next = icon(R.drawable.vivo_music_next, "next")
        for ((image, barX, cornerX) in listOf(Triple(previous, 55, 43), Triple(next, 185, 196))) {
            assertEquals(0, Color.alpha(image.getPixel(cornerX, 43)))
            assertEquals(255, Color.alpha(image.getPixel(barX, 50)))
            assertEquals(255, Color.alpha(image.getPixel(barX, 120)))
        }
        assertEquals(0, Color.alpha(previous.getPixel(197, 55)))
        assertEquals(0, Color.alpha(next.getPixel(42, 55)))
        assertTrue(Color.alpha(previous.getPixel(180, 120)) > 250)
        assertTrue(Color.alpha(next.getPixel(60, 120)) > 250)
    }

    @Test fun `play triangle and pause capsules retain their recognizable silhouettes`() {
        val play = icon(R.drawable.vivo_music_play, "play")
        val pause = icon(R.drawable.vivo_music_pause, "pause")
        assertEquals(0, Color.alpha(play.getPixel(64, 41)))
        assertEquals(255, Color.alpha(play.getPixel(100, 120)))
        assertEquals(0, Color.alpha(pause.getPixel(66, 34)))
        assertEquals(255, Color.alpha(pause.getPixel(80, 34)))
        assertEquals(255, Color.alpha(pause.getPixel(160, 120)))
        assertEquals(0, Color.alpha(pause.getPixel(120, 120)))
    }
}
