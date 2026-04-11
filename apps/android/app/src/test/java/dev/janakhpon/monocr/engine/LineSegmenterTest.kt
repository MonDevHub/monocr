package dev.janakhpon.monocr.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LineSegmenter].
 *
 * Creates synthetic bitmaps with known text line stripes and verifies
 * that segment() returns the correct number of segments with valid y/height.
 */
class LineSegmenterTest {

    /** Creates a white bitmap with horizontally-filled black stripes. */
    private fun createStripedBitmap(
        width: Int,
        height: Int,
        stripes: List<IntRange>  // row ranges to fill black
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (stripe in stripes) {
            for (y in stripe) {
                for (x in 0 until width) {
                    bmp.setPixel(x, y, Color.BLACK)
                }
            }
        }
        return bmp
    }

    @Test
    fun `two horizontal stripes produce two segments`() = runBlocking {
        val bitmap = createStripedBitmap(
            width = 300,
            height = 200,
            stripes = listOf(
                20..55,   // line 1: rows 20–55
                120..155  // line 2: rows 120–155
            )
        )

        val segments = LineSegmenter.segment(bitmap)
        bitmap.recycle()

        assertEquals("Expected 2 segments", 2, segments.size)
        // Each segment should cover the stripe region (with padding)
        assertTrue("Segment 0 y should be near 20", segments[0].y <= 20)
        assertTrue("Segment 0 height should cover stripe", segments[0].height >= 35)
        assertTrue("Segment 1 y should be near 120", segments[1].y <= 120)
        assertTrue("Segment 1 height should cover stripe", segments[1].height >= 35)
    }

    @Test
    fun `single stripe produces one segment`() = runBlocking {
        val bitmap = createStripedBitmap(
            width = 200,
            height = 100,
            stripes = listOf(30..60)
        )

        val segments = LineSegmenter.segment(bitmap)
        bitmap.recycle()

        assertEquals("Expected 1 segment", 1, segments.size)
    }

    @Test
    fun `blank image produces no segments`() = runBlocking {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        val segments = LineSegmenter.segment(bitmap)
        bitmap.recycle()

        assertEquals("Expected 0 segments for blank image", 0, segments.size)
    }
}
