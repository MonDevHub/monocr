import Foundation

/**
 * CTC greedy decoder — ported from dev.janakhpon.monocr.engine.CtcDecoder in Android.
 */
/// The model emitted a class the charset cannot name.
///
/// Unreachable once the model contract holds, and that is the point: it means the
/// graph emits more classes than the charset describes, so the two are different
/// generations. Android raises the same condition as `ModelContractException` and
/// web as `ModelContractError`; this port used to skip the index and carry on,
/// which turned a generation mismatch into a plausible reading with characters
/// quietly missing.
nonisolated struct ModelContractError: Error, CustomStringConvertible {
    let predictedClass: Int
    let charsetLength: Int
    var description: String {
        "model predicted class \(predictedClass) but the charset has only "
            + "\(charsetLength) characters; the model and the charset are "
            + "different generations"
    }
}

nonisolated enum CtcDecoder {
    
    /**
     * Decode raw logits from the OCR model into a string.
     *
     * @param logits     Flat Float32 array from model output, shape [1, T, C]
     * @param timeSteps  Number of time steps (T)
     * @param numClasses Number of output classes (C), includes blank at 0
     * @param charset    The character set string
     */
    static func decode(logits: [Float], timeSteps: Int, numClasses: Int, charset: String) throws -> String {
        // Port logic: Android's String[idx] uses UTF-16 code units.
        // We must map charset using UTF-16 to ensure 1:1 parity with the model's indexing.
        let utf16Chars = Array(charset.utf16)
        
        // Step 1: Greedy argmax per time step
        var predictions = [Int](repeating: 0, count: timeSteps)
        var nonBlankCount = 0
        var uniqueIndices = Set<Int>()
        
        for t in 0..<timeSteps {
            let offset = t * numClasses
            var maxIdx = 0
            var maxVal = -Float.infinity
            for c in 0..<numClasses {
                let v = logits[offset + c]
                if v > maxVal {
                    maxVal = v
                    maxIdx = c
                }
            }
            predictions[t] = maxIdx
            if maxIdx != 0 {
                nonBlankCount += 1
                uniqueIndices.insert(maxIdx)
            }
        }
        
        MonLogger.d("CTC Decoding: Sequence length \(timeSteps), Non-blank steps: \(nonBlankCount), Unique chars: \(uniqueIndices.count)")
        
        // Step 2 & 3: CTC collapse — remove blanks (0) and consecutive duplicates
        let decoded = NSMutableString()
        var prevIdx = -1
        var pathDescription = ""
        
        for idx in predictions {
            if idx != 0 && idx != prevIdx {
                let charIdx = idx - 1
                guard charIdx >= 0, charIdx < utf16Chars.count else {
                    throw ModelContractError(
                        predictedClass: idx, charsetLength: utf16Chars.count)
                }
                let scalarValue = UInt32(utf16Chars[charIdx])
                guard let scalar = UnicodeScalar(scalarValue) else {
                    // A lone UTF-16 surrogate: the charset itself is malformed at
                    // this index, which is the same class of contract failure.
                    throw ModelContractError(
                        predictedClass: idx, charsetLength: utf16Chars.count)
                }
                let char = String(Character(scalar))
                decoded.append(char)
                pathDescription += "[\(idx):\(char)] "
            }
            prevIdx = idx
        }
        
        let result = decoded as String
        MonLogger.d("CTC Path: \(pathDescription)")
        MonLogger.i("CTC Result: '\(result)'")
        return result
    }
}
