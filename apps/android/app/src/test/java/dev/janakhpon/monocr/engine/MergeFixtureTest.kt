package dev.janakhpon.monocr.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [LineSegmenter.mergeRuns] against
 * `shared/segmentation-fixtures/merge-cases.json`, the expectations generated from the
 * SPECIFICATION of the merge and shared with the web, iOS and Rust ports.
 *
 * [LineSegmenter.mergeRuns] is the only thing standing between raw-profile boundary
 * detection and a 22x garbage regression, and it now exists ten times in five
 * languages. Parity between those ten was checked once, by hand. This file is the
 * permanent version of that check.
 *
 * The expectations are NOT taken from any port — the generator reimplements the four
 * decisions from their statement. A fixture whose oracle is one of the implementations
 * proves only that they agree with each other, and if two of them are wrong in the
 * same way it certifies the bug. The generator additionally fails unless every one of
 * its twenty single-decision mutations is killed by some case and every case kills at
 * least one, and unless the greedy fold agrees with an independent brute-force
 * enumeration of every way to cut the run list into groups.
 *
 * A disagreement here is either a bug in this port or a regenerated fixture, and both
 * need a human — do not adjust the expectations to match the code.
 */
class MergeFixtureTest {

    /**
     * The fixture, from `MONOCR_MERGE_FIXTURE` if it is set and otherwise from the
     * test classpath, where `app/build.gradle.kts` wires in
     * `shared/segmentation-fixtures` as a test resource directory.
     *
     * Both paths fail loudly. A skip would report a green run for a port that nothing
     * checked, which is the failure mode this whole file exists to remove.
     */
    private val fixture: JsonObject by lazy {
        val override = System.getenv("MONOCR_MERGE_FIXTURE")
        val text = if (override != null) {
            val file = File(override)
            if (!file.isFile) {
                error("MONOCR_MERGE_FIXTURE=$override is not a file")
            }
            file.readText(Charsets.UTF_8)
        } else {
            val stream = javaClass.getResourceAsStream("/merge-cases.json")
                ?: error(
                    "merge-cases.json is not on the test classpath. It is wired in from " +
                        "shared/segmentation-fixtures by app/build.gradle.kts; check that " +
                        "source set, or set MONOCR_MERGE_FIXTURE to the file."
                )
            stream.reader(Charsets.UTF_8).readText()
        }
        JsonParser.parseString(text).asJsonObject
    }

    /**
     * The row profile a port must build from the same case description.
     *
     * Fills are applied IN ORDER and overwrite, which is how a one-row sub-threshold
     * dip is written over the band it sits inside. Applying them in any other order
     * gives a different profile and the fixture would not match.
     */
    private fun buildProfile(case: JsonObject): FloatArray {
        val hist = FloatArray(case["profile_length"].asInt)
        for (fill in case["profile_fills"].asJsonArray) {
            val triple = fill.asJsonArray
            val a = triple[0].asInt
            val b = triple[1].asInt
            val value = triple[2].asFloat
            for (y in a until b) hist[y] = value
        }
        return hist
    }

    private fun runsOf(case: JsonObject, key: String): List<Pair<Int, Int>> =
        case[key].asJsonArray.map {
            val pair = it.asJsonArray
            pair[0].asInt to pair[1].asInt
        }

    /** The constants are the contract. A fixture generated with different ones is not this one. */
    @Test
    fun `the fixture was generated with this port's constants`() {
        assertEquals(LineSegmenter.MIN_GAP_MERGE, fixture["min_gap_merge"].asInt)
        assertEquals(LineSegmenter.MIN_LINE_HEIGHT, fixture["min_line_height"].asInt)
    }

    @Test
    fun `every fixture case matches`() {
        val cases = fixture["cases"].asJsonArray
        assertTrue("the fixture carried no cases", cases.size() > 0)
        assertTrue(
            "the fixture carried no mutation battery",
            fixture["mutations"].asJsonObject.size() > 0
        )

        for (element in cases) {
            val case = element.asJsonObject
            val name = case["name"].asString
            val hist = buildProfile(case)

            val got = LineSegmenter.mergeRuns(
                runsOf(case, "runs"),
                hist,
                case["max_gap"].asInt,
                case["min_line"].asInt
            )

            // Exact equality, not a property. Half these cases assert that a merge
            // does NOT happen — a speckle chain that must not fuse, two real lines
            // that must stay apart — and asserting only the positive is what let the
            // speckle-chain defect survive a mutation battery once.
            assertEquals("$name: ${case["note"].asString}", runsOf(case, "expected"), got)

            // A regenerated fixture cannot quietly bring in padding: the generator
            // refuses to write a case no mutation kills, and this is the consumer-side
            // half of that guard, for a fixture edited by hand instead.
            assertTrue(
                "$name discriminates nothing",
                case["discriminates"].asJsonArray.size() > 0
            )
        }
    }
}
