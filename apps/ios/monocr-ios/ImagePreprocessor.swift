import Foundation
import UIKit

// LineSegment is already defined in LineSegment.swift

nonisolated enum ImagePreprocessor {
    
    static let targetHeight = ModelWindow.height
    static let minTargetWidth = ModelWindow.width

    /// Debug: Store the last processed line image
    private static var _lastProcessedLineImage: UIImage?
    static var lastProcessedLineImage: UIImage? {
        get { synchronized { _lastProcessedLineImage } }
        set { synchronized { _lastProcessedLineImage = newValue } }
    }
    
    private static let lock = NSLock()
    private static func synchronized<T>(_ closure: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }; return closure()
    }
    
    /**
     Scale one line — or one tile of a line — into the model window.

     `source` must be a polarity-normalised page (see `PageNormalizer`), and
     `segment` is in that page's pixel coordinates. Wide lines are cut into tiles
     by `LineTiler` before they get here, so the width clamp below is a backstop
     rather than the normal path.

     That clamp used to be priced at `CER 0.1434 against 0.0795 tiled`. Retired
     2026-08-22 — harness never committed, figures do not reproduce. What the
     clamp actually costs is width-dependent and unbounded: at four model windows
     a squeezed line scores 0.21 CER against tiling's 0.06, and by six it is above
     0.83 (`mon_OCR/eval/tiling-ab-2026-08-22.md`). It stays as a backstop
     precisely because reaching it means something upstream failed to tile.
     */
    static func processLine(source: UIImage, segment: LineSegment) -> [Float]? {
        guard segment.width > 0, segment.height > 0 else {
            MonLog_w("refusing to preprocess an empty segment \(segment)")
            return nil
        }

        // 1. Initial Scale Calculation
        let hScale = CGFloat(targetHeight) / CGFloat(segment.height)

        // Match Android logic: squash horizontally if line is wider than targetWidth
        let rawScaledWidth = CGFloat(segment.width) * hScale
        let scaledWidth = min(rawScaledWidth, CGFloat(minTargetWidth))
        let finalWidth = minTargetWidth // Always produce 1024x160 to match model expectations
        
        // 2. Assemble the scaled and padded line image using UIKit
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        format.opaque = true
        
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: finalWidth, height: targetHeight), format: format)
        let assembledImage = renderer.image { ctx in
            // Background: White
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: CGFloat(finalWidth), height: CGFloat(targetHeight)))
            
            // CROP & DRAW
            // Instead of drawing the whole source with a giant offset, 
            // we crop specifically what we need. This is safer for memory.
            guard let cgImage = source.cgImage else { return }
            
            let cropRect = CGRect(
                x: CGFloat(segment.x),
                y: CGFloat(segment.y),
                width: CGFloat(segment.width),
                height: CGFloat(segment.height)
            )
            
            if let croppedCg = cgImage.cropping(to: cropRect) {
                let croppedUi = UIImage(cgImage: croppedCg)
                // Draw the crop into the (0,0,scaledWidth,targetHeight) area.
                // Named rather than written as a literal: this said 128 and kept
                // saying it after the window moved to 160, because the draw below
                // reads ModelWindow.height while the comment read nothing.
                // This correctly squashes the line if scaledWidth was capped.
                croppedUi.draw(in: CGRect(x: 0, y: 0, width: scaledWidth, height: CGFloat(targetHeight)))
            } else {
                MonLog_w("Failed to crop CGImage for segment \(segment)")
            }
        }
        
        // 3. Extract pixels for bit-perfect Grayscale & Normalization
        // Using RGBA context to get raw R,G,B values and applying NTSC weights (matching Android)
        // A byte buffer, read in memory order. This was `[UInt32]` read with shifts,
        // which is only correct on a big-endian host: CoreGraphics writes the bytes
        // R,G,B,A, and a native 32-bit load on arm64 yields 0xAABBGGRR, so
        // `(pixel >> 24) & 0xFF` returned ALPHA rather than red.
        //
        // `makeUIImage` hands this a DeviceGray image, so R == G == B and the channel
        // swap between green and blue cancelled. Alpha did not: it is always 255, so
        // every luma came out `0.299 * 255 + 0.701 * v` — the range compressed from
        // [0, 255] to [76, 255] with an offset. The contrast stretch below cancels an
        // affine map whenever it fires, which is why this survived, but its gate is a
        // range threshold and iOS's range was 0.701 of Android's. Any tile whose true
        // range sits between the two thresholds stretched on Android and not here,
        // and on those tiles the model saw a compressed, offset signal.
        //
        // `GreyImage+UIKit.swift` reads an identically-configured context byte-wise
        // and has always been right; the two files disagreed about one buffer layout.
        var pixelBytes = [UInt8](repeating: 0, count: finalWidth * targetHeight * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        
        guard let context = CGContext(data: &pixelBytes,
                                    width: finalWidth,
                                    height: targetHeight,
                                    bitsPerComponent: 8,
                                    bytesPerRow: finalWidth * 4,
                                    space: colorSpace,
                                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return nil }
        
        // Flip context for top-down memory layout
        context.translateBy(x: 0, y: CGFloat(targetHeight))
        context.scaleBy(x: 1.0, y: -1.0)
        
        UIGraphicsPushContext(context)
        assembledImage.draw(in: CGRect(x: 0, y: 0, width: finalWidth, height: targetHeight))
        UIGraphicsPopContext()
        
        // 4. Calculate Grayscale with NTSC weights.
        //
        // Polarity is NOT decided here any more. It used to be: a per-line mean
        // under 120 flipped that line. But the segmenter had already run on the
        // un-inverted page, so on a dark-mode screenshot it had measured the
        // background as ink and returned the gaps between lines. Flipping
        // afterwards cannot undo that. PageNormalizer now does it once, before
        // segmentation, and it is not idempotent — so this must not do it again.
        let activeIntWidth = Int(scaledWidth)
        var grayscaleValues = [Float](repeating: 0, count: finalWidth * targetHeight)

        for y in 0..<targetHeight {
            for x in 0..<finalWidth {
                let idx = y * finalWidth + x
                let offset = idx * 4

                // R, G, B in memory order. Alpha, at offset + 3, is deliberately
                // not read: it is always 255 here and reading it as red is the
                // defect this loop used to carry.
                let r = Float(pixelBytes[offset])
                let g = Float(pixelBytes[offset + 1])
                let b = Float(pixelBytes[offset + 2])

                // NTSC Weights: matching Android's (0.299f * r + 0.587f * g + 0.114f * b)
                let gray = 0.299 * r + 0.587 * g + 0.114 * b
                grayscaleValues[idx] = Float(gray)
            }
        }

        // 5. Contrast Stretching: Find min/max in the active region and scale to [0, 255]
        var minG: Float = 255.0
        var maxG: Float = 0.0
        for idx in 0..<(finalWidth * targetHeight) {
            let x = idx % finalWidth
            if x < activeIntWidth {
                let g = grayscaleValues[idx]
                if g < minG { minG = g }
                if g > maxG { maxG = g }
            }
        }
        
        let rangeG = maxG - minG
        let applyStretch = rangeG > 30.0
        let stretchScale = applyStretch ? 255.0 / Float(rangeG) : 1.0
        let stretchOffset = applyStretch ? minG : 0.0
        
        // 6. Final Normalization & Debug Data
        var float32 = [Float](repeating: 0, count: finalWidth * targetHeight)
        var debugData = [UInt8](repeating: 0, count: finalWidth * targetHeight)
        
        for idx in 0..<(finalWidth * targetHeight) {
            var gray = grayscaleValues[idx]
            let x = idx % finalWidth
            
            if x < activeIntWidth && applyStretch {
                gray = (gray - stretchOffset) * stretchScale
            }
            
            float32[idx] = gray / 127.5 - 1.0
            debugData[idx] = UInt8(max(0, min(255, gray)))
        }
        
        // Update Static Debug Image
        let debugColorSpace = CGColorSpaceCreateDeviceGray()
        if let debugContext = CGContext(data: &debugData, width: finalWidth, height: targetHeight, bitsPerComponent: 8, bytesPerRow: finalWidth, space: debugColorSpace, bitmapInfo: CGImageAlphaInfo.none.rawValue),
           let debugCg = debugContext.makeImage() {
            lastProcessedLineImage = UIImage(cgImage: debugCg)
        }
        
        return float32
    }
}
