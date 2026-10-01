package dev.janakhpon.monocr.engine

import dev.janakhpon.monocr.ui.ScanGeneration
import dev.janakhpon.monocr.util.exifTransform
import org.junit.Assert.*
import org.junit.Test

class ImportLifecycleTest {
    @Test fun `all eight EXIF orientations map a labeled non-square grid correctly`() {
        val expected = listOf("abcdef", "cbafed", "fedcba", "defabc", "adbecf", "daebfc", "fcebda", "cfbead")
        for (orientation in 1..8) {
            val t = exifTransform(orientation)
            val points = (0..5).map { i ->
                Triple((t[0] * (i % 3) + t[1] * (i / 3)).toInt(),
                    (t[2] * (i % 3) + t[3] * (i / 3)).toInt(), 'a' + i)
            }
            val actual = points.sortedWith(compareBy({ it.second }, { it.first })).map { it.third }.joinToString("")
            assertEquals("EXIF $orientation", expected[orientation - 1], actual)
        }
    }

    @Test fun `new import and clear invalidate delayed job publication`() {
        val generations = ScanGeneration()
        val first = generations.next()
        val second = generations.next()
        assertFalse(generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
        generations.next() // Clear invalidates even non-cancellable work.
        assertFalse(generations.isCurrent(second))
    }

    @Test fun `cancelling current import invalidates only that generation`() {
        val generations = ScanGeneration()
        val cancelled = generations.next()

        assertTrue(generations.cancelIfCurrent(cancelled))
        assertFalse(generations.isCurrent(cancelled))
    }

    @Test fun `late cancellation cannot invalidate newer import`() {
        val generations = ScanGeneration()
        val first = generations.next()
        val second = generations.next()

        assertFalse(generations.cancelIfCurrent(first))
        assertTrue(generations.isCurrent(second))
    }
}
