package dev.janakhpon.monocr.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveThresholdTest {
    // Direct neighborhood enumeration shares no sliding-window bookkeeping.
    private fun reference(pixels: IntArray, width: Int, height: Int): BooleanArray =
        BooleanArray(pixels.size) { index ->
            val x = index % width
            val y = index / width
            var sum = 0L
            var count = 0
            for (yy in maxOf(0, y - 12)..minOf(height - 1, y + 12)) {
                for (xx in maxOf(0, x - 12)..minOf(width - 1, x + 12)) {
                    sum += pixels[yy * width + xx]
                    count++
                }
            }
            pixels[index] < sum.toFloat() / count - 8
        }

    @Test
    fun `rolling threshold agrees pixel for pixel with clipped box neighborhoods`() {
        val dimensions = listOf(1 to 1, 1 to 53, 53 to 1, 12 to 12, 25 to 25, 26 to 27, 63 to 35)
        for ((width, height) in dimensions) {
            val pixels = IntArray(width * height) { i ->
                ((i * 73 + (i / width) * 19 + (i % width) * (i % width) * 11) % 256)
            }
            assertArrayEquals("${width}x$height", reference(pixels, width, height),
                LineSegmenter.adaptiveThreshold(pixels, width, height))
        }
    }

    @Test
    fun `uniform black and white stay below no local mean`() {
        for (value in listOf(0, 255)) {
            val pixels = IntArray(31 * 29) { value }
            assertArrayEquals(BooleanArray(pixels.size), LineSegmenter.adaptiveThreshold(pixels, 31, 29))
        }
    }

    @Test
    fun `threshold comparison stays strict at the boundary`() {
        val pixels = IntArray(25) { 100 }
        pixels[12] = 92
        pixels[0] = 108
        assertFalse(LineSegmenter.adaptiveThreshold(pixels, 25, 1)[12])
        pixels[12] = 91
        pixels[0] = 109
        assertTrue(LineSegmenter.adaptiveThreshold(pixels, 25, 1)[12])
    }
}
