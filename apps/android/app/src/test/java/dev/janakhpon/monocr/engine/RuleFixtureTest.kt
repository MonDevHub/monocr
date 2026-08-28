package dev.janakhpon.monocr.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [LineSegmenter.suppressPageRules] against
 * `shared/segmentation-fixtures/rule-cases.json`, the expectations generated from the
 * printed-rule specification in `mon_OCR/src/monocr/segmenter.py` and shared with the
 * web and iOS ports.
 *
 * The point of a shared fixture is that three ports cannot drift apart quietly. A
 * disagreement here is either a bug in this port or a regenerated fixture, and both
 * need a human — do not adjust the expectations to match the code.
 *
 * The generator's docstring records two edge cases where the reference's cv2
 * morphology deviates from the sentence it implements, and why these expectations
 * follow the sentence. `--cross-check` re-derives that classification and fails on any
 * divergence it cannot attribute, so a new one cannot hide behind the known two.
 */
class RuleFixtureTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/rule-cases.json")
            ?: error(
                "rule-cases.json is not on the test classpath. It is wired in from " +
                    "shared/segmentation-fixtures by app/build.gradle.kts; check that source set."
            )
        JsonParser.parseReader(stream.reader(Charsets.UTF_8)).asJsonObject
    }

    /**
     * The 32-bit xorshift the generator describes, which every port reproduces.
     *
     * A PRNG rather than a literal mask because the cases run to 300x200; the
     * alternative is 60,000 booleans of JSON per case. It has to be a generator
     * exactly representable in all four languages, which is why it is not an LCG —
     * JS numbers lose precision above 2^53 and could not reproduce one.
     */
    private fun buildMask(case: JsonObject): BooleanArray {
        val w = case["width"].asInt
        val h = case["height"].asInt
        val density = case["density"].asInt
        val runLength = case["run_length"].asInt
        val runStart = case["run_start"].asInt

        var x = 2463534242u
        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            x = x xor (x shl 13)
            x = x xor (x shr 17)
            x = x xor (x shl 5)
            mask[i] = (x % 100u).toInt() < density
        }
        for (row in case["rule_rows"].asJsonArray) {
            val ry = row.asInt
            val length = if (runLength < 0) w else runLength
            val start = if (runLength < 0) 0 else runStart
            for (xx in start until minOf(w, start + length)) mask[ry * w + xx] = true
        }
        for (col in case["rule_cols"].asJsonArray) {
            val cx = col.asInt
            for (yy in 0 until h) mask[yy * w + cx] = true
        }
        return mask
    }

    /**
     * Ink count and a position-weighted checksum.
     *
     * A bare count would not notice suppression that removed the right NUMBER of
     * pixels in the wrong places, which is exactly what an off-by-one in a
     * run-length scan produces — and what the reference's even-kernel anchor does.
     */
    private fun signature(mask: BooleanArray): Pair<Int, Long> {
        var ink = 0
        var sum = 0L
        val modulus = fixture["checksum_modulus"].asLong
        for (i in mask.indices) if (mask[i]) {
            ink++
            sum = (sum + (i + 1)) % modulus
        }
        return ink to sum
    }

    /** The constants are the contract. A fixture generated with different ones is not this one. */
    @Test
    fun `the fixture was generated with this port's constants`() {
        assertEquals(LineSegmenter.RULE_SPAN, fixture["rule_span"].asFloat, 0.0f)
        assertEquals(LineSegmenter.RULE_MAX_INK_SHARE, fixture["rule_max_ink_share"].asFloat, 0.0f)
    }

    @Test
    fun `every fixture case matches`() {
        val cases = fixture["cases"].asJsonArray
        assertTrue("the fixture carried no cases", cases.size() > 0)

        for (element in cases) {
            val case = element.asJsonObject
            val name = case["name"].asString
            val mask = buildMask(case)

            val changed = LineSegmenter.suppressPageRules(mask, case["width"].asInt, case["height"].asInt)
            val (ink, checksum) = signature(mask)

            assertEquals("$name: changed", case["expected_changed"].asBoolean, changed)
            assertEquals("$name: remaining ink", case["expected_ink"].asInt, ink)
            assertEquals("$name: checksum", case["expected_checksum"].asLong, checksum)
        }
    }
}
