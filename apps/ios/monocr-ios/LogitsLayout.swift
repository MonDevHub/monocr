import Foundation

/**
 Which axis of a logits tensor carries classes and which carries time.

 Core ML reports the traced shape at load and the real shape at prediction, and
 both are read here so the two cannot disagree quietly: a graph whose class axis
 does not match the bundled charset is rejected at load rather than decoded into
 well-formed, wrong Mon text.
 */
nonisolated enum LogitsLayout {

    /// Nil when no axis has the expected class count, or when there is no other
    /// axis left to read time from. Callers must treat nil as a failure — a
    /// tensor we cannot index is not a blank line.
    static func resolve(shape: [Int], expectedClasses: Int) -> (classAxis: Int, timeAxis: Int)? {
        guard let classAxis = shape.firstIndex(of: expectedClasses) else { return nil }

        // The remaining axes are the batch (1) and time (hundreds), so the
        // largest of them is time.
        var timeAxis = -1
        var largest = -1
        for axis in shape.indices where axis != classAxis {
            if shape[axis] > largest {
                largest = shape[axis]
                timeAxis = axis
            }
        }
        guard timeAxis >= 0 else { return nil }
        return (classAxis, timeAxis)
    }
}
