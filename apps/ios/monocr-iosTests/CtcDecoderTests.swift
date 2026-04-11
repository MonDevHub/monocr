import XCTest
@testable import monocr_ios

final class CtcDecoderTests: XCTestCase {
    
    func testDecodeSimpleSequenceWithoutRepetition() {
        let charset = "ABC"
        let numClasses = 4 // blank + A + B + C
        let timeSteps = 5
        
        // Sequence: A A B blank C → after CTC collapse: ABC
        // Blank is index 0
        let argmaxes = [1, 1, 2, 0, 3]
        let logits = buildLogits(timeSteps: timeSteps, numClasses: numClasses, argmaxes: argmaxes)
        
        let result = CtcDecoder.decode(logits: logits, timeSteps: timeSteps, numClasses: numClasses, charset: charset)
        XCTAssertEqual(result, "ABC")
    }
    
    func testAllBlankProducesEmptyString() {
        let charset = "AB"
        let numClasses = 3
        let timeSteps = 4
        let argmaxes = [0, 0, 0, 0]
        let logits = buildLogits(timeSteps: timeSteps, numClasses: numClasses, argmaxes: argmaxes)
        
        let result = CtcDecoder.decode(logits: logits, timeSteps: timeSteps, numClasses: numClasses, charset: charset)
        XCTAssertEqual(result, "")
    }
    
    func testRepeatedSameCharWithBlankSeparatorStaysAsTwo() {
        let charset = "A"
        let numClasses = 2
        let timeSteps = 3
        let argmaxes = [1, 0, 1]
        let logits = buildLogits(timeSteps: timeSteps, numClasses: numClasses, argmaxes: argmaxes)
        
        let result = CtcDecoder.decode(logits: logits, timeSteps: timeSteps, numClasses: numClasses, charset: charset)
        XCTAssertEqual(result, "AA")
    }
    
    func testConsecutiveDuplicatesCollapsed() {
        let charset = "A"
        let numClasses = 2
        let timeSteps = 3
        let argmaxes = [1, 1, 1]
        let logits = buildLogits(timeSteps: timeSteps, numClasses: numClasses, argmaxes: argmaxes)
        
        let result = CtcDecoder.decode(logits: logits, timeSteps: timeSteps, numClasses: numClasses, charset: charset)
        XCTAssertEqual(result, "A")
    }
    
    private func buildLogits(timeSteps: Int, numClasses: Int, argmaxes: [Int]) -> [Float] {
        var logits = Array(repeating: Float(-10.0), count: timeSteps * numClasses)
        for t in 0..<timeSteps {
            logits[t * numClasses + argmaxes[t]] = 10.0
        }
        return logits
    }
}
