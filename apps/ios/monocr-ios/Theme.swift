import SwiftUI

struct MonTheme {
    static let backgroundColor = Color(light: Color(red: 0.984, green: 0.984, blue: 0.984), // #FBFBFB
                                     dark: Color(red: 0.082, green: 0.082, blue: 0.090))   // #151517
    
    static let surfaceColor = Color(light: Color(red: 0.98, green: 0.98, blue: 0.98),    // #FAFAFA
                                   dark: Color(red: 0.094, green: 0.094, blue: 0.106))   // #18181B

    static let secondaryTextColor = Color.secondary.opacity(0.8)
    
    // Semantic Status Colors
    static let success = Color.green
    static let warning = Color.orange
    static let error = Color.red
    
    // Typography Constitution (20, 16, 14, 13, 12)
    struct Typography {
        static let title     = Font.system(size: 20, weight: .semibold)
        static let section   = Font.system(size: 14, weight: .semibold) // Reduced from 16
        static let body      = Font.system(size: 13, weight: .regular)  // Reduced from 14
        static let secondary = Font.system(size: 12, weight: .medium)   // Reduced from 13
        static let meta      = Font.system(size: 11, weight: .regular)  // Reduced from 12
        
        /// Mon text. The name must be the font's own, and `Font.custom` fails
        /// **silently** to the system font when it is not.
        ///
        /// This read `"PyidaungSu-Regular"` until 2026-08-26. No name record in
        /// `pyidaungsu_regular.ttf` carries that string — nameID 1 and nameID 6 are
        /// both `Pyidaungsu`, and the only record containing "Regular" is the bare
        /// subfamily. So both call sites (`HistoryViews`, `FeedbackView`) rendered
        /// recognised Mon in Myanmar Sangam MN, whose Mon coverage is thin, with
        /// nothing logged. `MonTextView` used the correct name, which is why the
        /// main result card looked right and these two did not.
        ///
        /// Keep this string equal to nameID 6. Read it back with:
        ///   python3 -c "from fontTools.ttLib import TTFont; \
        ///     print([str(r) for r in TTFont('Fonts/pyidaungsu_regular.ttf')['name'].names if r.nameID==6])"
        static func monBody(size: CGFloat = 18) -> Font {
            return Font.custom("Pyidaungsu", size: size)
        }
    }
}

extension Color {
    init(light: Color, dark: Color) {
        self.init(uiColor: UIColor { traitCollection in
            switch traitCollection.userInterfaceStyle {
            case .dark:
                return UIColor(dark)
            default:
                return UIColor(light)
            }
        })
    }
}
