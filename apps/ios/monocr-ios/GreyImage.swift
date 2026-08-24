import Foundation

/**
 A single-channel 8-bit image: one byte per pixel, rows top-down, no padding.

 Segmentation, page normalisation and tiling all read pixels and nothing else, so
 they take this instead of `CGImage` or `UIImage`. That keeps them compilable and
 testable without a simulator — the tiling parity tests build fixture pages as
 plain byte arrays — and keeps every platform image concern in
 `GreyImage+UIKit.swift`, which is the only file in the chain that needs UIKit.
 */
nonisolated struct GreyImage {
    let pixels: [UInt8]
    let width: Int
    let height: Int

    init(pixels: [UInt8], width: Int, height: Int) {
        // A buffer that does not match its dimensions produces garbage reads at
        // some later offset rather than at the mistake, so refuse it here.
        precondition(width >= 0 && height >= 0, "grey image dimensions must not be negative")
        precondition(
            pixels.count == width * height,
            "grey image buffer is \(pixels.count) bytes, expected \(width * height) for \(width)x\(height)"
        )
        self.pixels = pixels
        self.width = width
        self.height = height
    }

    @inline(__always)
    func pixel(x: Int, y: Int) -> UInt8 {
        pixels[y * width + x]
    }

    @inline(__always)
    func contains(x: Int, y: Int) -> Bool {
        x >= 0 && y >= 0 && x < width && y < height
    }
}
