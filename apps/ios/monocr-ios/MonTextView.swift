import SwiftUI
import UIKit

/**
 * A specialized UITextView wrapper for SwiftUI that handles 
 * complex Mon/Myanmar script shaping and Unicode normalization.
 */
struct MonTextView: UIViewRepresentable {
    let text: String
    
    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isScrollEnabled = true
        textView.textContainer.lineFragmentPadding = 12
        textView.textContainerInset = UIEdgeInsets(top: 8, left: 4, bottom: 8, right: 4)
        
        // Enable complex text shaping features
        textView.allowsEditingTextAttributes = true
        return textView
    }
    
    func updateUIView(_ uiView: UITextView, context: Context) {
        // String Normalization (Canonical Mapping)
        let normalizedText = text.precomposedStringWithCanonicalMapping
        
        // Font setup (Aligned with MonTypography.body size: 14)
        let font: UIFont
        let targetSize: CGFloat = 16 // Slightly larger for Mon script visibility, but normalized
        if let customFont = UIFont(name: "Pyidaungsu", size: targetSize) {
            font = customFont
        } else {
            // Myanmar Sangam MN is the standard high-quality iOS Myanmar font
            font = UIFont(name: "Myanmar Sangam MN", size: targetSize) ?? UIFont.systemFont(ofSize: targetSize)
        }
        
        // Create AttributedString for better control
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.label,
            .paragraphStyle: {
                let style = NSMutableParagraphStyle()
                style.lineSpacing = 8
                return style
            }()
        ]
        
        uiView.attributedText = NSAttributedString(string: normalizedText, attributes: attributes)
    }
}
