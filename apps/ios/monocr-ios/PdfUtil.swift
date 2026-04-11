import Foundation
import UIKit
import PDFKit
import CoreGraphics

/// Global PDF utility functions for MonOCR iOS.
/// Defined globally to ensure thread-safety and non-isolation in Swift 6.

nonisolated func PdfUtil_renderPdfPageToImage(at url: URL, pageIndex: Int = 0) -> UIImage? {
    guard let document = PDFDocument(url: url) else {
        return nil
    }
    
    guard pageIndex < document.pageCount, let page = document.page(at: pageIndex) else {
        return nil
    }
    
    let pageRect = page.bounds(for: .mediaBox)
    let rotation = page.rotation // 0, 90, 180, 270
    let scale: CGFloat = 4.0 // High DPI (288 DPI)
    
    var targetWidth = pageRect.width
    var targetHeight = pageRect.height
    if rotation == 90 || rotation == 270 {
        swap(&targetWidth, &targetHeight)
    }
    
    let finalPixelSize = CGSize(width: targetWidth * scale, height: targetHeight * scale)
    
    // 1. Use the highly-optimized thumbnail method which handles rotation and cropping perfectly.
    // We use a slightly larger size to ensure no detail is lost if internal crops exist.
    let renderedPage = page.thumbnail(of: finalPixelSize, for: .mediaBox)
    
    // 2. IMPORTANT: PDF thumbnails often have transparent backgrounds.
    // We must flatten this onto WHITE to ensure the segmenter sees black text on white background.
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1.0
    format.opaque = true
    
    let renderer = UIGraphicsImageRenderer(size: finalPixelSize, format: format)
    return renderer.image { context in
        // Fill white
        UIColor.white.set()
        context.fill(CGRect(origin: .zero, size: finalPixelSize))
        
        // Draw the rendered page on top
        renderedPage.draw(in: CGRect(origin: .zero, size: finalPixelSize))
    }
}

nonisolated func PdfUtil_getPageCount(at url: URL) -> Int {
    guard let document = PDFDocument(url: url) else {
        return 0
    }
    return document.pageCount
}

/// Namespace wrapper for compatibility
nonisolated struct PdfUtil {
    nonisolated static func renderPdfPageToImage(at url: URL, pageIndex: Int = 0) -> UIImage? {
        return PdfUtil_renderPdfPageToImage(at: url, pageIndex: pageIndex)
    }
    
    nonisolated static func getPageCount(at url: URL) -> Int {
        return PdfUtil_getPageCount(at: url)
    }
}
