package dev.janakhpon.monocr.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [PageNormalizer.dilateDisk] against `shared/segmentation-fixtures/dilate-cases.json`,
 * generated from `cv2.getStructuringElement(cv2.MORPH_ELLIPSE, ...)` — the call
 * `mon_OCR`'s `_level_background` makes, and therefore the contract.
 *
 * This file exists because of a defect it would have caught. Until 2026-08-28 iOS
 * dilated with a SQUARE while this port used a disk, the two disagreed on 7 of 8
 * synthetic pages, and both had green tests: iOS compared its optimised dilation
 * against a naive one that was also a square. Comparing an implementation against a
 * second implementation of the same misunderstanding proves only that the
 * misunderstanding is consistent.
 *
 * A disagreement here is either a bug in this port or a regenerated fixture, and both
 * need a human — do not adjust the expectations to match the code.
 */
class DilateFixtureTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/dilate-cases.json")
            ?: error(
                "dilate-cases.json is not on the test classpath. It is wired in from " +
                    "shared/segmentation-fixtures by app/build.gradle.kts; check that source set."
            )
        JsonParser.parseReader(stream.reader(Charsets.UTF_8)).asJsonObject
    }

    private fun source(width: Int, height: Int): IntArray {
        var x = 2463534242u
        return IntArray(width * height) {
            x = x xor (x shl 13); x = x xor (x shr 17); x = x xor (x shl 5)
            (x % 256u).toInt()
        }
    }

    /**
     * The SHAPE, asserted directly.
     *
     * Dilating an image that is black except for one bright centre pixel renders the
     * structuring element itself: every position the kernel reaches from the centre
     * comes back 255. That needs no new API on [PageNormalizer] and it compares
     * against cv2's own half-widths rather than against a second copy of this port's
     * formula, which is the mistake that let a square ship on iOS.
     */
    @Test
    fun `the structuring element is cv2's ellipse`() {
        val halfWidths = fixture["half_widths"].asJsonObject
        assertTrue("the fixture carried no kernels", halfWidths.size() > 0)

        for ((kernelText, rowsElement) in halfWidths.entrySet()) {
            val kernel = kernelText.toInt()
            val expected = rowsElement.asJsonArray.map { it.asInt }
            val r = kernel / 2
            val side = 2 * r + 1

            val single = IntArray(side * side)
            single[r * side + r] = 255
            val rendered = PageNormalizer.dilateDisk(single, side, side, kernel)

            for (row in 0 until side) {
                val on = (0 until side).filter { rendered[row * side + it] == 255 }
                if (expected[row] < 0) {
                    assertTrue("kernel $kernel row $row should be empty", on.isEmpty())
                    continue
                }
                assertEquals(
                    "kernel $kernel row $row: reached columns $on",
                    (r - expected[row])..(r + expected[row]),
                    on.first()..on.last()
                )
                assertEquals(
                    "kernel $kernel row $row must be one contiguous run",
                    on.last() - on.first() + 1, on.size
                )
            }
        }
    }

    /**
     * The kernel rule, against the reference's own answers.
     *
     * `levelBackground` picks `max(7, (smallH / 4) | 1)`. Nothing asserted the floor
     * of 7 until this test: a mutation dropping it to 3 survived the whole Android
     * suite on 2026-08-28. Asserted against values the generator computed from
     * `_level_background`, not against a second copy of the expression here, because
     * a test that restates the formula agrees with any version of it.
     */
    @Test
    fun `the dilation kernel matches the reference rule`() {
        val rule = fixture["kernel_for_small_height"].asJsonObject
        assertTrue("the fixture carried no kernel rule", rule.size() > 0)
        for ((heightText, expected) in rule.entrySet()) {
            val height = heightText.toInt()
            assertEquals(
                "kernel for a downsampled height of $height",
                expected.asInt, PageNormalizer.kernelForSmallHeight(height)
            )
        }
    }

    /**
     * The corner-patch rule, likewise. A mutation dropping the floor of 3 to 1 also
     * survived the whole suite, because it only changes behaviour on images under 30
     * pixels across and nothing tested one.
     */
    @Test
    fun `the corner patch matches the reference rule`() {
        val rule = fixture["corner_patch_for_side"].asJsonObject
        assertTrue("the fixture carried no corner rule", rule.size() > 0)
        for ((sideText, expected) in rule.entrySet()) {
            val side = sideText.toInt()
            assertEquals("corner patch for a side of $side", expected.asInt, PageNormalizer.cornerPatch(side))
        }
    }

    @Test
    fun `every dilation case matches cv2`() {
        val cases = fixture["cases"].asJsonArray
        assertTrue("the fixture carried no cases", cases.size() > 0)
        val modulus = fixture["checksum_modulus"].asLong

        for (element in cases) {
            val case = element.asJsonObject
            val name = case["name"].asString
            val w = case["width"].asInt
            val h = case["height"].asInt

            val out = PageNormalizer.dilateDisk(source(w, h), w, h, case["kernel"].asInt)

            var sum = 0L
            var checksum = 0L
            for (i in out.indices) {
                sum += out[i]
                checksum = (checksum + (i + 1).toLong() * out[i]) % modulus
            }
            assertEquals("$name: checksum", case["expected_checksum"].asLong, checksum)
            assertEquals("$name: sum", case["expected_sum"].asLong, sum)
        }
    }
}
