import Testing

@testable import MonOcrCore

/**
 CTC greedy decode: collapse consecutive duplicates, then drop the blank.

 The blank is index 0, so a charset of N characters is decoded through N + 1
 classes and every index shifts by one. Getting that offset wrong yields
 well-formed text that is wrong by one character everywhere, which is why the
 cases below pin the mapping and not just the collapse.
 */
struct CtcDecoderTests {

    /// One-hot-ish logits: the winning class per time step gets 10, the rest -10.
    private func buildLogits(timeSteps: Int, numClasses: Int, argmaxes: [Int]) -> [Float] {
        var logits = [Float](repeating: -10.0, count: timeSteps * numClasses)
        for t in 0..<timeSteps {
            logits[t * numClasses + argmaxes[t]] = 10.0
        }
        return logits
    }

    @Test func decodeSimpleSequenceWithoutRepetition() {
        // A A B blank C over blank + A + B + C, which collapses to ABC.
        let logits = buildLogits(timeSteps: 5, numClasses: 4, argmaxes: [1, 1, 2, 0, 3])
        let result = CtcDecoder.decode(logits: logits, timeSteps: 5, numClasses: 4, charset: "ABC")
        #expect(result == "ABC")
    }

    @Test func allBlankProducesEmptyString() {
        let logits = buildLogits(timeSteps: 4, numClasses: 3, argmaxes: [0, 0, 0, 0])
        let result = CtcDecoder.decode(logits: logits, timeSteps: 4, numClasses: 3, charset: "AB")
        #expect(result == "")
    }

    /// The blank between the two A's is the only thing that makes them two
    /// characters rather than one, so this is the case that proves the collapse
    /// keys on the previous index and not on the emitted string.
    @Test func repeatedSameCharWithBlankSeparatorStaysAsTwo() {
        let logits = buildLogits(timeSteps: 3, numClasses: 2, argmaxes: [1, 0, 1])
        let result = CtcDecoder.decode(logits: logits, timeSteps: 3, numClasses: 2, charset: "A")
        #expect(result == "AA")
    }

    @Test func consecutiveDuplicatesCollapsed() {
        let logits = buildLogits(timeSteps: 3, numClasses: 2, argmaxes: [1, 1, 1])
        let result = CtcDecoder.decode(logits: logits, timeSteps: 3, numClasses: 2, charset: "A")
        #expect(result == "A")
    }
}
