package moe.ouom.neriplayer.common.graphics

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkBoxBlurTest {
    @Test fun `sliding sum matches the reference including alpha and clamped borders`() {
        val random = Random(42)
        for ((width, height) in listOf(1 to 1, 1 to 19, 23 to 1, 17 to 13)) {
            val source = IntArray(width * height) { random.nextInt() }
            for (radius in listOf(0, 1, 2, 9, 24)) for (horizontal in listOf(false, true)) {
                val expected = reference(source, width, height, radius, horizontal)
                val output = IntArray(source.size)
                artworkBoxBlurPass(source, width, height, radius, horizontal, output)
                assertArrayEquals("${width}x$height r=$radius horizontal=$horizontal", expected, output)
            }
        }
    }

    @Test fun `resampling preserves blur variance rather than just scaling the radius`() {
        assertEquals(0, scaledArtworkBlurRadius(0, 4f))
        assertEquals(5, scaledArtworkBlurRadius(1, 4f))
        assertEquals(9, scaledArtworkBlurRadius(2, 4f))
        assertEquals(24, scaledArtworkBlurRadius(12, 2f))
    }

    private fun reference(source: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) =
        IntArray(source.size) { index ->
            val channels = IntArray(4)
            for (offset in -radius..radius) {
                val x = if (horizontal) (index % width + offset).coerceIn(0, width - 1) else index % width
                val y = if (horizontal) index / width else (index / width + offset).coerceIn(0, height - 1)
                val color = source[y * width + x]
                for (channel in 0..3) channels[channel] += color ushr (channel * 8) and 255
            }
            var color = 0
            for (channel in 0..3) color = color or (channels[channel] / (radius * 2 + 1) shl (channel * 8))
            color
        }
}
