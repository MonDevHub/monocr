import SwiftUI
import UIKit

/**
 * A specialized UITextView wrapper for SwiftUI that handles 
 * complex Mon/Myanmar script shaping and Unicode normalization.
 *
 * The text follows Dynamic Type (`UIFontMetrics` for `.body`), and the view is
 * as tall as its text up to `maxHeight`, scrolling only past that. It was a fixed
 * 16pt font in a fixed 300pt box: a two-line reading sat in a mostly empty box,
 * and at large text sizes most of a long one was hidden behind a scroll inside
 * the screen's own scroll.
 */
struct MonTextView: UIViewRepresentable {
    let text: String
    var maxHeight: CGFloat = 480
    
    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isScrollEnabled = true
        textView.textContainer.lineFragmentPadding = 12
        textView.textContainerInset = UIEdgeInsets(top: 8, left: 4, bottom: 8, right: 4)
        textView.adjustsFontForContentSizeCategory = true
        textView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        
        // Enable complex text shaping features
        textView.allowsEditingTextAttributes = true
        return textView
    }
    
    func updateUIView(_ uiView: UITextView, context: Context) {
        // String Normalization (Canonical Mapping)
        let normalizedText = text.precomposedStringWithCanonicalMapping
        
        // 16pt at the default text size, scaled from there.
        let baseSize: CGFloat = 16
        let base = UIFont(name: "Pyidaungsu", size: baseSize)
            // Myanmar Sangam MN is the standard high-quality iOS Myanmar font
            ?? UIFont(name: "Myanmar Sangam MN", size: baseSize)
            ?? UIFont.systemFont(ofSize: baseSize)
        let font = UIFontMetrics(forTextStyle: .body).scaledFont(for: base)
        
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

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite, width > 0 else { return nil }
        let fitting = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: min(fitting.height, maxHeight))
    }
}
