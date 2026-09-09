import Foundation

/**
 One line of a page, as read.

 `looksLikeALine` is carried per line rather than folded into the text because a
 band that is really a fused block still returns fluent Mon — measured upstream
 at confidence 0.83 for text that appears nowhere on the page. The reading is
 kept and flagged; nothing is dropped on the caller's behalf.

 Foundation-only, deliberately: `MonOcrResult` (which used to define this type
 alongside itself) imports UIKit for its `debugImage` field, so it cannot be
 symlinked into `MonOcrCore`, which also builds for macOS. `PdfPageCombiner`
 needs this type and has to be testable off-device, so this file was split out
 first — relocating it, not renaming or reshaping it.
 */
struct RecognizedLine {
    let text: String

    /// Page pixel coordinates of the band this text came from.
    let bbox: LineSegment

    /// How many model windows this line was split into. More than one means the
    /// line was tiled instead of squeezed.
    let tileCount: Int

    /// False when the band is shaped like a block of text, not a single line.
    let looksLikeALine: Bool
}
