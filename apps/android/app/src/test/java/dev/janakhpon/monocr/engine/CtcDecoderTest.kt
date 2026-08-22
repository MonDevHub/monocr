package dev.janakhpon.monocr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [CtcDecoder].
 *
 * Constructs hand-crafted logit arrays and verifies the decoded output.
 */
class CtcDecoderTest {

    /**
     * Build a flat logit array where each time step has a clear argmax.
     * Shape: [1, T, C] — we flatten to T*C.
     */
    private fun buildLogits(timeSteps: Int, numClasses: Int, argmaxes: IntArray): FloatArray {
        val logits = FloatArray(timeSteps * numClasses) { -10f }
        for (t in 0 until timeSteps) {
            logits[t * numClasses + argmaxes[t]] = 10f  // clear winner
        }
        return logits
    }

    @Test
    fun `decode simple sequence without repetition`() {
        // charset: "ABC" (idx 1→A, 2→B, 3→C), blank=0
        val charset = "ABC"
        val numClasses = 4  // blank + A + B + C
        val timeSteps = 5

        // Sequence: A A B blank C → after CTC collapse: ABC
        val argmaxes = intArrayOf(1, 1, 2, 0, 3)
        val logits = buildLogits(timeSteps, numClasses, argmaxes)

        val result = CtcDecoder.decode(logits, timeSteps, numClasses, charset)
        assertEquals("ABC", result)
    }

    @Test
    fun `all blank produces empty string`() {
        val charset = "AB"
        val numClasses = 3
        val timeSteps = 4
        val argmaxes = intArrayOf(0, 0, 0, 0)
        val logits = buildLogits(timeSteps, numClasses, argmaxes)

        val result = CtcDecoder.decode(logits, timeSteps, numClasses, charset)
        assertEquals("", result)
    }

    @Test
    fun `repeated same char with blank separator stays as two`() {
        // A blank A → "AA" (blank separates identical chars)
        val charset = "A"
        val numClasses = 2  // blank, A
        val timeSteps = 3
        val argmaxes = intArrayOf(1, 0, 1)
        val logits = buildLogits(timeSteps, numClasses, argmaxes)

        val result = CtcDecoder.decode(logits, timeSteps, numClasses, charset)
        assertEquals("AA", result)
    }

    @Test
    fun `a class the charset cannot name is an error, not a dropped character`() {
        // The model claims 4 classes, so 3 characters, but the charset names 2. This
        // used to skip the unnameable index and return "A", which reads as a plausible
        // result and hides the fact that the model and the charset are different
        // generations.
        val charset = "AB"
        val numClasses = 4
        val timeSteps = 2
        val logits = buildLogits(timeSteps, numClasses, intArrayOf(1, 3))

        assertThrows(ModelContractException::class.java) {
            CtcDecoder.decode(logits, timeSteps, numClasses, charset)
        }
    }

    @Test
    fun `consecutive duplicates collapsed`() {
        // A A A → "A" (no blank, pure duplicates)
        val charset = "A"
        val numClasses = 2
        val timeSteps = 3
        val argmaxes = intArrayOf(1, 1, 1)
        val logits = buildLogits(timeSteps, numClasses, argmaxes)

        val result = CtcDecoder.decode(logits, timeSteps, numClasses, charset)
        assertEquals("A", result)
    }
}
