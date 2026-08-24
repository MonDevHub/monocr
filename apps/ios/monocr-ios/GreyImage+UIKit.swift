import Foundation
import UIKit
import CoreGraphics

/**
 The only bridge between platform images and the grey buffer the OCR chain reads.

 Kept apart from `GreyImage.swift` so segmentation, normalisation and tiling stay
 free of UIKit and can be exercised without a simulator.
 */
extension GreyImage {

    /**
     Flatten a `UIImage` into an upright, pixel-sized grey buffer.

     Drawing through `UIImage.draw` rather than reading `cgImage` directly is
     deliberate: `cgImage` ignores `imageOrientation`, so a camera photo would
     arrive rotated and every line would be a column.

     Returns nil rather than an empty buffer when the image has no pixels or a
     bitmap context cannot be made — the caller must report that, not OCR a
     blank page.
     */
    static func upright(_ image: UIImage) -> GreyImage? {
        let width = Int((image.size.width * image.scale).rounded())
        let height = Int((image.size.height * image.scale).rounded())
        guard width > 0, height > 0 else {
            MonLog_e("cannot read image pixels: size=\(image.size) scale=\(image.scale)")
            return nil
        }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: width, height: height), format: format
        )
        let flattened = renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }

        guard let cgImage = flattened.cgImage else {
            MonLog_e("flattened image has no CGImage backing")
            return nil
        }
        return GreyImage(cgImage: cgImage)
    }

    /**
     Read a `CGImage` as grey using the NTSC weights the rest of the chain uses.

     CoreGraphics could convert to a grey colour space itself, but it does so
     colorimetrically, which would give the tiler's ink threshold and the
     preprocessor's normalisation two different ideas of the same pixel.
     */
    init?(cgImage: CGImage) {
        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0 else { return nil }

        let byteCount = width * height * 4
        let rgba = UnsafeMutablePointer<UInt8>.allocate(capacity: byteCount)
        defer { rgba.deallocate() }
        rgba.initialize(repeating: 0, count: byteCount)

        guard let context = CGContext(
            data: rgba,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                | CGBitmapInfo.byteOrder32Big.rawValue
        ) else {
            MonLog_e("could not create an RGBA context for \(width)x\(height)")
            return nil
        }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        var grey = [UInt8](repeating: 0, count: width * height)
        for i in 0..<(width * height) {
            let offset = i * 4
            let luminosity = 0.299 * Float(rgba[offset])
                + 0.587 * Float(rgba[offset + 1])
                + 0.114 * Float(rgba[offset + 2])
            grey[i] = UInt8(max(0, min(255, luminosity)))
        }

        self.init(pixels: grey, width: width, height: height)
    }

    /// Render back to a `UIImage`, so the preprocessor can crop and scale tiles
    /// out of the normalised page with the same drawing path as before.
    func makeUIImage() -> UIImage? {
        guard width > 0, height > 0 else { return nil }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        guard let cgImage = CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 8,
            bytesPerRow: width,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        ) else {
            MonLog_e("could not build a grey CGImage for \(width)x\(height)")
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}
