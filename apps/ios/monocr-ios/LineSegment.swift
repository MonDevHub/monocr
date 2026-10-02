import Foundation

/**
 * Data structure representing a segmented line of text.
 * Ported from Android LineSegment.
 */
nonisolated struct LineSegment: Codable {
    let x: Int
    let y: Int
    let width: Int
    let height: Int
}
