import Foundation
import UIKit

/**
 Data model for MonOCR results.
 */
struct MonOcrResult {
    /// The extracted text
    let text: String
    
    /// Estimated word count
    let wordCount: Int
    
    /// Total character count
    let charCount: Int
    
    /// Total duration in milliseconds
    let durationMs: Int
    
    /// Preprocessed image used for debugging (engine input)
    let debugImage: UIImage?
}
