import Foundation
import UIKit

// LineSegment is already defined in LineSegment.swift

nonisolated enum ImagePreprocessor {
    
    static let targetHeight = 128
    static let minTargetWidth = 1024
    
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
    
    static func processLine(source: UIImage, segment: LineSegment) -> [Float]? {
        // 1. Initial Scale Calculation
        let hScale = CGFloat(targetHeight) / CGFloat(segment.height)
        
        // Match Android logic: squash horizontally if line is wider than targetWidth
        let rawScaledWidth = CGFloat(segment.width) * hScale
        let scaledWidth = min(rawScaledWidth, CGFloat(minTargetWidth))
        let finalWidth = minTargetWidth // Always produce 1024x128 to match model expectations
        
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
                // Draw the crop into the (0,0,scaledWidth,128) area.
                // This correctly squashes the line if scaledWidth was capped.
                croppedUi.draw(in: CGRect(x: 0, y: 0, width: scaledWidth, height: CGFloat(targetHeight)))
            } else {
                MonLog_w("Failed to crop CGImage for segment \(segment)")
            }
        }
        
        // 3. Extract pixels for bit-perfect Grayscale & Normalization
        // Using RGBA context to get raw R,G,B values and applying NTSC weights (matching Android)
        var pixelBytes = [UInt32](repeating: 0, count: finalWidth * targetHeight)
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
        
        // 4. Calculate Grayscale with NTSC weights and Adaptive Inversion
        var sumGray: Double = 0
        var count: Int = 0
        let activeIntWidth = Int(scaledWidth)
        var grayscaleValues = [Float](repeating: 0, count: finalWidth * targetHeight)
        
        for y in 0..<targetHeight {
            for x in 0..<finalWidth {
                let idx = y * finalWidth + x
                let pixel = pixelBytes[idx]
                
                // Extract R, G, B (assuming Big Endian RGBA)
                let r = Float((pixel >> 24) & 0xFF)
                let g = Float((pixel >> 16) & 0xFF)
                let b = Float((pixel >> 8) & 0xFF)
                
                // NTSC Weights: matching Android's (0.299f * r + 0.587f * g + 0.114f * b)
                let gray = 0.299 * r + 0.587 * g + 0.114 * b
                grayscaleValues[idx] = Float(gray)
                
                if x < activeIntWidth {
                    sumGray += Double(gray)
                    count += 1
                }
            }
        }
        
        let meanGray = count > 0 ? sumGray / Double(count) : 255.0
        // Use a slightly more conservative threshold for inversion
        let shouldInvert = meanGray < 120.0
        
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
            
            if x < activeIntWidth {
                if shouldInvert {
                    gray = 255.0 - gray
                }
                if applyStretch {
                    gray = (gray - stretchOffset) * stretchScale
                }
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
