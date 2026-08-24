package dev.janakhpon.monocr.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [LineTiler] against `shared/segmentation-fixtures/tiling-cases.json`, the
 * expectations generated from the reference implementation in monocr-onnx and shared
 * with the web, iOS and Rust tilers.
 *
 * The point of a shared fixture is that four ports cannot drift apart quietly. A
 * disagreement here is either a bug in this port or a regenerated fixture, and both
 * need a human — do not adjust the expectations to match the code.
 *
 * This runs on a plain JVM because [LineTiler] takes a [GreyImage] rather than an
 * `android.graphics.Bitmap`. The tests that predate it built bitmaps directly, which
 * throws "not mocked" off a device, so they could not have been passing.
 */
class LineTilerFixtureTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/tiling-cases.json")
            ?: error(
                "tiling-cases.json is not on the test classpath. It is wired in from " +
                    "shared/segmentation-fixtures by app/build.gradle.kts; check that source set."
            )
        JsonParser.parseReader(stream.reader(Charsets.UTF_8)).asJsonObject
    }

    private val targetHeight: Int get() = fixture["target_height"].asInt
    private val targetWidth: Int get() = fixture["target_width"].asInt

    /**
     * Ink is grey 0, background grey 255, and every column of a given x follows the
     * same rule, so a case is a vertical-stripe pattern described by one modulus.
     */
    private fun buildImage(case: JsonObject): GreyImage {
        val width = case["width"].asInt
        val height = case["height"].asInt
        val ink = case["ink"].asJsonObject
        val kind = ink["kind"].asString
        val modulus = ink["modulus"].asInt

        val columnIsInk = BooleanArray(width) { x ->
            when (kind) {
                "mod_eq" -> x % modulus == 0
                "mod_ne" -> x % modulus != 0
                "solid" -> true
                "blank" -> false
                else -> error("unknown ink rule in fixture: kind=$kind")
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (columnIsInk[x]) 0 else 255
            }
        }
        return GreyImage(width, height, pixels)
    }

    @Test
    fun `every fixture case tiles to the expected widths`() {
        val cases = fixture["cases"].asJsonArray
        assertTrue("fixture has no cases", cases.size() > 0)

        for (element in cases) {
            val case = element.asJsonObject
            val name = case["name"].asString
            val expected = case["expected_tile_widths"].asJsonArray.map { it.asInt }

            val tiles = LineTiler.tileLine(buildImage(case), targetHeight, targetWidth)

            assertEquals("[$name] tile widths", expected, tiles.map { it.width })
        }
    }

    @Test
    fun `tiles partition the line with no gap and no overlap`() {
        for (element in fixture["cases"].asJsonArray) {
            val case = element.asJsonObject
            val name = case["name"].asString
            val width = case["width"].asInt

            val tiles = LineTiler.tileLine(buildImage(case), targetHeight, targetWidth)

            assertEquals("[$name] first tile must start at 0", 0, tiles.first().x0)
            assertEquals("[$name] last tile must end at the line width", width, tiles.last().x1)
            assertEquals(
                "[$name] tile widths must sum to the line width",
                width,
                tiles.sumOf { it.width }
            )
            for (i in 0 until tiles.size - 1) {
                assertEquals(
                    "[$name] gap or overlap between tile $i and ${i + 1}",
                    tiles[i].x1,
                    tiles[i + 1].x0
                )
            }
            // A zero-width tile would loop forever in the caller and read nothing.
            assertTrue("[$name] every tile must be at least one pixel wide", tiles.all { it.width >= 1 })
        }
    }

    @Test
    fun `cut column probes land where the reference implementation puts them`() {
        val probes = fixture["cut_column_probes"].asJsonArray
        assertTrue("fixture has no cut column probes", probes.size() > 0)

        for (element in probes) {
            val probe = element.asJsonObject
            val name = probe["name"].asString
            val image = buildImage(probe)

            val cut = LineTiler.cutColumn(
                image,
                probe["x0"].asInt,
                probe["ideal"].asInt,
                probe["width"].asInt
            )

            assertEquals("[$name] cut column", probe["expected_cut"].asInt, cut)
        }
    }

    @Test
    fun `the fixture and this build agree on the tuning constants`() {
        // A fixture regenerated with different constants would otherwise fail as a
        // pile of off-by-a-few width mismatches instead of saying what changed.
        assertEquals(
            "cut search fraction",
            fixture["cut_search_fraction"].asDouble,
            LineTiler.CUT_SEARCH_FRACTION,
            0.0
        )
        assertEquals("cut ink threshold", fixture["cut_ink_threshold"].asInt, LineTiler.CUT_INK_THRESHOLD)
        assertEquals("target height", targetHeight, ImagePreprocessor.TARGET_HEIGHT)
        assertEquals("target width", targetWidth, ImagePreprocessor.TARGET_WIDTH)
    }
}
