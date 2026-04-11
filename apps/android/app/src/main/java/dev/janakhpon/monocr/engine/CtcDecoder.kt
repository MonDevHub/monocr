package dev.janakhpon.monocr.engine

/**
 * CTC greedy decoder — ported from monocr-onnx.ts decodePredictions().
 *
 * Algorithm:
 * 1. Argmax across class dimension per time step
 * 2. Remove blanks (index 0) and consecutive duplicate indices
 * 3. Map 1-indexed indices to charset characters
 */
object CtcDecoder {

    /**
     * Decode raw logits from the OCR model into a string.
     *
     * @param logits  Flat Float32 array from model output, shape [1, T, C]
     * @param timeSteps  Number of time steps (T)
     * @param numClasses Number of output classes (C), includes blank at 0
     * @param charset    The character set string (315 chars, 1-indexed)
     */
    fun decode(
        logits: FloatArray,
        timeSteps: Int,
        numClasses: Int,
        charset: String
    ): String {
        // Step 1: Greedy argmax per time step
        val predictions = IntArray(timeSteps)
        for (t in 0 until timeSteps) {
            val offset = t * numClasses
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val v = logits[offset + c]
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = c
                }
            }
            predictions[t] = maxIdx
        }

        // Step 2 & 3: CTC collapse — remove blanks and consecutive duplicates
        val decoded = StringBuilder()
        var prevIdx = -1
        for (idx in predictions) {
            if (idx != 0 && idx != prevIdx) {
                // 1-indexed: idx=1 → charset[0]
                val charIdx = idx - 1
                if (charIdx < charset.length) {
                    decoded.append(charset[charIdx])
                }
            }
            prevIdx = idx
        }

        return decoded.toString()
    }
}
